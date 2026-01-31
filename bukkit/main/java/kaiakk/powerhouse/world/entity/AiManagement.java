package kaiakk.powerhouse.world.entity;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Vector;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import kaiakk.multimedia.classes.SchedulerHelper;
import kaiakk.powerhouse.helpers.scaling.Scalable;
import kaiakk.powerhouse.helpers.scaling.ScaleUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import java.lang.reflect.Method;

public class AiManagement implements Scalable, Listener {
	private static final double DIST_SQ = 64.0 * 64.0;
	private final Set<UUID> simplified = ConcurrentHashMap.newKeySet();
	private final Map<UUID, Boolean> originalAI = new ConcurrentHashMap<>();

	private volatile BukkitTask task = null;
	private final DoubleSupplier msptSupplier;
	private Plugin plugin;

	private volatile double currentScale = 1.0;

	private static final Method GET_TARGET_METHOD;
	private static final Method SET_TARGET_METHOD;
	private static final Method SET_AWARE_METHOD;
	static {
		Method get = null;
		Method setTarget = null;
		Method setAware = null;
		try {
			get = LivingEntity.class.getMethod("getTarget");
		} catch (Throwable ignored) {}
		try {
			setTarget = LivingEntity.class.getMethod("setTarget", org.bukkit.entity.Entity.class);
		} catch (Throwable ignored) {}
		try {
			setAware = LivingEntity.class.getMethod("setAware", boolean.class);
		} catch (Throwable ignored) {}
		GET_TARGET_METHOD = get;
		SET_TARGET_METHOD = setTarget;
		SET_AWARE_METHOD = setAware;
	}

	public AiManagement(DoubleSupplier msptSupplier) {
		this.msptSupplier = msptSupplier;
	}

	private void setAiSafe(LivingEntity ent, boolean enabled) {
		try { kaiakk.powerhouse.helpers.internal.ItemVersion.setAI(ent, enabled); } catch (Throwable ignored) {}
	}

	public void start(Plugin plugin) {
		if (task != null) return;
		this.plugin = plugin;
		try { Bukkit.getPluginManager().registerEvents(this, plugin); } catch (Throwable ignored) {}
		task = SchedulerHelper.runTimerSeconds(plugin, this::tickMainThreadSnapshot, 1.0, 5.0);
	}

	public void stop() {
		if (task != null) {
			SchedulerHelper.cancelTask(task);
			task = null;
		}
		try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
		for (UUID id : simplified) {
			Entity e = kaiakk.powerhouse.world.entity.EntityLookup.getEntity(id);
			if (e instanceof LivingEntity && !(e instanceof Player) && e.isValid() && !e.isDead()) {
				restoreMobAI((LivingEntity) e);
			}
		}
		simplified.clear();
		originalAI.clear();
	}

	private void tickMainThreadSnapshot() {
		String tname = Thread.currentThread().getName();
		if (tname != null && (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin) || tname.contains("Region") || tname.contains("Threaded"))) {
			try { SchedulerHelper.run(plugin, this::tickMainThreadSnapshot); } catch (Throwable ignored) {}
			return;
		}

		final List<MobSnapshot> snapshots = new ArrayList<>();
		final Map<String, List<double[]>> playersByWorld = new HashMap<>();

		for (World world : Bukkit.getWorlds()) {
			List<double[]> playerLocs = new ArrayList<>();
			for (Player p : world.getPlayers()) {
				org.bukkit.Location pl = p.getLocation();
				playerLocs.add(new double[] { pl.getX(), pl.getY(), pl.getZ() });
			}
			playersByWorld.put(world.getName(), playerLocs);

			for (Entity ent : world.getEntities()) {
				if (!(ent instanceof LivingEntity) || ent instanceof Player || !ent.isValid()) continue;
				if (ent.getType() == EntityType.ARMOR_STAND) continue;

				LivingEntity mob = (LivingEntity) ent;
				org.bukkit.Location el = mob.getLocation();
				boolean hasTarget = false;
				try {
					if (GET_TARGET_METHOD != null) {
						Object t = GET_TARGET_METHOD.invoke(mob);
						if (t != null) hasTarget = true;
					}
				} catch (Throwable ignored) {}
				snapshots.add(new MobSnapshot(ent.getUniqueId(), world.getName(), el.getX(), el.getY(), el.getZ(), hasTarget));
			}
		}

