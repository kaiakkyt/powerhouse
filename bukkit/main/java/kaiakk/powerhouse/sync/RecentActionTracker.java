package kaiakk.powerhouse.sync;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.plugin.Plugin;
import kaiakk.multimedia.classes.SchedulerHelper;
import org.bukkit.event.HandlerList;
import org.bukkit.scheduler.BukkitTask;
import kaiakk.powerhouse.async.BlockKey;
import java.util.Map;
import java.util.UUID;

public class RecentActionTracker implements Listener {
    
    private static RecentActionTracker INSTANCE = null;
    private final Plugin plugin;
    private volatile BukkitTask cleanupTask = null;
    private final Map<BlockKey, Long> recentPlayerPlacements = createLRUMap(MAX_MAP_ENTRIES);
    private final Map<BlockKey, Long> recentDispenses = createLRUMap(MAX_MAP_ENTRIES);
    private final Map<UUID, Long> recentPlayerSpawns = createLRUMap(MAX_MAP_ENTRIES);
    private volatile long retentionWindowMs = 30_000L;
    private static final int MAX_MAP_ENTRIES = 5000;

    private RecentActionTracker(Plugin plugin) {
        this.plugin = plugin;
        try { startCleanupTask(); } catch (Throwable ignored) {}
    }
    public static synchronized void init(Plugin plugin) {
        if (INSTANCE == null) {
            INSTANCE = new RecentActionTracker(plugin);
            try { Bukkit.getPluginManager().registerEvents(INSTANCE, plugin); } catch (Throwable ignored) {}
        }
    }

    public static RecentActionTracker getInstance() { return INSTANCE; }

    private static <K,V> Map<K,V> createLRUMap(final int maxEntries) {
        return java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<K,V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(java.util.Map.Entry<K,V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    public boolean wasRecentPlayerPlacement(BlockKey key, long windowMs) {
        if (key == null) return false;
        Long t = recentPlayerPlacements.get(key);
        return t != null && (System.currentTimeMillis() - t) < windowMs;
    }

    public boolean wasRecentPlayerPlacement(BlockKey key) {
        return wasRecentPlayerPlacement(key, retentionWindowMs);
    }

    public boolean wasRecentDispense(BlockKey key, long windowMs) {
        if (key == null) return false;
        Long t = recentDispenses.get(key);
        return t != null && (System.currentTimeMillis() - t) < windowMs;
    }

    public boolean wasRecentDispense(BlockKey key) {
        return wasRecentDispense(key, retentionWindowMs);
    }

    public boolean wasEntityRecentlyPlayerSpawned(UUID id, long windowMs) {
        if (id == null) return false;
        Long t = recentPlayerSpawns.get(id);
        return t != null && (System.currentTimeMillis() - t) < windowMs;
    }

    public boolean wasEntityRecentlyPlayerSpawned(UUID id) {
        return wasEntityRecentlyPlayerSpawned(id, retentionWindowMs);
    }

    public void setRetentionWindowSeconds(int seconds) {
        if (seconds <= 0) return;
        this.retentionWindowMs = (long) seconds * 1000L;
    }

    public long getRetentionWindowMs() { return this.retentionWindowMs; }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent ev) {
        try {
            if (ev == null || ev.getBlock() == null) return;
            if (ev.getBlock().getType() == org.bukkit.Material.TNT) {
                BlockKey bk = BlockKey.from(ev.getBlock().getLocation());
                if (bk != null) {
                    if (recentPlayerPlacements.size() < MAX_MAP_ENTRIES) recentPlayerPlacements.put(bk, System.currentTimeMillis());
                }
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDispense(BlockDispenseEvent ev) {
        try {
            if (ev == null || ev.getBlock() == null || ev.getItem() == null) return;
            if (ev.getItem().getType() == org.bukkit.Material.TNT) {
                BlockKey bk = BlockKey.from(ev.getBlock().getLocation());
                if (bk != null) {
                    if (recentDispenses.size() < MAX_MAP_ENTRIES) recentDispenses.put(bk, System.currentTimeMillis());
                }
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDrop(PlayerDropItemEvent ev) {
        try {
            if (ev == null || ev.getItemDrop() == null) return;
            org.bukkit.entity.Item it = ev.getItemDrop();
            if (it == null) return;
            if (recentPlayerSpawns.size() < MAX_MAP_ENTRIES) recentPlayerSpawns.put(it.getUniqueId(), System.currentTimeMillis());
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitySpawn(EntitySpawnEvent ev) {
        if (ev == null || ev.getEntity() == null) return;
        try {
            Entity e = ev.getEntity();
            if (e instanceof org.bukkit.entity.TNTPrimed) {
                org.bukkit.entity.TNTPrimed tnt = (org.bukkit.entity.TNTPrimed) e;
                try {
                    org.bukkit.entity.Entity src = tnt.getSource();
                    if (src instanceof Player) {
                        markAsPlayerSpawned(tnt.getUniqueId(), System.currentTimeMillis());
                        return;
                    }
                } catch (Throwable ignored) {}

                try {
                    Location loc = e.getLocation();
                    if (loc != null && loc.getWorld() != null) {
                        for (Entity near : loc.getWorld().getNearbyEntities(loc, 3.0, 3.0, 3.0)) {
                            try {
                                if (near instanceof Player) {
                                    markAsPlayerSpawned(e.getUniqueId(), System.currentTimeMillis());
                                    return;
                                }
                            } catch (Throwable ignored) {}
                        }
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void markAsPlayerSpawned(UUID id, long time) {
        if (id == null) return;
        if (recentPlayerSpawns.size() < MAX_MAP_ENTRIES) recentPlayerSpawns.put(id, time);
    }

    public void startCleanupTask() {
        try {
            cleanupTask = SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
                public void run() {
                    long now = System.currentTimeMillis();
                    long cutoff = Math.max(10_000L, retentionWindowMs * 2);
                    try { recentPlayerPlacements.entrySet().removeIf(e -> now - e.getValue() > cutoff); } catch (Throwable ignored) {}
                    try { recentDispenses.entrySet().removeIf(e -> now - e.getValue() > cutoff); } catch (Throwable ignored) {}
                    try { recentPlayerSpawns.entrySet().removeIf(e -> now - e.getValue() > cutoff); } catch (Throwable ignored) {}
                }
            }, 30.0, 30.0);
        } catch (Throwable ignored) {}
    }

    public static synchronized void shutdown() {
        if (INSTANCE == null) return;
        try {
            try { if (INSTANCE.cleanupTask != null) SchedulerHelper.cancelTask(INSTANCE.cleanupTask); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        try { HandlerList.unregisterAll(INSTANCE); } catch (Throwable ignored) {}
        try { INSTANCE.recentPlayerPlacements.clear(); } catch (Throwable ignored) {}
        try { INSTANCE.recentDispenses.clear(); } catch (Throwable ignored) {}
        try { INSTANCE.recentPlayerSpawns.clear(); } catch (Throwable ignored) {}
        INSTANCE = null;
    }
}


