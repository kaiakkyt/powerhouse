package kaiakk.powerhouse.external;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import kaiakk.powerhouse.sync.AllOptimizations;

import java.math.BigDecimal;
import java.math.RoundingMode;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Public read-only API for Powerhouse.
 * Safe to use from other plugins.
 */
public final class PowerhouseAPI {

    private PowerhouseAPI() {} // no instantiation

    /**
     * Internal accessor.
     * Returns null if Powerhouse is not loaded or not ready.
     */
    private static AllOptimizations instance() {
        return AllOptimizations.getInstance();
    }

    /**
     * Get the current average server MSPT (Milliseconds Per Tick).
     *
     * @return MSPT, or -1 if unavailable
     */
    public static double getAverageMspt() {
        AllOptimizations inst = instance();
        return inst != null ? inst.getAverageMspt() : -1D;
    }

    /**
     * Get total entity removals performed due to mob-cramming mitigation.
     *
     * @return number of cramming removals, or 0 if unavailable
     */
    public static long getCrammingRemovals() {
        AllOptimizations inst = instance();
        return inst != null ? inst.getCrammingRemovals() : 0L;
    }

    /**
     * Get total item removals performed by cleanup systems.
     *
     * @return number of item removals, or 0 if unavailable
     */
    public static long getItemRemovals() {
        AllOptimizations inst = instance();
        return inst != null ? inst.getItemRemovals() : 0L;
    }

    /**
     * Get the current average server MSPT rounded to a given number of decimal places.
     *
     * @param decimals number of decimals to keep (>= 0)
     * @return rounded MSPT, or -1 if unavailable
     */
    public static double getAverageMsptRounded(int decimals) {
        if (decimals < 0) throw new IllegalArgumentException("decimals must be >= 0");
        AllOptimizations inst = instance();
        if (inst == null) return -1D;
        double val = inst.getAverageMspt();
        try {
            BigDecimal bd = new BigDecimal(Double.toString(val));
            bd = bd.setScale(decimals, RoundingMode.HALF_UP);
            return bd.doubleValue();
        } catch (Throwable ignored) {
            return val;
        }
    }

    /**
     * Check if Powerhouse is currently culling redstone updates
     * at a specific location.
     *
     * @param location Location to check
     * @return true if culled, false otherwise
     */
    public static boolean isLocationCulled(Location location) {
        if (location == null) return false;

        AllOptimizations inst = instance();
        if (inst == null) return false;

        return inst.isLocationCulled(location);
    }

    /**
     * Convenience overload: check culling by world name and coordinates.
     *
     * @param worldName world name
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @return true if culled, false otherwise
     */
    public static boolean isLocationCulled(String worldName, int x, int y, int z) {
        if (worldName == null) return false;
        World w = Bukkit.getWorld(worldName);
        if (w == null) return false;
        return isLocationCulled(new Location(w, x, y, z));
    }

    /**
     * Get a snapshot of current global optimization statistics.
     * This map is immutable and safe to read.
     *
     * @return statistics map, or empty map if unavailable
     */
    public static Map<String, Object> getStatisticsSnapshot() {
        AllOptimizations inst = instance();
        if (inst == null) return Collections.emptyMap();

        Map<String, Object> stats = inst.getStatistics();
        return stats != null ? Collections.unmodifiableMap(stats) : Collections.emptyMap();
    }

    /**
     * Get a snapshot of debug users currently enabled for per-user debug.
     *
     * @return set of debug user names, or empty set if unavailable
     */
    public static Set<String> getDebugUsersSnapshot() {
        AllOptimizations inst = instance();
        return inst != null ? inst.getDebugUsersSnapshot() : Collections.emptySet();
    }

    /**
     * Check whether global debug mode is enabled.
     *
     * @return true if debug enabled, false otherwise
     */
    public static boolean isDebugEnabled() {
        AllOptimizations inst = instance();
        return inst != null && inst.isDebugEnabled();
    }

    /**
     * Who currently owns the debug session, if any.
     *
     * @return owner name or null
     */
    public static String getDebugOwner() {
        AllOptimizations inst = instance();
        return inst != null ? inst.getDebugOwner() : null;
    }

    /**
     * Check whether debug is enabled for a specific user.
     *
     * @param name player name
     * @return true if enabled for that user
     */
    public static boolean isDebugEnabledForUser(String name) {
        if (name == null) return false;
        AllOptimizations inst = instance();
        return inst != null && inst.isDebugEnabledForUser(name);
    }

    /**
     * Check whether an entity's AI is currently disabled
     * by Powerhouse optimization systems.
     *
     * @param entityId Entity UUID
     * @return true if AI is disabled, false otherwise
     */
    public static boolean isEntityAiDisabled(UUID entityId) {
        if (entityId == null) return false;

        AllOptimizations inst = instance();
        if (inst == null) return false;

        return inst.isEntityAiDisabled(entityId);
    }

    /**
     * Check if a location is currently in the deferred-throttle (nudge) queue.
     * This is a best-effort check that will try to call into the async Calculations
     * helper if available. If the underlying helper does not expose a matching
     * method this will conservatively return false.
     *
     * @param location location to check
     * @return true if throttled/deferred, false otherwise
     */
    public static boolean isLocationThrottled(Location location) {
        if (location == null) return false;
        try {
            Class<?> cls = Class.forName("kaiakk.powerhouse.async.Calculations");
            String[] candidates = new String[]{"isLocationThrottled", "isThrottled", "isLocationDeferred"};
            for (String name : candidates) {
                try {
                    java.lang.reflect.Method m = cls.getMethod(name, Location.class);
                    Object res = m.invoke(null, location);
                    if (res instanceof Boolean) return (Boolean) res;
                } catch (NoSuchMethodException ignored) {}
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Get plugin version from the loaded plugin description, or null if unavailable.
     */
    public static String getVersion() {
        try {
            Plugin p = Bukkit.getPluginManager().getPlugin("Powerhouse");
            if (p != null && p.getDescription() != null) return p.getDescription().getVersion();
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Enqueue a synchronous task to run relative to an entity's scheduler.
     * Returns true if the task was successfully queued (best-effort).
     */
    public static boolean enqueueEntityTask(Entity ent, Runnable task) {
        if (ent == null || task == null) return false;
        AllOptimizations inst = instance();
        if (inst == null) return false;
        try {
            inst.enqueueEntityTask(ent, task);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Mark an entity as dead (best-effort).
     */
    public static void markEntityDead(Entity ent) {
        if (ent == null) return;
        AllOptimizations inst = instance();
        if (inst == null) return;
        try { inst.markEntityDead(ent); } catch (Throwable ignored) {}
    }

    /**
     * Check if Powerhouse is currently active.
     *
     * @return true if loaded and running
     */
    public static boolean isPowerhouseActive() {
        return instance() != null;
    }
}
