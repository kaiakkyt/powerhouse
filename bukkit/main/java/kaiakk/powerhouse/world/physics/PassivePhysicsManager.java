package kaiakk.powerhouse.world.physics;

import kaiakk.multimedia.classes.SchedulerHelper;
import kaiakk.powerhouse.data.RecentActionTracker;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PassivePhysicsManager implements Listener {
    private final Plugin plugin;
    private volatile org.bukkit.scheduler.BukkitTask task = null;
    private final Map<UUID, Long> passiveMarked = new ConcurrentHashMap<>();
    private final Queue<Entity> entitiesToMakePassive = new LinkedList<>();
    private final Queue<Entity> entitiesToRestore = new LinkedList<>();

    private static final int DEFAULT_THRESHOLD = 100;
    private static final double PLAYER_RADIUS = 32.0;
    private static final long MARKED_TTL_MS = 5 * 60_000L;

    public PassivePhysicsManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public static PassivePhysicsManager init(Plugin plugin) {
        PassivePhysicsManager mgr = new PassivePhysicsManager(plugin);
        try { mgr.start(); } catch (Throwable ignored) {}
        return mgr;
    }

    public void start() {
        if (task != null) return;
        try { Bukkit.getPluginManager().registerEvents(this, plugin); } catch (Throwable ignored) {}
        task = SchedulerHelper.runTimerSeconds(plugin, this::tick, 5.0, 5.0);
        
        // Process queued entity modifications on main thread
        SchedulerHelper.runTimerSeconds(plugin, this::processEntityQueues, 0.0, 0.05);
    }

    public void stop() {
        if (task != null) {
            try { SchedulerHelper.cancelTask(task); } catch (Throwable ignored) {}
            task = null;
        }
        passiveMarked.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitySpawn(EntitySpawnEvent ev) {
    }
    
    private void processEntityQueues() {
        // Process passive queue
        for (int i = 0; i < 10 && !entitiesToMakePassive.isEmpty(); i++) {
            Entity e = entitiesToMakePassive.poll();
            if (e != null && !e.isDead()) {
                applyPassive(e);
            }
        }
        
        // Process restore queue
        for (int i = 0; i < 10 && !entitiesToRestore.isEmpty(); i++) {
            Entity e = entitiesToRestore.poll();
            if (e != null && !e.isDead()) {
                applyRestore(e);
            }
        }
    }

    private void tick() {
        try {
            for (org.bukkit.World w : Bukkit.getWorlds()) {
                if (w == null) continue;
                int heavyCount = 0;
                for (Entity e : w.getEntities()) {
                    if (isHeavyType(e)) heavyCount++;
                }

                if (heavyCount < DEFAULT_THRESHOLD) {
                    for (UUID id : passiveMarked.keySet()) {
                        try {
                            Entity e = Bukkit.getEntity(id);
                            if (e == null || e.isDead()) { passiveMarked.remove(id); continue; }
                            Location loc = e.getLocation();
                            if (loc == null || loc.getWorld() == null) { passiveMarked.remove(id); continue; }
                            boolean playerNear = false;
                            double r2 = PLAYER_RADIUS * PLAYER_RADIUS;
                            for (Player p : loc.getWorld().getPlayers()) {
                                try { if (p != null && p.getLocation().distanceSquared(loc) <= r2) { playerNear = true; break; } } catch (Throwable ignored) {}
                            }
                            if (playerNear) restoreEntity(e);
                        } catch (Throwable ignored) {}
                    }
                    continue;
                }

                for (Entity e : w.getEntities()) {
                    try {
                        if (!isHeavyType(e)) continue;
                        if (e instanceof Player) continue;

                        UUID id = e.getUniqueId();
                        if (passiveMarked.containsKey(id)) {
                            passiveMarked.put(id, System.currentTimeMillis());
                            continue;
                        }

                        Location loc = e.getLocation();
                        if (loc == null || loc.getWorld() == null) continue;

                        try {
                            RecentActionTracker rt = RecentActionTracker.getInstance();
                            if (rt != null && rt.wasEntityRecentlyPlayerSpawned(id)) continue;
                        } catch (Throwable ignored) {}

                        boolean playerNear = false;
                        double r2 = PLAYER_RADIUS * PLAYER_RADIUS;
                        for (Player p : loc.getWorld().getPlayers()) {
                            try { if (p != null && p.getLocation().distanceSquared(loc) <= r2) { playerNear = true; break; } } catch (Throwable ignored) {}
                        }
                        if (playerNear) continue;

                        makePassive(e);
                        passiveMarked.put(id, System.currentTimeMillis());
                    } catch (Throwable ignored) {}
                }

                long now = System.currentTimeMillis();
                passiveMarked.entrySet().removeIf(en -> now - en.getValue() > MARKED_TTL_MS);
            }
        } catch (Throwable ignored) {}
    }

    private boolean isHeavyType(Entity e) {
        if (e == null) return false;
        try {
            if (e instanceof Projectile) return true;
            String name = e.getType().name();
            if (name.contains("TNT") || name.contains("MINECART") || name.contains("ARMOR_STAND") || name.contains("EXPERIENCE_ORB")) return true;
            if (e instanceof LivingEntity) return true;
        } catch (Throwable ignored) {}
        return false;
    }

    private void makePassive(Entity e) {
        if (e == null) return;
        entitiesToMakePassive.offer(e);
    }

    private void restoreEntity(Entity e) {
        if (e == null) return;
        entitiesToRestore.offer(e);
    }
    
    private void applyPassive(Entity e) {
        try {
            try { e.setMetadata("powerhouse_passive", new FixedMetadataValue(plugin, true)); } catch (Throwable ignored) {}
            try { e.setVelocity(new org.bukkit.util.Vector(0,0,0)); } catch (Throwable ignored) {}
            try {
                if (e instanceof LivingEntity) {
                    try { ((LivingEntity)e).setAI(false); } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
            try { e.setGravity(false); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private void applyRestore(Entity e) {
        try {
            try { e.removeMetadata("powerhouse_passive", plugin); } catch (Throwable ignored) {}
            try { e.setVelocity(new org.bukkit.util.Vector(0,0,0)); } catch (Throwable ignored) {}
            try { if (e instanceof LivingEntity) { ((LivingEntity)e).setAI(true); } } catch (Throwable ignored) {}
            try { e.setGravity(true); } catch (Throwable ignored) {}
            passiveMarked.remove(e.getUniqueId());
        } catch (Throwable ignored) {}
    }
}

