package kaiakk.powerhouse.sync;

import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import kaiakk.multimedia.classes.SchedulerHelper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import kaiakk.powerhouse.async.BlockKey;

public class HopperLimiter implements Listener, Scalable {
    private final Plugin plugin;
    private volatile int maxTransfersPerSecond;
    private volatile int baseMaxTransfers;
    private static final int MAX_MAP_ENTRIES = 5000;
    private final java.util.Map<Long, Integer> hopperTransferCount = createLRUMap(MAX_MAP_ENTRIES);
    private final java.util.Map<Long, Long> proxyCooldowns = createLRUMap(MAX_MAP_ENTRIES);
    private final Map<UUID, Integer> worldIndexMap = new ConcurrentHashMap<>();
    private volatile BukkitTask cleanupTask = null;

    public HopperLimiter(Plugin plugin, int maxTransfersPerSecond) {
        this.plugin = plugin;
        this.maxTransfersPerSecond = maxTransfersPerSecond;
        this.baseMaxTransfers = Math.max(1, maxTransfersPerSecond);
    }

    public void setMaxTransfersPerSecond(int v) {
        if (v <= 0) return;
        this.maxTransfersPerSecond = v;
    }

    @Override
    public void setScale(double scale) {
        double m = ScaleUtils.multiplierFromScale(scale, 0.5, 2.0);
        int transfers = Math.max(1, (int) Math.round((double) baseMaxTransfers * m));
        setMaxTransfersPerSecond(transfers);
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        cleanupTask = SchedulerHelper.runTimerSeconds(plugin, this::resetCounters, 1.0, 1.0);
        PowerhouseLogger.info("Hopper limiter enabled (max " + maxTransfersPerSecond + " transfers/sec/hopper)");
    }

    private void resetCounters() {
        try { hopperTransferCount.clear(); } catch (Throwable ignored) {}
        if (!proxyCooldowns.isEmpty()) {
            long now = System.currentTimeMillis();
            try {
                synchronized (proxyCooldowns) {
                    java.util.Iterator<java.util.Map.Entry<Long, Long>> it = proxyCooldowns.entrySet().iterator();
                    while (it.hasNext()) {
                        try {
                            java.util.Map.Entry<Long, Long> e = it.next();
                            if (e == null) continue;
                            Long v = e.getValue();
                            if (v == null) continue;
                            if (v < now) it.remove();
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        }
    }

    public void stop() {
        if (cleanupTask != null) {
            SchedulerHelper.cancelTask(cleanupTask);
            cleanupTask = null;
        }
        HandlerList.unregisterAll(this);
        try { hopperTransferCount.clear(); } catch (Throwable ignored) {}
        try { proxyCooldowns.clear(); } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(org.bukkit.event.block.BlockPhysicsEvent event) {
        try {
            Material type = event.getChangedType();
            if (type == Material.AIR || !type.isOccluding()) {
                org.bukkit.block.Block below = event.getBlock().getRelative(BlockFace.DOWN);
                if (below.getType() == Material.HOPPER) {
                    proxyCooldowns.remove(packLocation(below.getLocation()));
                }
            }
        } catch (Exception ignored) {} 
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHopperTransfer(InventoryMoveItemEvent event) {
        Inventory sourceInv = event.getSource();
        if (sourceInv.getType() != org.bukkit.event.inventory.InventoryType.HOPPER) return;

        try {
            org.bukkit.Location loc = sourceInv.getLocation();
            if (loc == null) return;
            
            long key = packLocation(loc);
            long now = System.currentTimeMillis();

            Long wakeMs = proxyCooldowns.get(key);
            if (wakeMs != null) {
                if (wakeMs > now) {
                    event.setCancelled(true);
                    return;
                }
                try { proxyCooldowns.remove(key); } catch (Throwable ignored) {}
            }

            org.bukkit.block.Block above = loc.getBlock().getRelative(BlockFace.UP);
            if (above.getType().isOccluding() && !(above.getState() instanceof org.bukkit.inventory.InventoryHolder)) {
                if (isInventoryEmpty(sourceInv)) {
                    event.setCancelled(true);
                    try { proxyCooldowns.put(key, now + 10000L); } catch (Throwable ignored) {}
                    return;
                }
            }
            int effectiveLimit = maxTransfersPerSecond;
            try {
                RecentActionTracker rt = RecentActionTracker.getInstance();
                BlockKey bk = BlockKey.from(loc);
                if (rt != null && bk != null) {
                    if (rt.wasRecentPlayerPlacement(bk)) {
                        effectiveLimit = Math.max(effectiveLimit, baseMaxTransfers * 4);
                    } else if (rt.wasRecentDispense(bk)) {
                        effectiveLimit = Math.max(effectiveLimit, baseMaxTransfers * 2);
                    }
                }
            } catch (Throwable ignored) {}

            int currentCount = 0;
            try { currentCount = hopperTransferCount.merge(key, 1, Integer::sum); } catch (Throwable ignored) {}
            if (currentCount > effectiveLimit) {
                event.setCancelled(true);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error processing hopper transfer: " + e.getMessage());
        }
    }

    private boolean isInventoryEmpty(Inventory inv) {
        int size = inv.getSize();
        for (int i = 0; i < size; i++) {
            try {
                ItemStack is = inv.getItem(i);
                if (is != null && is.getType() != Material.AIR) return false;
            } catch (Throwable ignored) {}
        }
        return true;
    }

    private long packLocation(org.bukkit.Location loc) {
        int worldId = worldIndexMap.computeIfAbsent(loc.getWorld().getUID(), k -> worldIndexMap.size());
        
        long x = (long) loc.getBlockX() & 0x3FFFFFFL;
        long z = (long) loc.getBlockZ() & 0x3FFFFFFL;
        long y = (long) loc.getBlockY() & 0xFFFFL;
        long w = (long) worldId & 0xFFL;

        return (w << 56) | (y << 48) | (x << 24) | z;
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


