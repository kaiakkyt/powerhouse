package kaiakk.powerhouse.world.entity;

import kaiakk.multimedia.classes.SchedulerHelper;
import kaiakk.powerhouse.calculations.entity.CrammingCalculator;
import kaiakk.powerhouse.data.snapshot.EntitySnapshot;
import kaiakk.powerhouse.helpers.scaling.Scalable;
import kaiakk.powerhouse.helpers.scaling.ScaleUtils;
import kaiakk.powerhouse.world.AllOptimizations;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.function.DoubleSupplier;

public class EntityPusher implements Scalable {
    private final Plugin plugin;
    private volatile int threshold;
    private final double radius;
    private final double intervalSeconds;
    private volatile BukkitTask task = null;
    private final DoubleSupplier msptSupplier;
    private static final int MAX_MAP_ENTRIES = 5000;
    private final java.util.Map<java.util.UUID, Long> aiDisabled = createLRUMap(MAX_MAP_ENTRIES);
    private static final int MAX_ENTITIES_PER_RUN = 500;

    public EntityPusher(Plugin plugin, int threshold, double radius, double intervalSeconds) {
        this(plugin, threshold, radius, intervalSeconds, null);
    }

    public EntityPusher(Plugin plugin, int threshold, double radius, double intervalSeconds, DoubleSupplier msptSupplier) {
        this.plugin = plugin;
        this.threshold = threshold;
        this.baseThreshold = threshold;
        this.radius = radius;
        this.intervalSeconds = intervalSeconds;
        this.msptSupplier = msptSupplier;
    }

    private volatile int baseThreshold = 16;

    @Override
    public void setScale(double scale) {
        double m = ScaleUtils.multiplierFromScale(scale, 0.5, 2.0);
        int thr = Math.max(1, (int) Math.round((double) baseThreshold * m));
        setThreshold(thr);
    }

    private void setAiSafe(LivingEntity ent, boolean enabled) {
        try { kaiakk.powerhouse.helpers.internal.ItemVersion.setAI(ent, enabled); } catch (Throwable ignored) {}
    }

    public void setThreshold(int newThreshold) {
        if (newThreshold <= 0) return;
        this.threshold = newThreshold;
    }

    public boolean isAiDisabled(UUID id) {
        if (id == null) return false;
        return aiDisabled.containsKey(id);
    }

    public void start() {
        if (task != null) return;
        task = SchedulerHelper.runTimerSeconds(plugin, this::tickMain, intervalSeconds, intervalSeconds);
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
        final List<EntitySnapshot> globalSnaps = new ArrayList<>();
        final double mspt = msptSupplier == null ? 0.0 : msptSupplier.getAsDouble();
        double s = ScaleUtils.continuousScaleFromMspt(ScaleUtils.getSmoothedMspt());
        final boolean canDisableAI = s < 0.30; 

        try { aiDisabled.keySet().removeIf(id -> getEntitySafe(id) == null); } catch (Throwable ignored) {}

        final Set<LivingEntity> toDisableAI = new HashSet<>();
        final Set<LivingEntity> toEnableAI = new HashSet<>();

        for (World w : Bukkit.getWorlds()) {
            final String worldName = w.getName();
            final List<Player> worldPlayers = w.getPlayers();

            final double[][] playerLocs = new double[worldPlayers.size()][];
            for (int i = 0; i < worldPlayers.size(); i++) {
                Location pl = worldPlayers.get(i).getLocation();
                playerLocs[i] = new double[] { pl.getX(), pl.getY(), pl.getZ() };
            }

            if (worldPlayers.isEmpty()) continue;

            final int chunkRadius = 2;
            final Set<Chunk> chunksToProcess = new HashSet<>();
            for (Player p : worldPlayers) {
                int pcx = p.getLocation().getBlockX() >> 4;
                int pcz = p.getLocation().getBlockZ() >> 4;
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                        int tx = pcx + dx;
                        int tz = pcz + dz;
                        try {
                            if (!w.isChunkLoaded(tx, tz)) continue;
                            Chunk c = w.getChunkAt(tx, tz);
                            if (c != null) chunksToProcess.add(c);
                        } catch (Throwable ignored) {}
                    }
                }
            }

