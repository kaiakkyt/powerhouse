package kaiakk.powerhouse.world.explosion;

import kaiakk.multimedia.classes.SchedulerHelper;
import kaiakk.powerhouse.calculations.Calculations;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;

public class ExplosionListener implements Listener {
    private final Plugin plugin;

    public ExplosionListener(Plugin plugin) { this.plugin = plugin; }

    public static ExplosionListener init(Plugin plugin) {
        ExplosionListener el = new ExplosionListener(plugin);
        try { el.start(); } catch (Throwable ignored) {}
        return el;
    }

    public void start() {
        try { Bukkit.getPluginManager().registerEvents(this, plugin); } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent ev) {
        if (ev == null) return;
        try {
            Location loc = ev.getLocation();
            if (loc == null) return;

            int affected = ev.blockList() == null ? 0 : ev.blockList().size();
            
            if (affected > 8) {
                int intensity = Math.min(affected, 200);
                
                for (int i = 0; i < intensity / 8; i++) {
                    Calculations.recordParticle(loc);
                    
                    Calculations.recordParticle(loc.clone().add(1, 0, 0));
                    Calculations.recordParticle(loc.clone().add(-1, 0, 0));
                    Calculations.recordParticle(loc.clone().add(0, 0, 1));
                    Calculations.recordParticle(loc.clone().add(0, 1, 0));
                }

                
                boolean playerNearby = false;
                for (Player p : loc.getWorld().getPlayers()) {
                    try { if (p.getLocation().distanceSquared(loc) <= 256) { playerNearby = true; break; } } catch (Throwable ignored) {}
                }
                if (!playerNearby) {
                    SchedulerHelper.run(plugin, () -> {
                        try { Calculations.markParticleLocationCulled(loc); } catch (Throwable ignored) {}
                    });
                }
            }
        } catch (Throwable ignored) {}
    }
}

