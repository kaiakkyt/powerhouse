package kaiakk.powerhouse.world.explosion;

import kaiakk.multimedia.classes.SchedulerHelper;
import kaiakk.powerhouse.data.BlockKey;
import kaiakk.powerhouse.data.RecentActionTracker;
import kaiakk.powerhouse.helpers.internal.PowerhouseLogger;
import kaiakk.powerhouse.helpers.scaling.ScaleUtils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import java.util.function.DoubleSupplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.bukkit.Material;
import java.util.Comparator;
import java.util.List;
import org.bukkit.event.entity.EntityDamageEvent;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.Queue;

public class ExplosionCanceller implements Listener {
    private final int threshold;
    private final ConcurrentMap<BlockKey, Integer> explosionCounts = new ConcurrentHashMap<>();
    private final ConcurrentMap<java.util.UUID, String> tntOrigins = new ConcurrentHashMap<>();
    private final ConcurrentMap<java.util.UUID, Long> tntOriginTimestamps = new ConcurrentHashMap<>();
    private volatile long retentionWindowMs = 30_000L;
    private final Plugin plugin;
    private volatile BukkitTask cleanupTask;
    private final DoubleSupplier msptSupplier;
    private final int playerProximityRadius = 32;
    private static final double FREEZE_SCALE_THRESHOLD = 0.35;
    private static final long FREEZE_DURATION_MS = 2_500L;
    private static final double FREEZE_RADIUS = 12.0;
    private final Queue<FreezeRegion> freezeRegions = new ConcurrentLinkedQueue<>();

