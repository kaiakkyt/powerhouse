package kaiakk.powerhouse.sync;

import kaiakk.multimedia.classes.SchedulerHelper;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ProjectileManager implements Listener {
    private final Plugin plugin;
    private volatile org.bukkit.scheduler.BukkitTask task = null;
    private final Map<UUID, ProjectileData> projectiles = new ConcurrentHashMap<>();

    private static class ProjectileData {
        long spawnTime;
        long lastMoved;
        Location lastLocation;
        int stationaryTicks;
        ProjectileData(long now, Location loc) { this.spawnTime = now; this.lastMoved = now; this.lastLocation = loc; this.stationaryTicks = 0; }
    }

    public ProjectileManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public static ProjectileManager init(Plugin plugin) {
        ProjectileManager pm = new ProjectileManager(plugin);
        try { pm.start(); } catch (Throwable ignored) {}
        return pm;
    }

    public void start() {
        if (task != null) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = SchedulerHelper.runTimerSeconds(plugin, this::tick, 2.0, 2.0);
    }

    public void stop() {
        if (task != null) {
            try { SchedulerHelper.cancelTask(task); } catch (Throwable ignored) {}
            task = null;
        }
        projectiles.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileLaunch(ProjectileLaunchEvent ev) {
        if (ev == null) return;
        try {
            org.bukkit.entity.Projectile p = ev.getEntity();
            if (p == null) return;
            projectiles.put(p.getUniqueId(), new ProjectileData(System.currentTimeMillis(), p.getLocation()));
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent ev) {
        if (ev == null) return;
        try {
            Projectile p = ev.getEntity();
            if (p == null) return;
            projectiles.remove(p.getUniqueId());
        } catch (Throwable ignored) {}
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, ProjectileData> e : projectiles.entrySet()) {
            try {
                UUID id = e.getKey();
                ProjectileData d = e.getValue();
                if (id == null || d == null) { projectiles.remove(id); continue; }

                Entity ent = Bukkit.getEntity(id);
                if (ent == null || ent.isDead()) { projectiles.remove(id); continue; }

                
                Location cur = null;
                try { cur = ent.getLocation(); } catch (Throwable ignored) {}
                if (cur == null) { projectiles.remove(id); continue; }

                double dsq = d.lastLocation.distanceSquared(cur);
                if (dsq <= 0.0001) {
                    d.stationaryTicks++;
                } else {
                    d.stationaryTicks = 0;
                    d.lastMoved = now;
                    d.lastLocation = cur;
                }

                long age = now - d.spawnTime;
                boolean noPlayersNearby = true;
                try {
                    for (Player p : cur.getWorld().getPlayers()) {
                        try { if (p.getLocation().distanceSquared(cur) <= 256) { noPlayersNearby = false; break; } } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}

                
                boolean slow = false;
                try {
                    if (ent instanceof Projectile) {
                        double vel2 = ((Projectile) ent).getVelocity().lengthSquared();
                        slow = vel2 < 0.04; 
                    }
                } catch (Throwable ignored) {}

                if ((age > 5000 && (slow || d.stationaryTicks >= 3)) || (age > 20000)) {
                    if (noPlayersNearby) {
                        final UUID remId = id;
                        projectiles.remove(id);
                        SchedulerHelper.run(plugin, () -> {
                            try {
                                Entity re = Bukkit.getEntity(remId);
                                if (re != null && !re.isDead()) {
                                    try { AllOptimizations.getInstance().markEntityDead(re); } catch (Throwable ignored) { try { re.remove(); } catch (Throwable ignored2) {} }
                                }
                            } catch (Throwable ignored) {}
                        });
                    } else {
                        
                        if (age > 60000) projectiles.remove(id);
                    }
                }
            } catch (Throwable ignored) {}
        }
    }
}

