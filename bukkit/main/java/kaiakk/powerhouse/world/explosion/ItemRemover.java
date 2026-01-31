package kaiakk.powerhouse.world.explosion;

import kaiakk.multimedia.classes.SchedulerHelper;
import kaiakk.powerhouse.data.BlockKey;
import kaiakk.powerhouse.data.RecentActionTracker;
import kaiakk.powerhouse.world.AllOptimizations;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class ItemRemover implements Listener {
    private final Plugin plugin;
    private volatile BukkitTask task = null;
    private volatile BukkitTask restoreTask = null;
    private final java.util.concurrent.ConcurrentHashMap<java.util.UUID, org.bukkit.entity.Item> hiddenItems = new java.util.concurrent.ConcurrentHashMap<>();
    private static final double PLAYER_CLEAR_RADIUS = 96.0;
    private static final double PLAYER_CLEAR_RADIUS_SQ = PLAYER_CLEAR_RADIUS * PLAYER_CLEAR_RADIUS;
    private static final int PURGE_INTERVAL_SECONDS = 300;

    private volatile double hideDistance = 16.0;
    private volatile double hideDistanceSq = hideDistance * hideDistance;
    private volatile double hideVelocityThreshold = 5.0;

    public ItemRemover(Plugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (task != null) return;
        reloadSettings();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = SchedulerHelper.runTimerSeconds(plugin, this::runPurgeTask, PURGE_INTERVAL_SECONDS, PURGE_INTERVAL_SECONDS);
        restoreTask = SchedulerHelper.runTimerSeconds(plugin, this::runRestoreTask, 5, 5);
    }

    public void stop() {
        if (task != null) {
            SchedulerHelper.cancelTask(task);
            task = null;
        }
        if (restoreTask != null) {
            SchedulerHelper.cancelTask(restoreTask);
            restoreTask = null;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        try {
            Item it = event.getEntity();
            if (it == null) return;
            ItemStack stack = null;
            try { stack = it.getItemStack(); } catch (Throwable ignored) {}
            if (stack == null) return;

            if (isProtected(stack)) return;

            World w = it.getWorld();
                            try {
                                AllOptimizations ao = AllOptimizations.getInstance();
                                double vel = ao != null ? ao.getMsptVelocity() : 0.0;
                                boolean climbing = vel > hideVelocityThreshold;
                                try {
                                    for (Player p : w.getPlayers()) {
                                        try {
                                            double d2 = p.getLocation().distanceSquared(it.getLocation());
                                                            if (climbing && d2 > hideDistanceSq) {
                                                                safeHideEntity(p, it);
                                                            } else {
                                                                safeShowEntity(p, it);
                                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                } catch (Throwable ignored) {}
                            } catch (Throwable ignored) {}

            boolean nearby = false;
            for (Player p : w.getPlayers()) {
                try {
                    if (p.getLocation().distanceSquared(it.getLocation()) <= PLAYER_CLEAR_RADIUS_SQ) { nearby = true; break; }
                } catch (Throwable ignored) {}
            }
            if (!nearby) {
                try {
                    RecentActionTracker rt = RecentActionTracker.getInstance();
                    boolean recentPlayer = (rt != null && rt.wasEntityRecentlyPlayerSpawned(it.getUniqueId()));
                    boolean recentDispense = false;
                    try {
                        BlockKey bk = BlockKey.from(it.getLocation());
                        if (bk != null && rt != null) recentDispense = rt.wasRecentDispense(bk);
                    } catch (Throwable ignored) {}

                    if (!recentPlayer && !recentDispense) {
                        AllOptimizations inst = AllOptimizations.getInstance();
                        if (inst != null) inst.markEntityDead(it);
                        kaiakk.powerhouse.helpers.internal.DebugLog.debug("ItemRemover: immediate removed " + stack.getType() + " @ " + it.getLocation());
                    } else {
                        kaiakk.powerhouse.helpers.internal.DebugLog.debug("ItemRemover: skipped recent player/dispense item " + stack.getType() + " @ " + it.getLocation());
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    private void runPurgeTask() {
        String tname = Thread.currentThread().getName();
        if (tname != null && (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin) || tname.contains("Region") || tname.contains("Threaded"))) {
            try { SchedulerHelper.run(plugin, this::runPurgeTask); } catch (Throwable ignored) {}
            return;
        }

        int itemRemovalsLocal = 0;
        try {
            for (World world : Bukkit.getWorlds()) {
                List<Player> players = world.getPlayers();
                List<double[]> playerLocs = new ArrayList<>();
                for (Player p : players) {
                    try { playerLocs.add(new double[] { p.getLocation().getX(), p.getLocation().getY(), p.getLocation().getZ() }); } catch (Throwable ignored) {}
                }

                for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                    try {
                        for (org.bukkit.entity.Entity e : chunk.getEntities()) {
                            if (!(e instanceof Item)) continue;
                            Item it = (Item) e;
                            if (!it.isValid() || it.isDead()) continue;
                            ItemStack stack = null;
                            try { stack = it.getItemStack(); } catch (Throwable ignored) {}
                            if (stack == null) continue;
                            if (isProtected(stack)) continue;

                            try {
                                AllOptimizations ao = AllOptimizations.getInstance();
                                double vel = ao != null ? ao.getMsptVelocity() : 0.0;
                                boolean climbing = vel > hideVelocityThreshold;
                                try {
                                    for (Player p : players) {
                                        try {
                                            double dx = it.getLocation().getX() - p.getLocation().getX();
                                            double dy = it.getLocation().getY() - p.getLocation().getY();
                                            double dz = it.getLocation().getZ() - p.getLocation().getZ();
                                            double d2 = dx*dx + dy*dy + dz*dz;
                                            if (climbing && d2 > hideDistanceSq) {
                                                safeHideEntity(p, it);
                                            } else {
                                                safeShowEntity(p, it);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                } catch (Throwable ignored) {}
                            } catch (Throwable ignored) {}

                            boolean nearby = false;
                            for (double[] pl : playerLocs) {
                                double dx = it.getLocation().getX() - pl[0];
                                double dy = it.getLocation().getY() - pl[1];
                                double dz = it.getLocation().getZ() - pl[2];
                                double d2 = dx*dx + dy*dy + dz*dz;
                                if (d2 <= PLAYER_CLEAR_RADIUS_SQ) { nearby = true; break; }
                            }
                            if (!nearby) {
                                try {
                                    RecentActionTracker rt = RecentActionTracker.getInstance();
                                    boolean recentPlayer = (rt != null && rt.wasEntityRecentlyPlayerSpawned(it.getUniqueId()));
                                    boolean recentDispense = false;
                                    try { BlockKey bk = BlockKey.from(it.getLocation()); if (bk != null && rt != null) recentDispense = rt.wasRecentDispense(bk); } catch (Throwable ignored) {}
                                    if (!recentPlayer && !recentDispense) {
                                        AllOptimizations.getInstance().markEntityDead(it); itemRemovalsLocal++; kaiakk.powerhouse.helpers.internal.DebugLog.debug("ItemRemover: purge removed " + stack.getType() + " @ " + it.getLocation());
                                    } else {
                                        kaiakk.powerhouse.helpers.internal.DebugLog.debug("ItemRemover: purge skipped recent item " + stack.getType() + " @ " + it.getLocation());
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            if (itemRemovalsLocal > 0) {
                AllOptimizations ao = AllOptimizations.getInstance();
                if (ao != null) ao.addItemRemovals(itemRemovalsLocal);
            }
        } catch (Throwable ignored) {}
    }

    private boolean isProtected(ItemStack stack) {
        if (stack == null) return true;
        try {
            if (stack.hasItemMeta()) {
                try {
                    org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
                    if (meta != null) {
                        if (meta.hasDisplayName() || meta.hasLore() || !meta.getEnchants().isEmpty()) return true;
                    }
                } catch (Throwable ignored) {}
            }

            String name = stack.getType().name();
            String[] toolOrArmor = new String[]{"HELMET","CHESTPLATE","LEGGINGS","BOOTS","SWORD","AXE","PICKAXE","SHOVEL","HOE","ELYTRA","BOW","CROSSBOW","TRIDENT","SHIELD"};
            for (String k : toolOrArmor) if (name.contains(k)) return true;

            String[] rare = new String[]{"DIAMOND","NETHER_STAR","EMERALD","ENCHANTED_BOOK","TOTEM_OF_UNDYING","HEART_OF_THE_SEA","NETHERITE","BEACON","ELYTRA"};
            for (String r : rare) if (name.contains(r)) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private void safeHideEntity(Player p, Item it) {
        if (p == null || it == null) return;
        try {
            try {
                java.lang.reflect.Method m = p.getClass().getMethod("hideEntity", org.bukkit.plugin.Plugin.class, org.bukkit.entity.Entity.class);
                m.invoke(p, plugin, it);
                return;
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}

        try {
            try {
                java.lang.reflect.Method m = p.getClass().getMethod("hideEntity", org.bukkit.entity.Entity.class);
                m.invoke(p, it);
                try { hiddenItems.put(it.getUniqueId(), it); } catch (Throwable ignored) {}
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
    }

    private void safeShowEntity(Player p, Item it) {
        if (p == null || it == null) return;
        try {
            try {
                java.lang.reflect.Method m = p.getClass().getMethod("showEntity", org.bukkit.plugin.Plugin.class, org.bukkit.entity.Entity.class);
                m.invoke(p, plugin, it);
                try { hiddenItems.remove(it.getUniqueId()); } catch (Throwable ignored) {}
                return;
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}

        try {
            try {
                java.lang.reflect.Method m = p.getClass().getMethod("showEntity", org.bukkit.entity.Entity.class);
                m.invoke(p, it);
                try { hiddenItems.remove(it.getUniqueId()); } catch (Throwable ignored) {}
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
    }

    private void runRestoreTask() {
        try {
            AllOptimizations ao = AllOptimizations.getInstance();
            double vel = ao != null ? ao.getMsptVelocity() : 0.0;
            if (vel <= hideVelocityThreshold) {
                for (java.util.Map.Entry<java.util.UUID, org.bukkit.entity.Item> e : hiddenItems.entrySet()) {
                    try {
                        org.bukkit.entity.Item it = e.getValue();
                        if (it == null) { hiddenItems.remove(e.getKey()); continue; }
                        if (!it.isValid() || it.isDead()) { hiddenItems.remove(e.getKey()); continue; }
                        World w = it.getWorld();
                        if (w == null) { hiddenItems.remove(e.getKey()); continue; }
                        for (Player p : w.getPlayers()) {
                            try { safeShowEntity(p, it); } catch (Throwable ignored) {}
                        }
                        hiddenItems.remove(e.getKey());
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }

    public void reloadSettings() {
        try {
            double d = kaiakk.powerhouse.helpers.other.ConfigHelp.getDouble("item-hiding.distance", 16.0);
            double v = kaiakk.powerhouse.helpers.other.ConfigHelp.getDouble("item-hiding.velocity-threshold", 5.0);
            this.hideDistance = d;
            this.hideDistanceSq = d * d;
            this.hideVelocityThreshold = v;
        } catch (Throwable ignored) {}
    }

    public int getHiddenCount() {
        try { return hiddenItems.size(); } catch (Throwable ignored) { return 0; }
    }
}


