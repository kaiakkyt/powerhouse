package kaiakk.powerhouse.world.physics;

import kaiakk.multimedia.classes.SchedulerHelper;
import kaiakk.powerhouse.calculations.Calculations;
import kaiakk.powerhouse.helpers.internal.PowerhouseLogger;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Map;

public class ParticleCulling {
    private final Plugin plugin;
    private volatile org.bukkit.scheduler.BukkitTask task = null;

    public ParticleCulling(Plugin plugin) {
        this.plugin = plugin;
    }

    public static ParticleCulling init(Plugin plugin) {
        ParticleCulling pc = new ParticleCulling(plugin);
        try { pc.start(); } catch (Throwable ignored) {}
        return pc;
    }

    public void start() {
        if (task != null) return;
        task = SchedulerHelper.runTimerSeconds(plugin, this::tick, 5.0, 5.0);
    }

    public void stop() {
        if (task != null) {
            try { SchedulerHelper.cancelTask(task); } catch (Throwable ignored) {}
            task = null;
        }
    }

    private void tick() {
        SchedulerHelper.runAsync(plugin, () -> {
            try {
                final int threshold = 100;
                final Map<Location, Integer> cands = Calculations.scanParticleCullingCandidatesWithCounts(threshold);
                if (cands == null || cands.isEmpty()) return;

                SchedulerHelper.run(plugin, () -> {
                    for (Map.Entry<Location, Integer> e : cands.entrySet()) {
                        Location loc = e.getKey();
                        int count = e.getValue();
                        boolean playerNearby = false;
                        for (Player p : loc.getWorld().getPlayers()) {
                            try { if (p.getLocation().distanceSquared(loc) <= 256) { playerNearby = true; break; } } catch (Throwable ignored) {}
                        }
                        if (playerNearby) continue;
                        Calculations.markParticleLocationCulled(loc);
                        String locStr = String.format("%s(%d, %d, %d)", loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
                        PowerhouseLogger.warn("Culled particles at: " + locStr + " (" + count + " particles/sec)");
                    }
                });
            } catch (Throwable ignored) {}
        });
    }
}
