package kaiakk.powerhouse.world.controllers;

import kaiakk.multimedia.classes.SchedulerHelper;
import kaiakk.powerhouse.world.AllOptimizations;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.util.logging.Level;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ServerController {
    private final JavaPlugin plugin;
    
    private volatile BukkitTask monitorTask = null;
    private volatile BukkitTask countdownTask = null;
    private final AtomicBoolean countingDown = new AtomicBoolean(false);
    private final AtomicInteger consecutiveHigh = new AtomicInteger(0);

    private static final double EMERGENCY_MSPT = 325.0;
    private static final double CRITICAL_SHUTDOWN_MSPT = 425.0;
    private static final double WARN_MSPT = 150.0;
    private static final double COUNTDOWN_MSPT = 250.0;
    private final int graceSeconds;
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public ServerController(JavaPlugin plugin) {
        this(plugin, 20);
    }

    public ServerController(JavaPlugin plugin, int graceSeconds) {
        this.plugin = plugin;
        this.graceSeconds = graceSeconds;
    }

    public void start() {
        if (monitorTask != null) return;
        
        final ServerController self = this;
        monitorTask = SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
            public void run() { self.checkHealth(); }
        }, 1.0, 1.0);
    }

    public void stop() {
        
        if (monitorTask != null) {
            SchedulerHelper.cancelTask(monitorTask);
            monitorTask = null;
        }
        if (countdownTask != null) {
            SchedulerHelper.cancelTask(countdownTask);
            countdownTask = null;
        }
        countingDown.set(false);
    }

    private void checkHealth() {
        try {
            if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return;
            double mspt = 0.0;
            try {
                AllOptimizations ao = AllOptimizations.getInstance();
                if (ao != null) mspt = ao.getAverageMspt();
            } catch (Throwable t) {
                try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: failed to read MSPT", t); } catch (Throwable ignored) {}
            }

            if (Double.isNaN(mspt) || mspt < 0.0) mspt = 0.0;

            try {
                if (mspt >= CRITICAL_SHUTDOWN_MSPT) {
                    try { Bukkit.getLogger().warning("Powerhouse: CRITICAL MSPT spike >= " + CRITICAL_SHUTDOWN_MSPT + "ms detected - forcing IMMEDIATE shutdown"); } catch (Throwable ignored) {}
                    try {
                        if (monitorTask != null) {
                            SchedulerHelper.cancelTask(monitorTask);
                            monitorTask = null;
                        }
                    } catch (Throwable t) {
                        try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: failed to cancel monitor task during critical shutdown", t); } catch (Throwable ignored) {}
                    }
                    try {
                        if (countdownTask != null) {
                            SchedulerHelper.cancelTask(countdownTask);
                            countdownTask = null;
                        }
                    } catch (Throwable t) {
                        try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: failed to cancel countdown task during critical shutdown", t); } catch (Throwable ignored) {}
                    }
                    try { finalizeShutdown(); } catch (Throwable t) { try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: critical finalizeShutdown failed", t); } catch (Throwable ignored) {} }
                    return;
                }

                if (mspt >= EMERGENCY_MSPT) {
                    try { Bukkit.getLogger().warning("Powerhouse: critical MSPT spike >= " + EMERGENCY_MSPT + "ms detected - forcing immediate shutdown"); } catch (Throwable ignored) {}
                    try {
                        if (monitorTask != null) {
                            SchedulerHelper.cancelTask(monitorTask);
                            monitorTask = null;
                        }
                    } catch (Throwable t) {
                        try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: failed to cancel monitor task during emergency shutdown", t); } catch (Throwable ignored) {}
                    }
                    try {
                        if (countdownTask != null) {
                            SchedulerHelper.cancelTask(countdownTask);
                            countdownTask = null;
                        }
                    } catch (Throwable t) {
                        try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: failed to cancel countdown task during emergency shutdown", t); } catch (Throwable ignored) {}
                    }
                    try { finalizeShutdown(); } catch (Throwable t) { try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: emergency finalizeShutdown failed", t); } catch (Throwable ignored) {} }
                    return;
                }
            } catch (Throwable t) { try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: error evaluating emergency MSPT condition", t); } catch (Throwable ignored) {} }

            if (mspt >= WARN_MSPT) {
                if (warned.compareAndSet(false, true)) {
                    Bukkit.broadcastMessage("\u00A7e[Powerhouse] Warning: server MSPT high (" + String.format("%.2f", mspt) + "ms). If this continues the server may shut down.");
                }
            } else {
                warned.set(false);
            }

            if (mspt >= COUNTDOWN_MSPT) {
                int now = consecutiveHigh.incrementAndGet();
                if (now >= 4) {
                    if (countingDown.compareAndSet(false, true)) {
                        beginCountdown();
                    }
                }
            } else {
                consecutiveHigh.set(0);
                if (countingDown.compareAndSet(true, false)) {
                    if (countdownTask != null) {
                        SchedulerHelper.cancelTask(countdownTask);
                        countdownTask = null;
                    }
                    Bukkit.broadcastMessage("\u00A7a[Powerhouse] Server recovered. Shutdown aborted.");
                }
            }
        } catch (Throwable ignored) {}
    }

    private void beginCountdown() {
        final java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(graceSeconds);
        Bukkit.broadcastMessage("\u00A74[Powerhouse] Server performance critically degraded. Shutting down in " + left.get() + " seconds. Please disconnect immediately.");
        final ServerController self = this;
        countdownTask = SchedulerHelper.runTimerSeconds(plugin, new Runnable() {
            public void run() {
                try {
                    int nowLeft = left.decrementAndGet();
                    if (nowLeft <= 0) {
                        try { finalizeShutdown(); } catch (Throwable t) { try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: finalizeShutdown failed during countdown", t); } catch (Throwable ignored) {} }
                        countingDown.set(false);
                        if (countdownTask != null) {
                            try { SchedulerHelper.cancelTask(countdownTask); } catch (Throwable t) { try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: failed to cancel countdown task", t); } catch (Throwable ignored) {} }
                            countdownTask = null;
                        }
                        return;
                    }
                    if (nowLeft <= 5 || (nowLeft % 5 == 0)) {
                        Bukkit.broadcastMessage("\u00A74[Powerhouse] Server shutting down in " + nowLeft + " seconds. Please disconnect.");
                    }
                } catch (Throwable t) {
                    try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: exception in countdown task", t); } catch (Throwable ignored) {}
                }
            }
        }, 1.0, 1.0);
    }

    private void finalizeShutdown() {
        String reason = "The server has been shutdown due to performance issues! Please reconnect later if fixed.";
        try {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all");
            } catch (Throwable ignored) {}

            for (Player p : Bukkit.getOnlinePlayers()) {
                try { p.kickPlayer(reason); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = Bukkit.class.getMethod("shutdown");
            m.invoke(null);
            return;
        } catch (Throwable t) {
            try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: reflection shutdown (Bukkit.shutdown) failed, trying server instance", t); } catch (Throwable ignored) {}
        }

        try {
            Object srv = Bukkit.getServer();
            java.lang.reflect.Method ms = srv.getClass().getMethod("shutdown");
            ms.invoke(srv);
            return;
        } catch (Throwable t) {
            try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: reflection shutdown on server instance failed, trying System.exit", t); } catch (Throwable ignored) {}
        }

        try {
            System.exit(0);
        } catch (Throwable t) {
            try { Bukkit.getLogger().log(Level.WARNING, "Powerhouse: System.exit failed during finalizeShutdown", t); } catch (Throwable ignored) {}
        }
    }
}


