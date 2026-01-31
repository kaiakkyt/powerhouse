package kaiakk.powerhouse.world.entity;

import kaiakk.multimedia.classes.SchedulerHelper;
import kaiakk.powerhouse.calculations.EntityCalculator;
import kaiakk.powerhouse.data.RecentActionTracker;
import kaiakk.powerhouse.data.snapshot.EntitySnapshot;
import kaiakk.powerhouse.world.AllOptimizations;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Wither;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class EntityCulling {
	private final Plugin plugin;
	private final int maxPerChunk;
	private final double maxDistance;
	private final double intervalSeconds;
	private volatile BukkitTask task = null;

	private static final java.util.concurrent.ConcurrentHashMap<String, Integer> WORLD_ID = new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.concurrent.atomic.AtomicInteger NEXT_WORLD_ID = new java.util.concurrent.atomic.AtomicInteger(1);

	public EntityCulling(Plugin plugin, int maxPerChunk, double maxDistance, double intervalSeconds) {
		this.plugin = plugin;
		this.maxPerChunk = maxPerChunk;
		this.maxDistance = maxDistance;
		this.intervalSeconds = intervalSeconds;
	}

	private void safeRemoveEntity(Entity e) {
		if (e == null) return;
		if (e instanceof Player) return;
		try {
			if (!e.isDead()) AllOptimizations.getInstance().markEntityDead(e);
		} catch (Throwable ignored) {}
	}

	public void start() {
		final EntityCulling self = this;
		if (task != null) return;
		task = SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
			public void run() { self.tickMain(); }
		}, intervalSeconds, intervalSeconds);
	}

	public void stop() {
		if (task != null) {
			SchedulerHelper.cancelTask(task);
			task = null;
		}
	}

	private void tickMain() {
		String tname = Thread.currentThread().getName();
		if (tname != null && (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin) || tname.contains("Region") || tname.contains("Threaded"))) {
			try { SchedulerHelper.run(plugin, this::tickMain); } catch (Throwable ignored) {}
			return;
		}
		final List<EntitySnapshot> snapshots = new ArrayList<EntitySnapshot>();
		final Map<String, List<double[]>> playersByWorld = new HashMap<String, List<double[]>>();
		final Set<UUID> present = new HashSet<UUID>();

		final Set<UUID> immediateRemovals = new HashSet<UUID>();
		final Map<Long, Integer> crammingCount = new HashMap<Long, Integer>();

		for (World w : Bukkit.getWorlds()) {
			String worldName = (w == null) ? "" : w.getName();
			List<double[]> playerList = new ArrayList<double[]>();
			for (Player p : w.getPlayers()) {
				playerList.add(new double[] { p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ() });
			}
			if (!playerList.isEmpty()) playersByWorld.put(worldName, playerList);

			for (Entity e : w.getEntities()) {
				if (e == null || !e.isValid()) continue;

					Location loc = e.getLocation();

					if (e instanceof ArmorStand) {
						continue;
					}

					UUID id = e.getUniqueId();
					int bx = loc.getBlockX();
					int by = loc.getBlockY();
					int bz = loc.getBlockZ();

					if (e instanceof Player) continue;

					LivingEntity le = (e instanceof LivingEntity) ? (LivingEntity) e : null;

					if (le != null) {
						try {
							if (le.getCustomName() != null) continue;
						} catch (Throwable ignored) {}
						try {
							if (le instanceof Tameable) continue;
						} catch (Throwable ignored) {}
						try {
							if (le instanceof EnderDragon || le instanceof Wither) continue;
						} catch (Throwable ignored) {}
						try {
							if (le.getScoreboardTags().contains("PH_KEEP")) continue;
						} catch (Throwable ignored) {}
					}

					long packed = packLocation(bx, by, bz, worldName);
					Integer cur = crammingCount.merge(packed, 1, Integer::sum);
					if (cur != null && cur > maxPerChunk) immediateRemovals.add(id);
					present.add(id);
					boolean isPlayer = (e instanceof Player);
					boolean hasName = e.getCustomName() != null && !e.getCustomName().isEmpty();
					int worth = 50;
					if (hasName) worth += 200;
					if (e.getType() == EntityType.EXPERIENCE_ORB) worth = 1;

					if (e instanceof Item) {
						Item item = (Item) e;
						int ageTicks = item.getTicksLived();
						if (ageTicks > 30 * 20) worth = Math.min(worth, 5);
					}

					snapshots.add(new EntitySnapshot(id, worldName, loc.getX(), loc.getY(), loc.getZ(), e.getType(), hasName, isPlayer, worth));
				}
			}

		final List<EntitySnapshot> snapForAsync = new ArrayList<EntitySnapshot>(snapshots);
		final Map<String, List<double[]>> playersForAsync = new HashMap<String, List<double[]>>(playersByWorld);
		final Set<UUID> presentNow = new HashSet<UUID>(present);

		if (!immediateRemovals.isEmpty()) {
			final Set<UUID> toKillNow = new HashSet<UUID>(immediateRemovals);
			try { kaiakk.powerhouse.helpers.internal.DebugLog.debug("EntityCulling: scheduling immediate removals: " + toKillNow.size()); } catch (Throwable ignored) {}
				SchedulerHelper.runLater(plugin, new Runnable() {
				public void run() {
					int removed = 0;
					for (UUID id : toKillNow) {
						Entity e = kaiakk.powerhouse.world.entity.EntityLookup.getEntity(id);
						if (e == null) continue;
						if (e instanceof Player) continue;
						try {
							RecentActionTracker rt = RecentActionTracker.getInstance();
							boolean recentPlayer = (rt != null && rt.wasEntityRecentlyPlayerSpawned(id));
							if (recentPlayer) continue;
							safeRemoveEntity(e);
							removed++;
						} catch (Throwable ignored) {}
					}
					try { kaiakk.powerhouse.helpers.internal.DebugLog.debug("EntityCulling: applied immediate removals, removed=" + removed); } catch (Throwable ignored) {}
				}
			}, 1L);

			for (UUID u : toKillNow) {
				snapForAsync.removeIf(s -> s != null && s.uuid.equals(u));
			}
		}

		SchedulerHelper.runAsync(plugin, new Runnable() {
			public void run() {
				final Set<UUID> toRemove = EntityCalculator.calculateCullCandidates(snapForAsync, playersForAsync, maxPerChunk, maxDistance);
				if (toRemove == null || toRemove.isEmpty()) return;

				try { kaiakk.powerhouse.helpers.internal.DebugLog.debug("EntityCulling: async culling candidates=" + (toRemove == null ? 0 : toRemove.size())); } catch (Throwable ignored) {}
				SchedulerHelper.runLater(plugin, new Runnable() {
					public void run() {
						int removed = 0;
						for (UUID id : toRemove) {
							if (!presentNow.contains(id)) continue;
							Entity e = kaiakk.powerhouse.world.entity.EntityLookup.getEntity(id);
							if (e == null) continue;
							if (e instanceof Player) continue;
							try {
								RecentActionTracker rt = RecentActionTracker.getInstance();
								boolean recentPlayer = (rt != null && rt.wasEntityRecentlyPlayerSpawned(id));
								if (recentPlayer) continue;

								safeRemoveEntity(e);
								removed++;
							} catch (Throwable ignored) {}
						}
						try { kaiakk.powerhouse.helpers.internal.DebugLog.debug("EntityCulling: applied async removals, removed=" + removed); } catch (Throwable ignored) {}
					}
				}, 1L);
			}
		});
	}

	private long packLocation(int bx, int by, int bz, String worldName) {
		int wid = WORLD_ID.computeIfAbsent(worldName == null ? "" : worldName, k -> NEXT_WORLD_ID.getAndIncrement());
		long w = (long) (wid & 0xFF) << 56;
		long y = (long) (by + 128 & 0x3FF) << 46;
		long x = (long) (bx & 0x7FFFFFFL) << 23;
		long z = (long) (bz & 0x7FFFFFFL);
		return w | y | x | z;
	}
}

