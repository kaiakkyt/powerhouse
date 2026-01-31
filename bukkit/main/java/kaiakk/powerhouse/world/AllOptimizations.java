package kaiakk.powerhouse.world;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import kaiakk.powerhouse.calculations.Calculations;
import kaiakk.powerhouse.data.RecentActionTracker;
import kaiakk.powerhouse.data.collectors.MetricCollector;
import kaiakk.powerhouse.helpers.logs.PowerhouseLogger;
import kaiakk.powerhouse.calculations.CalculationsSync;
import kaiakk.powerhouse.helpers.scaling.Scalable;
import kaiakk.powerhouse.helpers.scaling.ScaleUtils;
import kaiakk.powerhouse.world.entity.EntityPusher;
import kaiakk.powerhouse.world.limiters.HopperLimiter;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import kaiakk.multimedia.classes.*;

public class AllOptimizations implements Listener {
    
    private final JavaPlugin plugin;
    private final Map<World, Long> lastItemScan = new ConcurrentHashMap<>();
    private final Map<World, Long> lastRedstoneScan = new ConcurrentHashMap<>();
    
    private static final int ITEM_MERGE_INTERVAL_SECONDS = 3;
    private static final int REDSTONE_CHECK_INTERVAL_SECONDS = 1;
    private static final int CLEANUP_INTERVAL_SECONDS = 30;
    
    private MetricCollector metricCollector;
    private kaiakk.powerhouse.world.controllers.DistanceController distanceController;
    private kaiakk.powerhouse.calculations.DistanceCalculator distanceCalculator;
    private kaiakk.powerhouse.world.entity.EntityPusher entityPusher;
    private kaiakk.powerhouse.world.limiters.HopperLimiter hopperLimiter;
    private org.bukkit.scheduler.BukkitTask arrowTask = null;
    private kaiakk.powerhouse.helpers.scaling.DynamicScaler dynamicScaler = null;
    private java.util.function.Consumer<Double> scalerSubscriber = null;
    private final java.util.Map<Scalable, java.util.function.Consumer<Double>> scalableSubscribers = new java.util.concurrent.ConcurrentHashMap<Scalable, java.util.function.Consumer<Double>>();
    private double currentArrowInterval = 30.0;
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pendingSyncTasks = new java.util.concurrent.ConcurrentLinkedQueue<Runnable>();
    
    private final java.util.concurrent.ConcurrentHashMap<kaiakk.powerhouse.data.BlockKey, java.util.concurrent.atomic.AtomicInteger> redstoneListenerCounts = new java.util.concurrent.ConcurrentHashMap<>();
    private static AllOptimizations INSTANCE = null;
    private volatile boolean debugEnabled = false;
    private volatile String debugOwner = null;
    private final java.util.Set<String> debugUsers = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong crammingRemovals = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong itemRemovals = new java.util.concurrent.atomic.AtomicLong(0);
    
    public AllOptimizations(JavaPlugin plugin) {
        this.plugin = plugin;
        INSTANCE = this;
    }

    public static AllOptimizations getInstance() {
        return INSTANCE;
    }

    public void setDebugEnabled(boolean enabled) {
        setDebugEnabled(enabled, null);
    }

    public synchronized void setDebugEnabled(boolean enabled, String owner) {
        this.debugEnabled = enabled;
        try {
            if (enabled) {
                this.debugOwner = owner;
                PowerhouseLogger.info("Powerhouse debug enabled" + (owner != null ? " by " + owner : ""));
            } else {
                this.debugOwner = null;
                PowerhouseLogger.info("Powerhouse debug disabled");
            }
        } catch (Throwable ignored) {}
    }

    public String getDebugOwner() { return this.debugOwner; }

    public void enableDebugForUser(String name) {
        if (name == null) return;
        try { debugUsers.add(name); } catch (Throwable ignored) {}
    }

    public void disableDebugForUser(String name) {
        if (name == null) return;
        try { debugUsers.remove(name); } catch (Throwable ignored) {}
    }

    public boolean isDebugEnabledForUser(String name) {
        if (name == null) return false;
        try { return debugUsers.contains(name); } catch (Throwable ignored) { return false; }
    }

    public java.util.Set<String> getDebugUsersSnapshot() {
        try { return new java.util.HashSet<>(debugUsers); } catch (Throwable ignored) { return java.util.Collections.emptySet(); }
    }

    public boolean isDebugEnabled() { return this.debugEnabled; }

    public double getAverageMspt() {
        try {
            if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return -1.0;
            if (metricCollector != null) return metricCollector.getAverageMspt();
        } catch (Throwable ignored) {}
        return 0.0;
    }

    public double getActiveMspt() {
        try {
            if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return -1.0;
            if (metricCollector != null) return metricCollector.getActiveMspt();
        } catch (Throwable ignored) {}
        return -1.0;
    }

    public double getIntervalMspt() {
        try {
            if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return -1.0;
            if (metricCollector != null) return metricCollector.getIntervalMspt();
        } catch (Throwable ignored) {}
        return -1.0;
    }

    public String getMsptSource() {
        try {
            if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return "folia-disabled";
            if (metricCollector != null) return metricCollector.getLastSampleSource();
        } catch (Throwable ignored) {}
        return "unknown";
    }

    public double getMsptVelocity() {
        try {
            if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return 0.0;
            if (metricCollector != null) return metricCollector.getMsptVelocity();
        } catch (Throwable ignored) {}
        return 0.0;
    }

    public void addCrammingRemovals(long n) {
        if (n <= 0) return;
        try { crammingRemovals.addAndGet(n); } catch (Throwable ignored) {}
    }

    public void addItemRemovals(long n) {
        if (n <= 0) return;
        try { itemRemovals.addAndGet(n); } catch (Throwable ignored) {}
    }