            int processed = 0;
            for (Chunk c : chunksToProcess) {
                if (processed >= MAX_ENTITIES_PER_RUN) break;
                try {
                    for (Entity e : c.getEntities()) {
                        if (e == null || !e.isValid() || e instanceof Player || !(e instanceof LivingEntity) || e instanceof ArmorStand) continue;
                        LivingEntity living = (LivingEntity) e;
                        Location eloc = e.getLocation();
                        double ex = eloc.getX();
                        double ey = eloc.getY();
                        double ez = eloc.getZ();

                        double minDistSq = Double.MAX_VALUE;
                        if (playerLocs.length > 0) {
                            for (double[] pp : playerLocs) {
                                double dx = ex - pp[0];
                                double dy = ey - pp[1];
                                double dz = ez - pp[2];
                                double d2 = dx*dx + dy*dy + dz*dz;
                                if (d2 < minDistSq) minDistSq = d2;
                                if (minDistSq <= 25.0) break;
                            }
                        }

                        boolean hasName = (e.getCustomName() != null && !e.getCustomName().isEmpty());
                        int worth = hasName ? 250 : 50;
                        if (minDistSq <= 25.0) worth += 100;

                        if (canDisableAI) {
                            if (minDistSq == Double.MAX_VALUE) {
                                toDisableAI.add(living);
                            } else {
                                if (minDistSq > 1024.0) {
                                    toDisableAI.add(living);
                                } else if (minDistSq <= 256.0) {
                                    toEnableAI.add(living);
                                }
                            }
                        }

                        globalSnaps.add(new EntitySnapshot(
                                e.getUniqueId(), worldName,
                                ex, ey, ez,
                                e.getType(), hasName, false, worth
                        ));

                        processed++;
                        if (processed >= MAX_ENTITIES_PER_RUN) break;
                    }
                } catch (Throwable ignored) {}
            }
            }

        if (canDisableAI) {
            for (LivingEntity ent : toDisableAI) {
                try {
                    java.util.UUID id = ent.getUniqueId();
                    if (aiDisabled.containsKey(id)) continue;
                    setAiSafe(ent, false);
                    aiDisabled.put(id, System.currentTimeMillis());
                } catch (Throwable ignored) {}
            }
            for (LivingEntity ent : toEnableAI) {
                try {
                    java.util.UUID id = ent.getUniqueId();
                    if (!aiDisabled.containsKey(id)) continue;
                    setAiSafe(ent, true);
                    aiDisabled.remove(id);
                } catch (Throwable ignored) {}
            }
        }

        if (globalSnaps.isEmpty()) return;

        final List<EntitySnapshot> snapCopy = new ArrayList<>(globalSnaps);
                SchedulerHelper.runAsync(plugin, () -> {
            final Set<UUID> toRemove = CrammingCalculator.calculateCrammingRemovals(snapCopy, radius, threshold);
            if (toRemove == null || toRemove.isEmpty()) return;
                    try { kaiakk.powerhouse.helpers.logs.DebugLog.debug("EntityPusher: cramming detection found " + (toRemove == null ? 0 : toRemove.size()) + " candidates"); } catch (Throwable ignored) {}

            SchedulerHelper.runLater(plugin, () -> {
                int removed = 0;
                for (UUID id : toRemove) {
                    final Entity e = getEntitySafe(id);
                    if (e == null || !e.isValid() || e instanceof ArmorStand) continue;
                    try {
                        AllOptimizations.getInstance().markEntityDead(e);
                        removed++;
                    } catch (Throwable ignored) {}
                }
                try {
                    AllOptimizations ao = AllOptimizations.getInstance();
                    if (ao != null) {
                        if (removed > 0) ao.addCrammingRemovals(removed);
                        if (removed > 0) {
                            try { kaiakk.powerhouse.helpers.logs.DebugLog.debug("EntityPusher: removed " + removed + " entities due to cramming (threshold=" + threshold + ", radius=" + radius + ")"); } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }, 1L);
        });
    }

    private Entity getEntitySafe(UUID id) {
        if (id == null) return null;
        try {
            try {
                java.lang.reflect.Method m = Bukkit.class.getMethod("getEntity", java.util.UUID.class);
                Object res = m.invoke(null, id);
                if (res instanceof Entity) return (Entity) res;
            } catch (Throwable ignored) {}

            try {
                Object server = Bukkit.getServer();
                if (server != null) {
                    java.lang.reflect.Method m2 = server.getClass().getMethod("getEntity", java.util.UUID.class);
                    Object res2 = m2.invoke(server, id);
                    if (res2 instanceof Entity) return (Entity) res2;
                }
            } catch (Throwable ignored) {}

            for (World w : Bukkit.getWorlds()) {
                try {
                    for (Chunk c : w.getLoadedChunks()) {
                        for (Entity e : c.getEntities()) {
                            if (e != null && id.equals(e.getUniqueId())) return e;
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static <K,V> java.util.Map<K,V> createLRUMap(final int maxEntries) {
        return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<K,V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<K,V> eldest) {
                return size() > maxEntries;
            }
        });
    }
}