		SchedulerHelper.runAsync(plugin, () -> calculateAIStatesAsync(snapshots, playersByWorld));
	}

	private void calculateAIStatesAsync(List<MobSnapshot> snapshots, Map<String, List<double[]>> playersByWorld) {
		final Set<UUID> toSimplify = new HashSet<>();
		final Set<UUID> toRestore = new HashSet<>();
		final double currentMspt = msptSupplier == null ? 0.0 : msptSupplier.getAsDouble();
		final double s = currentScale;
		final boolean highLag = s < 0.05; 
		double dynamicDistSq = ScaleUtils.multiplierFromScale(s, 32.0 * 32.0, 64.0 * 64.0);

		for (MobSnapshot snap : snapshots) {
			List<double[]> playerLocs = playersByWorld.get(snap.worldName);
			if (playerLocs == null || playerLocs.isEmpty()) continue;
			
			double minSq = Double.POSITIVE_INFINITY;
			for (double[] pp : playerLocs) {
				double dx = snap.x - pp[0], dy = snap.y - pp[1], dz = snap.z - pp[2];
				double d2 = dx*dx + dy*dy + dz*dz;
				if (d2 < minSq) minSq = d2;
				if (minSq <= 25.0) break;
			}

			boolean isSimplified = simplified.contains(snap.id);

			if (minSq > dynamicDistSq && !snap.hasActiveTarget) {
				if (!isSimplified) toSimplify.add(snap.id);
			} else {
				if (isSimplified) toRestore.add(snap.id);
			}
		}

		if (!toSimplify.isEmpty() || !toRestore.isEmpty()) {
			SchedulerHelper.run(plugin, () -> applyAIChangesSync(toSimplify, toRestore));
		}
	}

	private void applyAIChangesSync(Set<UUID> toSimplify, Set<UUID> toRestore) {
		for (UUID id : toSimplify) {
			Entity e = kaiakk.powerhouse.world.entity.EntityLookup.getEntity(id);
			if (e instanceof LivingEntity && !(e instanceof Player)) simplifyMobAI((LivingEntity) e);
		}
		for (UUID id : toRestore) {
			Entity e = kaiakk.powerhouse.world.entity.EntityLookup.getEntity(id);
			if (e instanceof LivingEntity && !(e instanceof Player)) restoreMobAI((LivingEntity) e);
		}
	}
	
	private static class MobSnapshot {
		final UUID id;
		final String worldName;
		final double x, y, z;
		final boolean hasActiveTarget;
		MobSnapshot(UUID id, String worldName, double x, double y, double z, boolean hasActiveTarget) {
			this.id = id; this.worldName = worldName; this.x = x; this.y = y; this.z = z; this.hasActiveTarget = hasActiveTarget;
		}
	}


	private void simplifyMobAI(LivingEntity mob) {
		UUID id = mob.getUniqueId();
		boolean orig = true;
		try { orig = mob.hasAI(); } catch (Throwable ignored) {}
		originalAI.put(id, orig);

		setAiSafe(mob, false);
		try {
			if (SET_TARGET_METHOD != null) {
				SET_TARGET_METHOD.invoke(mob, (Object) null);
			}
		} catch (Throwable ignored) {}
		try {
			if (SET_AWARE_METHOD != null) {
				SET_AWARE_METHOD.invoke(mob, false);
			}
		} catch (Throwable ignored) {}
		try { mob.setVelocity(new Vector(0, 0, 0)); } catch (Throwable ignored) {}
		simplified.add(id);
	}

	private void restoreMobAI(LivingEntity mob) {
		UUID id = mob.getUniqueId();
		boolean orig = originalAI.getOrDefault(id, true);
		setAiSafe(mob, orig);
		try {
			if (SET_AWARE_METHOD != null) {
				SET_AWARE_METHOD.invoke(mob, true);
			}
		} catch (Throwable ignored) {}
		simplified.remove(id);
		originalAI.remove(id);
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onDeath(EntityDeathEvent e) {
		if (e == null || e.getEntity() == null) return;
		UUID id = e.getEntity().getUniqueId();
		try { simplified.remove(id); } catch (Throwable ignored) {}
		try { originalAI.remove(id); } catch (Throwable ignored) {}
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onUnload(ChunkUnloadEvent e) {
		if (e == null) return;
		try {
			for (Entity ent : e.getChunk().getEntities()) {
				if (ent == null) continue;
				UUID id = ent.getUniqueId();
				try { simplified.remove(id); } catch (Throwable ignored) {}
				try { originalAI.remove(id); } catch (Throwable ignored) {}
			}
		} catch (Throwable ignored) {}
	}

	@Override
	public void setScale(double scale) {
		if (Double.isNaN(scale)) return;
		this.currentScale = Math.max(0.0, Math.min(1.0, scale));
	}
}