    public long getCrammingRemovals() { try { return crammingRemovals.get(); } catch (Throwable ignored) { return 0L; } }
    public long getItemRemovals() { try { return itemRemovals.get(); } catch (Throwable ignored) { return 0L; } }

    public boolean isLocationCulled(Location loc) {
        try {
            if (loc == null) return false;
            return kaiakk.powerhouse.calculations.Calculations.isLocationCulled(loc);
        } catch (Throwable ignored) {}
        return false;
    }

    public boolean isEntityAiDisabled(java.util.UUID id) {
        try {
            if (entityPusher != null && id != null) return entityPusher.isAiDisabled(id);
        } catch (Throwable ignored) {}
        return false;
    }

    public void enqueueEntityTask(final org.bukkit.entity.Entity ent, final Runnable task) {
        if (ent == null || task == null) {
            return;
        }
        try {
            java.lang.reflect.Method getScheduler = ent.getClass().getMethod("getScheduler");
            Object scheduler = getScheduler.invoke(ent);
            if (scheduler != null) {
                for (java.lang.reflect.Method m : scheduler.getClass().getMethods()) {
                    if (!m.getName().equals("run")) continue;
                    Class<?>[] params = m.getParameterTypes();
                    try {
                        if (params.length >= 2 && org.bukkit.plugin.Plugin.class.isAssignableFrom(params[0]) && Runnable.class.isAssignableFrom(params[1])) {
                            m.invoke(scheduler, plugin, task, (Object) null);
                            return;
                        }
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        try {
            try {
                org.bukkit.Location loc = null;
                try { loc = ent.getLocation(); } catch (Throwable ignored) {}
                if (loc != null) {
                    runAtLocation(loc, task);
                    return;
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        try { pendingSyncTasks.add(task); } catch (Throwable ignored) {}
    }
    
    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        
        try { RecentActionTracker.init(plugin); RecentActionTracker.getInstance().startCleanupTask();
            try { kaiakk.powerhouse.world.limiters.BookLimiter.init(plugin); } catch (Throwable ignored) {}
            try { kaiakk.powerhouse.world.physics.ParticleCulling.init(plugin); } catch (Throwable ignored) {}
            try { kaiakk.powerhouse.world.physics.ProjectileManager.init(plugin); } catch (Throwable ignored) {}
            try { kaiakk.powerhouse.world.explosion.ExplosionListener.init(plugin); } catch (Throwable ignored) {}
            try { kaiakk.powerhouse.world.physics.PassivePhysicsManager.init(plugin); } catch (Throwable ignored) {}
            
            try { int secs = kaiakk.powerhouse.helpers.internal.ConfigHelp.getInt("recent-action.window-seconds", 30); RecentActionTracker.getInstance().setRetentionWindowSeconds(secs); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        
        PowerhouseLogger.info("Starting Powerhouse optimization systems...");
        
        for (World world : Bukkit.getWorlds()) {
            startItemMergingTask(world);
        }
        
        startRedstoneCullingTask();
        try {
            final int nudgeDelay = kaiakk.powerhouse.helpers.internal.ConfigHelp.getInt("redstone-culling.nudge-delay-ticks", 2);
            Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                @Override
                public void run() {
                    try {
                        final java.util.List<Location> toFix = kaiakk.powerhouse.calculations.Calculations.drainThrottledLocations();
                        if (toFix == null || toFix.isEmpty()) return;
                        Bukkit.getScheduler().runTaskLater(plugin, new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    for (Location loc : toFix) {
                                        try {
                                            Block b = loc.getBlock();
                                            if (b == null) continue;
                                            try {
                                                org.bukkit.block.data.BlockData data = b.getBlockData();
                                                b.setBlockData(data, true);
                                            } catch (Throwable ex) {
                                                try { b.getState().update(true, true); } catch (Throwable ignored) {}
                                            }

                                            try {
                                                for (BlockFace face : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN }) {
                                                    try {
                                                        Block nb = b.getRelative(face);
                                                        if (nb == null) continue;
                                                        try { nb.getState().update(true, true); } catch (Throwable ignored) {}
                                                    } catch (Throwable ignored) {}
                                                }
                                            } catch (Throwable ignored) {}
                                            try {
                                                for (BlockFace face : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
                                                    try {
                                                        Block nb = b.getRelative(face);
                                                        if (nb == null) continue;
                                                        Block nb2 = nb.getRelative(face);
                                                        if (nb2 == null) continue;
                                                        try { nb2.getState().update(true, true); } catch (Throwable ignored) {}
                                                    } catch (Throwable ignored) {}
                                                }
                                            } catch (Throwable ignored) {}
                                            try {
                                                try {
                                                    Block down2 = b.getRelative(BlockFace.DOWN, 2);
                                                    if (down2 != null) {
                                                        try { down2.getState().update(true, true); } catch (Throwable ignored) {}
                                                        for (BlockFace face : new BlockFace[] { BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST }) {
                                                            try { Block nb2 = down2.getRelative(face); if (nb2 != null) try { nb2.getState().update(true, true); } catch (Throwable ignored) {} } catch (Throwable ignored) {}
                                                        }
                                                    }
                                                } catch (Throwable ignored) {}
                                            } catch (Throwable ignored) {}

                                            try {
                                                Object bd = b.getBlockData();
                                                if (bd != null) {
                                                    try {
                                                        java.lang.reflect.Method m = bd.getClass().getMethod("getPower");
                                                        Object val = m.invoke(bd);
                                                        if (val instanceof Number && ((Number) val).intValue() > 0) {
                                                            try { b.getState().update(true, true); } catch (Throwable ignored) {}
                                                        }
                                                    } catch (NoSuchMethodException nsme) {
                                                    } catch (Throwable ignored) {}
                                                }
                                            } catch (Throwable ignored) {}
                                        } catch (Throwable ignored) {}
                                    }
                                } catch (Throwable ignored) {}
                            }
                        }, Math.max(0, nudgeDelay));
                    } catch (Throwable ignored) {}
                }
            }, 1L, 1L);
        } catch (Throwable ignored) {}
        
        startCleanupTask();

        metricCollector = new MetricCollector(plugin);
        metricCollector.start();
        
        try {
            dynamicScaler = new kaiakk.powerhouse.helpers.scaling.DynamicScaler(plugin, metricCollector, 40);
            dynamicScaler.start();
        } catch (Throwable ignored) {}
        distanceController = new kaiakk.powerhouse.world.controllers.DistanceController();
        distanceCalculator = new kaiakk.powerhouse.calculations.DistanceCalculator(plugin, metricCollector, distanceController);
        distanceCalculator.start();
        final int baseEntityThreshold = 16;
        final int baseHopperTransfers = 64;

        entityPusher = new EntityPusher(plugin, baseEntityThreshold, 2.0, 5.0, new java.util.function.DoubleSupplier() {
            public double getAsDouble() { return kaiakk.powerhouse.helpers.scaling.ScaleUtils.getSmoothedMspt(); }
        });
        entityPusher.start();
        hopperLimiter = new HopperLimiter(plugin, baseHopperTransfers);
        hopperLimiter.start();
        
        try {
            if (dynamicScaler != null) {
                final int baseViewLow = 6, baseViewHigh = 10;
                final int baseSimLow = 4, baseSimHigh = 8;
                final int baseEntity = baseEntityThreshold;
                final int baseHopper = baseHopperTransfers;
                scalerSubscriber = new java.util.function.Consumer<Double>() {
                    public void accept(Double s) {
                        try {
                            double mView = kaiakk.powerhouse.helpers.scaling.ScaleUtils.multiplierFromScale(s, 0.6, 1.5);
                            int view = Math.max(baseViewLow, (int) Math.round(baseViewHigh * mView));
                            int sim = Math.max(baseSimLow, (int) Math.round(baseSimHigh * mView));
                            try { distanceController.applyGlobalDistances(view, sim); } catch (Throwable ignored) {}

                            double mEntity = kaiakk.powerhouse.helpers.scaling.ScaleUtils.multiplierFromScale(s, 0.5, 2.0);
                            int thr = Math.max(1, (int) Math.round(baseEntity * mEntity));
                            try { entityPusher.setThreshold(thr); } catch (Throwable ignored) {}

                            double mHopper = kaiakk.powerhouse.helpers.scaling.ScaleUtils.multiplierFromScale(s, 0.5, 2.0);
                            int transfers = Math.max(1, (int) Math.round(baseHopper * mHopper));
                            try { hopperLimiter.setMaxTransfersPerSecond(transfers); } catch (Throwable ignored) {}

                            double mArrow = kaiakk.powerhouse.helpers.scaling.ScaleUtils.multiplierFromScale(s, (5.0/30.0), 2.0);
                            double interval = Math.max(1.0, 30.0 * mArrow);
                            try { setArrowInterval(interval); } catch (Throwable ignored) {}
                        } catch (Throwable ignored) {}
                    }
                };
                dynamicScaler.subscribe(scalerSubscriber);
            }
        } catch (Throwable ignored) {}

        try {
            SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
                public void run() {
                    try {
                        double vel = metricCollector.getMsptVelocity();
                        double avg = metricCollector.getAverageMspt();

                        if (vel > 5.0) {
                            entityPusher.setThreshold(Math.max(5, baseEntityThreshold / 2));
                        } else {
                            entityPusher.setThreshold(baseEntityThreshold);
                        }

                        double msptAvg = ScaleUtils.getSmoothedMspt();
                        double s2 = ScaleUtils.continuousScaleFromMspt(msptAvg);
                        int transfers = ScaleUtils.lerpInt(Math.max(1, baseHopperTransfers / 2), baseHopperTransfers, s2);
                        hopperLimiter.setMaxTransfersPerSecond(transfers);
                    } catch (Throwable ignored) {}
                }
            }, 1.0, 1.0);
        } catch (Throwable ignored) {}
        
        startArrowCleanupTask(30.0);

        try {
            
            SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
                public void run() {
                    try {
                        double raw = metricCollector.getAverageMspt();
                        
                        double s = ScaleUtils.continuousScaleFromMspt(ScaleUtils.getSmoothedMspt());
                        double interval = Math.max(1.0, ScaleUtils.lerp(5.0, 30.0, s));
                        setArrowInterval(interval);
                    } catch (Throwable ignored) {}
                }
            }, 5.0, 5.0);
        } catch (Throwable ignored) {}

        try {
                final int batchLimit = computeBatchLimit();
            PowerhouseLogger.info("Processor batch limit set to: " + batchLimit);

            Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
                public void run() {
                    int limit = batchLimit > 0 ? batchLimit : 50;
                    int processed = 0;
                    while (processed < limit) {
                        Runnable task = pendingSyncTasks.poll();
                        if (task == null) break;
                        try {
                            task.run();
                        } catch (Throwable ignored) {}
                        processed++;
                    }
                }
            }, 1L, 1L);
        } catch (Throwable ignored) {}

        try {
            attemptPruneTileEntities();
        } catch (Throwable ignored) {}

        PowerhouseLogger.info("All optimization systems active!");
    }
    
    public void stop() {
        Calculations.clearAllData();
        lastItemScan.clear();
        lastRedstoneScan.clear();
        if (distanceCalculator != null) distanceCalculator.stop();
        if (metricCollector != null) metricCollector.stop();
            if (dynamicScaler != null) {
                try { if (scalerSubscriber != null) dynamicScaler.unsubscribe(scalerSubscriber); } catch (Throwable ignored) {}
                try {
                    for (java.util.function.Consumer<Double> c : scalableSubscribers.values()) {
                        try { dynamicScaler.unsubscribe(c); } catch (Throwable ignored) {}
                    }
                    scalableSubscribers.clear();
                } catch (Throwable ignored) {}
                dynamicScaler.stop();
            }
        if (entityPusher != null) entityPusher.stop();
        if (hopperLimiter != null) hopperLimiter.stop();
        try { if (arrowTask != null) { kaiakk.multimedia.classes.SchedulerHelper.cancelTask(arrowTask); arrowTask = null; } } catch (Throwable ignored) {}
        try { HandlerList.unregisterAll(this); } catch (Throwable ignored) {}
        INSTANCE = null;
        PowerhouseLogger.info("Powerhouse optimization systems stopped.");
    }

    public void registerScalable(final Scalable s) {
        if (s == null) return;
        try {
            java.util.function.Consumer<Double> c = new java.util.function.Consumer<Double>() {
                public void accept(Double scale) {
                    try { s.setScale(scale); } catch (Throwable ignored) {}
                }
            };
            scalableSubscribers.put(s, c);
            if (dynamicScaler != null) dynamicScaler.subscribe(c);
        } catch (Throwable ignored) {}
    }

    public void unregisterScalable(final Scalable s) {
        if (s == null) return;
        try {
            java.util.function.Consumer<Double> c = scalableSubscribers.remove(s);
            if (c != null && dynamicScaler != null) {
                try { dynamicScaler.unsubscribe(c); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent ev) {
        if (ev == null) return;
        try {
            org.bukkit.World w = ev.getWorld();
            if (w == null) return;
            try { lastItemScan.remove(w); } catch (Throwable ignored) {}
            try { lastRedstoneScan.remove(w); } catch (Throwable ignored) {}
            try { kaiakk.powerhouse.calculations.Calculations.clearWorldData(w.getName()); } catch (Throwable ignored) {}
            try { kaiakk.powerhouse.helpers.logs.DebugLog.debug("Powerhouse: cleared caches for unloaded world: " + w.getName()); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
    
    
private void startItemMergingTask(final World world) {
        SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
        public void run() {
            try {
                for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                    final org.bukkit.Chunk fChunk = chunk;
                    runAtLocation(fChunk.getBlock(0, 64, 0).getLocation(), new Runnable() {
                        public void run() {
                            List<Item> droppedItems = new ArrayList<Item>();
                            List<ExperienceOrb> droppedOrbs = new ArrayList<ExperienceOrb>();

                            try {
                                for (Entity ent : fChunk.getEntities()) {
                                    if (ent instanceof Item) droppedItems.add((Item) ent);
                                    else if (ent instanceof ExperienceOrb) droppedOrbs.add((ExperienceOrb) ent);
                                }
                            } catch (Throwable ignored) {
                                return;
                            }

                            if (droppedItems.isEmpty() && droppedOrbs.isEmpty()) return;

                            final List<kaiakk.powerhouse.data.snapshot.ItemSnapshot> itemSnaps = new ArrayList<>();
                            final Map<Integer, Item> indexToItem = new HashMap<>();
                            final List<kaiakk.powerhouse.data.snapshot.ExperienceOrbSnapshot> orbSnaps = new ArrayList<>();

                            List<Player> worldPlayers = fChunk.getWorld().getPlayers();

                            for (int i = 0; i < droppedItems.size(); i++) {
                                Item it = droppedItems.get(i);
                                if (it == null || !it.isValid()) continue;
                                ItemStack stack = null;
                                try { stack = it.getItemStack(); } catch (Throwable ignored) {}
                                if (stack == null || stack.getType() == org.bukkit.Material.AIR) continue;

                                Location loc = it.getLocation();
                                boolean hasMeta = false;
                                try {
                                    if (stack.hasItemMeta()) {
                                        ItemMeta meta = stack.getItemMeta();
                                        hasMeta = meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants();
                                    }
                                } catch (Throwable ignored) {}

                                boolean playerNearby = false;
                                try {
                                    for (Player p : worldPlayers) {
                                        if (p != null && p.getLocation().distanceSquared(loc) <= 2.25) { playerNearby = true; break; }
                                    }
                                } catch (Throwable ignored) {}

                                kaiakk.powerhouse.data.snapshot.ItemSnapshot s = new kaiakk.powerhouse.data.snapshot.ItemSnapshot(i, loc.getX(), loc.getY(), loc.getZ(),
                                        stack.getType(), stack.getAmount(), stack.getDurability(), stack.getMaxStackSize(), hasMeta, playerNearby);
                                itemSnaps.add(s);
                                indexToItem.put(i, it);
                            }

                            for (int i = 0; i < droppedOrbs.size(); i++) {
                                ExperienceOrb o = droppedOrbs.get(i);
                                if (o == null || o.isDead()) continue;
                                Location loc = o.getLocation();
                                orbSnaps.add(new kaiakk.powerhouse.data.snapshot.ExperienceOrbSnapshot(i, loc.getX(), loc.getY(), loc.getZ(), o.getExperience()));
                            }

                            final List<kaiakk.powerhouse.data.snapshot.ItemSnapshot> finalItemSnaps = new ArrayList<>(itemSnaps);
                            final Map<Integer, Item> finalIndexToItem = new HashMap<>(indexToItem);
                            final List<kaiakk.powerhouse.data.snapshot.ExperienceOrbSnapshot> finalOrbSnaps = new ArrayList<>(orbSnaps);

                            SchedulerHelper.runAsync(plugin, new Runnable() {
                                public void run() {
                                    try {
                                        final Map<kaiakk.powerhouse.data.snapshot.ItemSnapshot, List<kaiakk.powerhouse.data.snapshot.ItemSnapshot>> snapResult = kaiakk.powerhouse.calculations.Calculations.scanItemMergeCandidatesSnapshots(finalItemSnaps);

                                        final Map<Item, List<Item>> mapped = new HashMap<>();
                                        if (snapResult != null && !snapResult.isEmpty()) {
                                            for (Map.Entry<kaiakk.powerhouse.data.snapshot.ItemSnapshot, List<kaiakk.powerhouse.data.snapshot.ItemSnapshot>> ent : snapResult.entrySet()) {
                                                Item key = finalIndexToItem.get(ent.getKey().originalIndex);
                                                if (key == null) continue;
                                                List<Item> merged = new ArrayList<>();
                                                for (kaiakk.powerhouse.data.snapshot.ItemSnapshot s : ent.getValue()) {
                                                    Item o = finalIndexToItem.get(s.originalIndex);
                                                    if (o != null) merged.add(o);
                                                }
                                                if (!merged.isEmpty()) mapped.put(key, merged);
                                            }
                                        }

                                        final Map<Item, List<Item>> finalMapped = mapped;

                                        final Map<kaiakk.powerhouse.data.snapshot.ExperienceOrbSnapshot, List<kaiakk.powerhouse.data.snapshot.ExperienceOrbSnapshot>> xpGroups = kaiakk.powerhouse.calculations.Calculations.scanXpMergeCandidatesSnapshots(finalOrbSnaps);

                                        SchedulerHelper.run(plugin, new Runnable() {
                                            public void run() {
                                                try {
                                                    if (!finalMapped.isEmpty()) performItemMerges(finalMapped);
                                                } catch (Throwable ignored) {}

                                                if (xpGroups != null && !xpGroups.isEmpty()) {
                                                    Map<ExperienceOrb, Integer> xpToMerge = new HashMap<ExperienceOrb, Integer>();
                                                    Set<ExperienceOrb> toRemove = new HashSet<ExperienceOrb>();
                                                    for (Map.Entry<kaiakk.powerhouse.data.snapshot.ExperienceOrbSnapshot, List<kaiakk.powerhouse.data.snapshot.ExperienceOrbSnapshot>> e : xpGroups.entrySet()) {
                                                        ExperienceOrb tgt = droppedOrbs.size() > e.getKey().originalIndex ? droppedOrbs.get(e.getKey().originalIndex) : null;
                                                        if (tgt == null || !tgt.isValid()) continue;
                                                        int total = tgt.getExperience();
                                                        for (kaiakk.powerhouse.data.snapshot.ExperienceOrbSnapshot s : e.getValue()) {
                                                            ExperienceOrb src = droppedOrbs.size() > s.originalIndex ? droppedOrbs.get(s.originalIndex) : null;
                                                            if (src != null && src.isValid()) {
                                                                total += src.getExperience();
                                                                toRemove.add(src);
                                                            }
                                                        }
                                                        xpToMerge.put(tgt, total);
                                                    }

                                                    if (!xpToMerge.isEmpty()) {
                                                        for (Map.Entry<ExperienceOrb, Integer> e : xpToMerge.entrySet()) {
                                                            ExperienceOrb tgt = e.getKey();
                                                            if (tgt != null && tgt.isValid()) {
                                                                try { tgt.setExperience(e.getValue()); } catch (Throwable ignored) {}
                                                            }
                                                        }
                                                        for (ExperienceOrb src : toRemove) {
                                                            if (src != null && src.isValid()) {
                                                                try { markEntityDead(src); } catch (Throwable ignored) {}
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        });
                                    } catch (Throwable ignored) {}
                                }
                            });
                        }
                    });
                }
            } catch (Throwable ignored) {}
        }
    }, ITEM_MERGE_INTERVAL_SECONDS, ITEM_MERGE_INTERVAL_SECONDS);

    PowerhouseLogger.info("Item and XP merging enabled for world: " + world.getName());
}

    private void runAtLocation(Location loc, Runnable task) {
        if (loc == null || task == null) return;
        try {
            try {
                java.lang.reflect.Method getRegionScheduler = Bukkit.getServer().getClass().getMethod("getRegionScheduler");
                Object regionScheduler = getRegionScheduler.invoke(Bukkit.getServer());
                if (regionScheduler != null) {
                    for (java.lang.reflect.Method m : regionScheduler.getClass().getMethods()) {
                        if (!m.getName().equals("execute")) continue;
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length >= 3) {
                            try {
                                m.invoke(regionScheduler, plugin, loc, task);
                                return;
                            } catch (Throwable ignored) {}
                        }
                    }
                }
            } catch (Throwable ignored) {}

            SchedulerHelper.run(plugin, task);
        } catch (Throwable ignored) {}
    }

    private void processMerges(List<Item> items, List<ExperienceOrb> orbs) {
        try {
            final Map<Item, List<Item>> itemGroups = CalculationsSync.scanItemMergeCandidates(items);
            if (!itemGroups.isEmpty()) {
                Location loc = items.get(0).getLocation();
                runAtLocation(loc, new Runnable() {
                    public void run() {
                        performItemMerges(itemGroups);
                    }
                });
            }

            if (orbs == null || orbs.isEmpty()) return;

            final Map<ExperienceOrb, Integer> xpToMerge = new HashMap<ExperienceOrb, Integer>();
            final Set<ExperienceOrb> toRemove = new HashSet<ExperienceOrb>();

            for (int i = 0; i < orbs.size(); i++) {
                ExperienceOrb a = orbs.get(i);
                if (a == null || a.isDead() || toRemove.contains(a)) continue;
                for (int j = i + 1; j < orbs.size(); j++) {
                    ExperienceOrb b = orbs.get(j);
                    if (b == null || b.isDead() || toRemove.contains(b)) continue;
                    try {
                        if (a.getLocation().distanceSquared(b.getLocation()) < 4.0) {
                            int cur = xpToMerge.getOrDefault(a, a.getExperience());
                            xpToMerge.put(a, cur + b.getExperience());
                            toRemove.add(b);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            if (!xpToMerge.isEmpty()) {
                final Map<ExperienceOrb, Integer> finalXp = xpToMerge;
                final Set<ExperienceOrb> finalRemove = toRemove;
                runAtLocation(orbs.get(0).getLocation(), new Runnable() {
                    public void run() {
                        for (Map.Entry<ExperienceOrb, Integer> e : finalXp.entrySet()) {
                            ExperienceOrb tgt = e.getKey();
                            if (tgt != null && tgt.isValid()) {
                                try { tgt.setExperience(e.getValue()); } catch (Throwable ignored) {}
                            }
                        }
                        for (ExperienceOrb src : finalRemove) {
                            if (src != null && src.isValid()) {
                                try { markEntityDead(src); } catch (Throwable ignored) {}
                            }
                        }
                    }
                });
            }
        } catch (Throwable ignored) {}
    }
    
    private void performItemMerges(Map<Item, List<Item>> mergeGroups) {
        final java.util.concurrent.atomic.AtomicInteger mergedCount = new java.util.concurrent.atomic.AtomicInteger(0);

        for (Map.Entry<Item, List<Item>> entry : mergeGroups.entrySet()) {
            final Item target = entry.getKey();
            final List<Item> sources = entry.getValue();

            enqueueEntityTask(target, new Runnable() {
                public void run() {
                    try {
                        if (target == null || !target.isValid() || target.isDead()) return;

                        ItemStack targetStack = target.getItemStack();
                        int maxStack = targetStack.getType().getMaxStackSize();

                        for (Item source : sources) {
                            if (source == null || !source.isValid() || source.isDead() || source.equals(target)) continue;
                            if (targetStack.getAmount() >= maxStack) break;

                            ItemStack sourceStack = source.getItemStack();
                            int moveAmount = Math.min(sourceStack.getAmount(), maxStack - targetStack.getAmount());

                            if (moveAmount > 0) {
                                targetStack.setAmount(targetStack.getAmount() + moveAmount);
                                sourceStack.setAmount(sourceStack.getAmount() - moveAmount);

                                if (sourceStack.getAmount() <= 0) {
                                    try {
                                        if (source.isValid() && !source.isDead()) {
                                            try { markEntityDead(source); } catch (Throwable ignored) {}
                                        }
                                    } catch (Throwable ignored) {}
                                    mergedCount.incrementAndGet();
                                } else {
                                    try { source.setItemStack(sourceStack); } catch (Throwable ignored) {}
                                }
                            }
                        }

                        try { target.setItemStack(targetStack); } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                }
            });
        }

        if (mergedCount.get() > 0) {
            kaiakk.powerhouse.helpers.logs.DebugLog.debug("Merged " + mergedCount.get() + " items into " + mergeGroups.size() + " stacks");
        }
    }
    
    
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        Material type = block.getType();
        
        if (isRedstoneBlock(type)) {
            
            if (Calculations.isBlockCulled(block)) {
                try { Calculations.markThrottled(block.getLocation()); } catch (Throwable ignored) {}
                event.setCancelled(true);
                return;
            }

            
            try {
                kaiakk.powerhouse.data.BlockKey bk = new kaiakk.powerhouse.data.BlockKey(block.getX(), block.getY(), block.getZ(), block.getWorld().getName());
                redstoneListenerCounts.computeIfAbsent(bk, k -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
            } catch (Throwable ignored) {}

            final Location fLoc = block.getLocation();
            SchedulerHelper.runAsync(plugin, new Runnable() {
                public void run() {
                    Calculations.recordBlockUpdate(fLoc);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockRedstone(org.bukkit.event.block.BlockRedstoneEvent event) {
        try {
            org.bukkit.block.Block block = event.getBlock();
            if (block == null) return;
            if (!isRedstoneBlock(block.getType())) return;

            
            if (Calculations.isBlockCulled(block)) {
                try { Calculations.markThrottled(block.getLocation()); } catch (Throwable ignored) {}
                return;
            }

            try {
                kaiakk.powerhouse.data.BlockKey bk = new kaiakk.powerhouse.data.BlockKey(block.getX(), block.getY(), block.getZ(), block.getWorld().getName());
                redstoneListenerCounts.computeIfAbsent(bk, k -> new java.util.concurrent.atomic.AtomicInteger(0)).incrementAndGet();
            } catch (Throwable ignored) {}

            
            final org.bukkit.Location loc = block.getLocation();
            SchedulerHelper.runAsync(plugin, new Runnable() {
                public void run() {
                    Calculations.recordBlockUpdate(loc);
                }
            });
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        try {
            org.bukkit.entity.Entity spawned = event.getEntity();
            if (!(spawned instanceof ExperienceOrb)) return;
            ExperienceOrb orb = (ExperienceOrb) spawned;
            org.bukkit.Location loc = orb.getLocation();
            org.bukkit.World w = loc.getWorld();
            java.util.Collection<org.bukkit.entity.Entity> nearby = null;
            try {
                nearby = w.getNearbyEntities(loc, 2.0, 2.0, 2.0);
            } catch (Throwable ex) {
                nearby = new java.util.ArrayList<org.bukkit.entity.Entity>(w.getEntities());
            }

            for (org.bukkit.entity.Entity e : nearby) {
                if (e instanceof ExperienceOrb && e != orb) {
                    ExperienceOrb existing = (ExperienceOrb) e;
                    try {
                        existing.setExperience(existing.getExperience() + orb.getExperience());
                        event.setCancelled(true);
                        return;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
    }
    
    private boolean isRedstoneBlock(Material type) {
        String name = type.name();
        return name.contains("REDSTONE") || 
               name.contains("REPEATER") || 
               name.contains("COMPARATOR") ||
               name.contains("OBSERVER") ||
               name.contains("PISTON") ||
               name.contains("HOPPER") ||
               name.contains("DISPENSER") ||
               name.contains("DROPPER");
    }
    
    private void startRedstoneCullingTask() {
    final AllOptimizations self = this;
    SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
        public void run() {
            SchedulerHelper.runAsync(plugin, new Runnable() {
                public void run() {
                    try {  } catch (Throwable ignored) {}
                    double mspt = ScaleUtils.getSmoothedMspt();
                    double s = ScaleUtils.continuousScaleFromMspt(mspt);
                    final int dynamicThreshold = ScaleUtils.lerpInt(5, 20, s);
                    final double finalMspt = mspt;

                    try {
                        double recoveredScale = ScaleUtils.continuousScaleFromMspt(30.0);
                        if (mspt > 0.0 && ScaleUtils.continuousScaleFromMspt(mspt) > recoveredScale) {
                            try {
                                Calculations.uncullAll();
                                kaiakk.powerhouse.helpers.logs.DebugLog.debug("Powerhouse: redstone safety valve - cleared all culled locations as MSPT recovered (" + String.format("%.1f", mspt) + "mspt)");
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}

                    
                    try {
                        java.util.Map<kaiakk.powerhouse.data.BlockKey, Integer> drained = new java.util.HashMap<>();
                        for (java.util.Map.Entry<kaiakk.powerhouse.data.BlockKey, java.util.concurrent.atomic.AtomicInteger> e : redstoneListenerCounts.entrySet()) {
                            try {
                                int got = e.getValue().getAndSet(0);
                                if (got > 0) drained.put(e.getKey(), got);
                            } catch (Throwable ignored) {}
                        }
                        if (!drained.isEmpty()) {
                            try { kaiakk.powerhouse.calculations.Calculations.applyListenerCounts(drained); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}

                    final java.util.Map<Location, Integer> toCullWithCounts = Calculations.scanRedstoneCullingCandidatesWithCounts(dynamicThreshold);
                    
                    if (!toCullWithCounts.isEmpty()) {
                        SchedulerHelper.run(plugin, new Runnable() {
                            public void run() {
                                for (java.util.Map.Entry<Location, Integer> entry : toCullWithCounts.entrySet()) {
                                    Location loc = entry.getKey();
                                    int count = entry.getValue();
                                    
                                    boolean playerNearby = false;
                                    if (ScaleUtils.continuousScaleFromMspt(finalMspt) > 0.05) {
                                        for (Player p : loc.getWorld().getPlayers()) {
                                            if (p.getLocation().distanceSquared(loc) <= 256) {
                                                playerNearby = true;
                                                break;
                                            }
                                        }
                                    }

                                    if (playerNearby) continue;

                                    Calculations.markLocationCulled(loc);
                                    String locStr = formatLocation(loc);
                                    
                                    PowerhouseLogger.warn("Culled redstone at: " + locStr + " (" + count + " updates/sec @ " + String.format("%.1f", finalMspt) + "mspt)");
                                }
                            }
                        });
                    }
                }
            });
        }
    }, (double) REDSTONE_CHECK_INTERVAL_SECONDS, (double) REDSTONE_CHECK_INTERVAL_SECONDS);
    
    kaiakk.powerhouse.helpers.logs.DebugLog.debug("Adaptive redstone culling active!");
}
    
    private void startCleanupTask() {
        final AllOptimizations self = this;
        SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
            public void run() {
                SchedulerHelper.runAsync(plugin, new Runnable() {
                    public void run() {
                        final List<Location> expired = Calculations.getExpiredCulledLocations();
                        
                        if (!expired.isEmpty()) {
                            SchedulerHelper.run(plugin, new Runnable() {
                                public void run() {
                                    for (Location loc : expired) {
                                        
                                        try {
                                            for (int dx = -2; dx <= 2; dx++) {
                                                for (int dy = -1; dy <= 1; dy++) {
                                                    for (int dz = -2; dz <= 2; dz++) {
                                                        try {
                                                            final Location nloc = loc.clone().add(dx, dy, dz);
                                                            Calculations.uncullLocation(nloc);
                                                            try {
                                                                org.bukkit.block.Block nb = nloc.getBlock();
                                                                if (nb != null) try { nb.getState().update(true, true); } catch (Throwable ignored) {}
                                                            } catch (Throwable ignored) {}
                                                        } catch (Throwable ignored) {}
                                                    }
                                                }
                                            }

                                            
                                            try {
                                                final Location base = loc;
                                                SchedulerHelper.runLater(plugin, new Runnable() {
                                                    public void run() {
                                                        try {
                                                            for (int dx = -2; dx <= 2; dx++) {
                                                                for (int dy = -1; dy <= 1; dy++) {
                                                                    for (int dz = -2; dz <= 2; dz++) {
                                                                        try {
                                                                            Location nloc2 = base.clone().add(dx, dy, dz);
                                                                            try {
                                                                                org.bukkit.block.Block b2 = nloc2.getBlock();
                                                                                if (b2 != null) try { b2.getState().update(true, true); } catch (Throwable ignored) {}
                                                                            } catch (Throwable ignored) {}
                                                                        } catch (Throwable ignored) {}
                                                                    }
                                                                }
                                                            }
                                                        } catch (Throwable ignored) {}
                                                    }
                                                }, 2L);
                                            } catch (Throwable ignored) {}
                                        } catch (Throwable ignored) {}
                                        String locStr = formatLocation(loc);
                                        kaiakk.powerhouse.helpers.logs.DebugLog.debug("Restored redstone at: " + locStr);

                                    }
                                }
                            });
                        }
                        
                        final Map<String, Object> stats = Calculations.getStatistics();
                            if ((int) stats.get("culled_locations") > 0) {
                            PowerhouseLogger.info("Powerhouse Stats - Monitored: " + stats.get("monitored_locations") + 
                                          ", Culled: " + stats.get("culled_locations"));

                        }
                    }
                });
            }
        }, (double) CLEANUP_INTERVAL_SECONDS, (double) CLEANUP_INTERVAL_SECONDS);
        
        PowerhouseLogger.info("Cleanup task enabled");
    }

    private void startArrowCleanupTask(double intervalSeconds) {
        double prevInterval = currentArrowInterval;
        boolean wasRunning = arrowTask != null;
        if (wasRunning) {
            try { SchedulerHelper.cancelTask(arrowTask); } catch (Throwable ignored) {}
            arrowTask = null;
        }
        final double useInterval = Math.max(1.0, intervalSeconds);
        currentArrowInterval = useInterval;
        arrowTask = SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
            public void run() {
                String tname = Thread.currentThread().getName();
                if (tname != null && (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin) || tname.contains("Region") || tname.contains("Threaded"))) {
                    try { SchedulerHelper.run(plugin, this); } catch (Throwable ignored) {}
                    return;
                }

                for (World w : Bukkit.getWorlds()) {
                    try {
                        for (Entity e : new ArrayList<Entity>(w.getEntities())) {
                            if (e == null) continue;
                            try { if (!e.isValid()) continue; } catch (Throwable ignored) { continue; }
                            if (!(e instanceof Arrow)) continue;
                            Arrow a = (Arrow) e;
                                double v2 = a.getVelocity().lengthSquared();
                                if (v2 < 0.0001) {
                                    try { if (a.isValid() && !a.isDead()) { AllOptimizations.getInstance().markEntityDead(a); } } catch (Throwable ignored) {}
                                }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }, useInterval, useInterval);

        
        
    }

    private void setArrowInterval(double seconds) {
        if (Math.abs(currentArrowInterval - seconds) < 1.0) return;
        startArrowCleanupTask(seconds);
    }
    
    
    private String formatLocation(Location loc) {
        return String.format("%s(%d, %d, %d)", 
            loc.getWorld().getName(),
            loc.getBlockX(), 
            loc.getBlockY(), 
            loc.getBlockZ());
    }

    private void attemptPruneTileEntities() {
        SchedulerHelper.runLater(plugin, new Runnable() {
            public void run() {
                int removed = 0;
                for (World w : Bukkit.getWorlds()) {
                    try {
                        java.lang.reflect.Method getHandle = w.getClass().getMethod("getHandle");
                        Object worldHandle = getHandle.invoke(w);
                        if (worldHandle == null) continue;

                        java.lang.reflect.Field listField = null;
                        for (String fname : new String[]{"tileEntityList", "tileEntities", "tileEntity"}) {
                            try {
                                java.lang.reflect.Field f = worldHandle.getClass().getDeclaredField(fname);
                                f.setAccessible(true);
                                listField = f;
                                break;
                            } catch (Throwable ignored) {}
                        }
                        if (listField == null) continue;

                        Object listObj = listField.get(worldHandle);
                        if (listObj instanceof java.util.Collection) {
                            java.util.Collection col = (java.util.Collection) listObj;
                            java.util.Iterator it = col.iterator();
                            while (it.hasNext()) {
                                Object te = it.next();
                                if (te == null) continue;
                                String cn = te.getClass().getSimpleName();
                                if (cn.contains("Sign") || cn.contains("Skull") || cn.contains("TileEntitySign") || cn.contains("TileEntitySkull")) {
                                    it.remove();
                                    removed++;
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                }
                if (removed > 0) {
                    kaiakk.powerhouse.helpers.logs.DebugLog.debug("Pruned " + removed + " sign/skull tile-entities from ticking lists (best-effort)");
                }
            }
        }, 20L);
    }
    
    public Map<String, Object> getStatistics() {
        try {
            Map<String, Object> stats = new HashMap<>(Calculations.getStatistics());
            stats.put("cramming_removals", getCrammingRemovals());
            stats.put("item_removals", getItemRemovals());
            return stats;
        } catch (Throwable ignored) {
            return Calculations.getStatistics();
        }
    }

    private int computeBatchLimit() {
        try {
            try {
                Class.forName("io.papermc.paper.threadedregions.scheduler.RegionScheduler");
                return 500;
            } catch (Throwable ignored) {}

            String bv = Bukkit.getBukkitVersion();
            String ver = bv.split("-")[0];
            String[] parts = ver.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;

            if (major > 1 || (major == 1 && minor >= 21)) return 400;
            if (major == 1 && minor >= 16) return 250;
            if (major == 1 && minor <= 10) return 50;
        } catch (Throwable ignored) {}

        return 100;
    }

    public void markEntityDead(org.bukkit.entity.Entity ent) {
        if (ent == null) return;
        try {
            Object handle = null;
            try {
                java.lang.reflect.Method getHandle = ent.getClass().getMethod("getHandle");
                handle = getHandle.invoke(ent);
            } catch (Throwable ignored) {}

            if (handle != null) {
                try {
                    java.lang.reflect.Method die = handle.getClass().getMethod("die");
                    die.invoke(handle);
                    kaiakk.powerhouse.helpers.logs.DebugLog.debug("markEntityDead via die(): " + ent.getType() + " @ " + ent.getLocation());
                    return;
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Method setDead = handle.getClass().getMethod("setDead");
                    setDead.invoke(handle);
                    try { kaiakk.powerhouse.helpers.logs.DebugLog.debug("markEntityDead via setDead(): " + ent.getType() + " @ " + ent.getLocation()); } catch (Throwable ignored) {}
                    return;
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Field deadField = handle.getClass().getDeclaredField("dead");
                    deadField.setAccessible(true);
                    deadField.setBoolean(handle, true);
                    try { kaiakk.powerhouse.helpers.logs.DebugLog.debug("markEntityDead via dead field: " + ent.getType() + " @ " + ent.getLocation()); } catch (Throwable ignored) {}
                    return;
                } catch (Throwable ignored) {}

                try {
                    java.lang.reflect.Field ticks = handle.getClass().getDeclaredField("ticksLived");
                    ticks.setAccessible(true);
                    ticks.setInt(handle, 24000);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}


