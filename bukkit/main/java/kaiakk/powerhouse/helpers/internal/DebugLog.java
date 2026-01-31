package kaiakk.powerhouse.helpers.internal;

import kaiakk.multimedia.classes.ColorConverter;
import kaiakk.powerhouse.world.AllOptimizations;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public final class DebugLog {
    private DebugLog() {}

    public static void debug(String msg) {
        try {
            AllOptimizations ao = AllOptimizations.getInstance();
            if (ao != null && ao.isDebugEnabled()) {
                try { PowerhouseLogger.info("[Powerhouse DEBUG] " + msg); } catch (Throwable ignored) {}
            }

            try {
                if (ao != null) {
                    for (String name : ao.getDebugUsersSnapshot()) {
                        try {
                            Player p = Bukkit.getPlayerExact(name);
                            if (p != null && p.isOnline()) {
                                p.sendMessage(ColorConverter.colorize("&7[Powerhouse DEBUG] &f" + msg));
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }
}