    public ExplosionCanceller(Plugin plugin, int threshold, long windowMs, DoubleSupplier msptSupplier) {
        this.plugin = plugin;
        this.threshold = threshold;
        this.msptSupplier = msptSupplier;
        if (windowMs > 0) this.retentionWindowMs = windowMs;
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        cleanupTask = SchedulerHelper.runTimerSeconds(plugin, this::cleanupExpired, 1.0, 1.0);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitySpawn(EntitySpawnEvent ev) {
        if (ev == null || ev.getEntity() == null) return;
        try {
            if (!(ev.getEntity() instanceof org.bukkit.entity.TNTPrimed)) return;
            org.bukkit.entity.Entity e = ev.getEntity();
            java.util.UUID id = e.getUniqueId();

            try {
                try {
                    org.bukkit.entity.Entity src = ((org.bukkit.entity.TNTPrimed)e).getSource();
                    if (src instanceof Player) {
                        tntOrigins.put(id, "player");
                        tntOriginTimestamps.put(id, System.currentTimeMillis());
                        return;
                    }
                } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            try {
                Location ploc = e.getLocation();
                if (ploc != null) {
                    BlockKey pbk = BlockKey.from(ploc);
                    RecentActionTracker rt = RecentActionTracker.getInstance();
                    if (rt != null && pbk != null) {
                        if (rt.wasRecentPlayerPlacement(pbk, 30_000L)) {
                            tntOrigins.put(id, "player");
                            tntOriginTimestamps.put(id, System.currentTimeMillis());
                            return;
                        }
                        if (rt.wasRecentDispense(pbk, 30_000L)) {
                            tntOrigins.put(id, "dispense");
                            tntOriginTimestamps.put(id, System.currentTimeMillis());
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            try {
                Location loc = e.getLocation();
                if (loc != null && loc.getWorld() != null) {
                    
                    boolean nearPlayer = false;
                    try {
                        try {
                            double radiusSqNearby = 3.0 * 3.0;
                            for (Player p : loc.getWorld().getPlayers()) {
                                try { if (p != null && p.getLocation().distanceSquared(loc) <= radiusSqNearby) { nearPlayer = true; break; } } catch (Throwable ignored) {}
                            }
                            if (!nearPlayer) {
                                try {
                                    java.util.Collection<Entity> found = loc.getWorld().getNearbyEntities(loc, 3.0, 3.0, 3.0);
                                    for (Entity near : found) { if (near instanceof Player) { nearPlayer = true; break; } }
                                } catch (Throwable ignored) {}
                            }
                        } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}

                    if (nearPlayer) {
                        tntOrigins.put(id, "player");
                        tntOriginTimestamps.put(id, System.currentTimeMillis());
                        return;
                    }

                    
                    try {
                        boolean anyPrivileged = false;
                        for (Player wp : loc.getWorld().getPlayers()) {
                            try { if (wp != null && (wp.isOp() || wp.getGameMode() == org.bukkit.GameMode.CREATIVE)) { anyPrivileged = true; break; } } catch (Throwable ignored) {}
                        }
                        if (anyPrivileged) {
                            org.bukkit.block.Block base = loc.getBlock();
                            int r = 1;
                            for (int dx = -r; dx <= r; dx++) {
                                for (int dy = -r; dy <= r; dy++) {
                                    for (int dz = -r; dz <= r; dz++) {
                                        try {
                                            org.bukkit.block.Block b = base.getRelative(dx, dy, dz);
                                            if (b != null && b.getState() instanceof org.bukkit.block.CommandBlock) {
                                                tntOrigins.put(id, "command");
                                                tntOriginTimestamps.put(id, System.currentTimeMillis());
                                                return;
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }

    public void stop() {
        if (cleanupTask != null) {
            SchedulerHelper.cancelTask(cleanupTask);
            cleanupTask = null;
        }
        explosionCounts.clear();
        tntOrigins.clear();
        tntOriginTimestamps.clear();
        freezeRegions.clear();
    }

    

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntityType() != EntityType.PRIMED_TNT) return;

        Location loc = event.getLocation();
        if (loc == null || loc.getWorld() == null) return;

        boolean playerNearby = false;
        double radius = (double) playerProximityRadius;
        try {
            try {
                java.util.Collection<Entity> found = loc.getWorld().getNearbyEntities(loc, radius, radius, radius, e -> e instanceof Player);
                if (found != null && !found.isEmpty()) playerNearby = true;
            } catch (Throwable inner) {
                double radiusSq = radius * radius;
                for (Player p : loc.getWorld().getPlayers()) {
                    if (p != null && p.getLocation().distanceSquared(loc) <= radiusSq) {
                        playerNearby = true;
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}

        String tntOrigin = null;
        try {
            if (event.getEntity() != null) {
                java.util.UUID id = event.getEntity().getUniqueId();
                tntOrigin = tntOrigins.remove(id);
                try { tntOriginTimestamps.remove(id); } catch (Throwable ignored) {}
                if ("player".equals(tntOrigin)) {
                    playerNearby = true;
                }
            }
        } catch (Throwable ignored) {}
        
        try {
            if (tntOrigin == null && event.getEntity() != null) {
                java.util.UUID id2 = event.getEntity().getUniqueId();
                RecentActionTracker rt = RecentActionTracker.getInstance();
                if (rt != null && rt.wasEntityRecentlyPlayerSpawned(id2)) {
                    tntOrigin = "player";
                    playerNearby = true;
                }
            }
        } catch (Throwable ignored) {}
            try {
                double mspt = (msptSupplier == null) ? ScaleUtils.getSmoothedMspt() : msptSupplier.getAsDouble();
                double scale = ScaleUtils.continuousScaleFromMspt(mspt);
                double smoothed = ScaleUtils.getSmoothedMspt();
                boolean aggressive = (smoothed >= 45.0) || (scale <= FREEZE_SCALE_THRESHOLD);
                if (!"player".equals(tntOrigin)) {
                    if (aggressive) {
                        try { event.setYield(0.0f); } catch (Throwable ignored) {}
                        try { event.blockList().clear(); } catch (Throwable ignored) {}

                        try {
                            FreezeRegion fr = new FreezeRegion(loc, FREEZE_RADIUS, System.currentTimeMillis() + FREEZE_DURATION_MS);
                            freezeRegions.add(fr);
                            PowerhouseLogger.warn("Applied Physics Freezer at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + " (scale=" + String.format("%.2f", scale) + ", smoothed=" + String.format("%.1f", smoothed) + ")");
                        } catch (Throwable ignored) {}
                    } else if (smoothed >= 35.0) {
                        try {
                            List<Block> blocks = event.blockList();
                            int orig = blocks.size();
                            if (orig > 0) {
                                double frac = 1.0 - Math.max(0.0, Math.min(1.0, (smoothed - 35.0) / 10.0));
                                int keep = Math.max(0, (int) Math.round(orig * frac));
                                if (keep < orig) {
                                    try {
                                        blocks.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(loc)));
                                        for (int i = orig - 1; i >= keep; i--) {
                                            try { blocks.remove(i); } catch (Throwable ignored) {}
                                        }
                                        try { event.setYield(0.0f); } catch (Throwable ignored) {}
                                        FreezeRegion fr = new FreezeRegion(loc, FREEZE_RADIUS, System.currentTimeMillis() + FREEZE_DURATION_MS);
                                        freezeRegions.add(fr);
                                        PowerhouseLogger.warn("Shrunk explosion blocks from " + orig + "->" + keep + " at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + " (smoothed=" + String.format("%.1f", smoothed) + ")");
                                    } catch (Throwable ignored) {}
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        BlockKey key = BlockKey.from(loc);
        if (key == null) return;

        double effectiveThreshold = getEffectiveThreshold();

        int current = explosionCounts.merge(key, 1, Integer::sum);

            if (current > (int) effectiveThreshold) {
            event.setCancelled(true);
            if (event.getEntity() != null) event.getEntity().remove();
            PowerhouseLogger.warn("Cancelled TNT explosion at " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + " due to spam (" + current + ")");
        }

    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        Block b = event.getBlock();

        if (b == null || b.getType() == org.bukkit.Material.AIR) {
            if (event.getBlock() != null && event.getBlock().getWorld().getEnvironment() != org.bukkit.World.Environment.NORMAL) {
                event.setCancelled(true);
                PowerhouseLogger.warn("Blocked bed explosion (fallback) at " + event.getBlock().getX() + "," + event.getBlock().getY() + "," + event.getBlock().getZ());
                return;
            }
        }

        if (b != null && (b.getType().name().contains("BED") || b.getType() == Material.RESPAWN_ANCHOR)) {
            event.setCancelled(true);
            PowerhouseLogger.warn("Blocked bed explosion at " + b.getX() + "," + b.getY() + "," + b.getZ());
        }
    }

    private void cleanupExpired() {
        try { explosionCounts.clear(); } catch (Throwable ignored) {}
        try {
            long now = System.currentTimeMillis();
            long cutoff = Math.max(10_000L, retentionWindowMs * 2);
            tntOriginTimestamps.entrySet().removeIf(e -> now - e.getValue() > cutoff);
            
            try {
                for (java.util.UUID id : tntOrigins.keySet()) {
                    if (!tntOriginTimestamps.containsKey(id)) tntOrigins.remove(id);
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        try {
            long now = System.currentTimeMillis();
            freezeRegions.removeIf(fr -> fr.expiryMs <= now);
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityDamage(EntityDamageEvent ev) {
        if (ev == null) return;
        try {
            EntityDamageEvent.DamageCause cause = ev.getCause();
            if (cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION && cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) return;
            org.bukkit.entity.Entity entity = ev.getEntity();
            if (entity == null) return;
            if (entity instanceof org.bukkit.entity.Player) return;

            org.bukkit.entity.EntityType t = entity.getType();
            boolean isBackground = (t == EntityType.ARMOR_STAND || t == EntityType.ITEM_FRAME || t == EntityType.DROPPED_ITEM || t == EntityType.EXPERIENCE_ORB || t == EntityType.PAINTING);
            if (!isBackground) return;

            org.bukkit.Location loc = entity.getLocation();
            if (loc == null) return;
            long now = System.currentTimeMillis();
            for (FreezeRegion fr : freezeRegions) {
                if (fr.expiryMs <= now) continue;
                if (!fr.worldName.equals(loc.getWorld().getName())) continue;
                double dx = fr.x - loc.getX();
                double dy = fr.y - loc.getY();
                double dz = fr.z - loc.getZ();
                double dsq = dx*dx + dy*dy + dz*dz;
                if (dsq <= fr.radiusSq) {
                    ev.setCancelled(true);
                    return;
                }
            }
        } catch (Throwable ignored) {}
    }

    private static final class FreezeRegion {
        final String worldName;
        final double x,y,z;
        final double radiusSq;
        final long expiryMs;

        FreezeRegion(Location loc, double radius, long expiryMs) {
            this.worldName = (loc.getWorld() == null) ? "" : loc.getWorld().getName();
            this.x = loc.getX(); this.y = loc.getY(); this.z = loc.getZ();
            this.radiusSq = radius * radius;
            this.expiryMs = expiryMs;
        }
    }

    private double getEffectiveThreshold() {
        if (msptSupplier == null) return threshold;
        try {
            double mspt = msptSupplier.getAsDouble();
            double s = ScaleUtils.continuousScaleFromMspt(mspt);
            if (s > ScaleUtils.continuousScaleFromMspt(25.0)) return 50.0;
            double val = ScaleUtils.lerp(5.0, (double) threshold, s);
            if (val < 1.0) return 0.0;
            return val;
        } catch (Throwable t) {
            return threshold;
        }
    }
}


