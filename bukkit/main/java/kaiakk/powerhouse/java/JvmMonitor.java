package kaiakk.powerhouse.java;

import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

import kaiakk.powerhouse.helpers.internal.PowerhouseLogger;
import kaiakk.powerhouse.helpers.scaling.ScaleUtils;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public final class JvmMonitor {
    private JvmMonitor() {}
    private static volatile Object currentRecording = null;
    private static volatile BukkitTask autoJfrTask = null;
    private static final AtomicInteger autoJfrConsecutive = new AtomicInteger(0);
    private static final Pattern FILENAME_SAFE = Pattern.compile("[^A-Za-z0-9._-]");

    public static void status(CommandSender sender) {
        try {
            Runtime rt = Runtime.getRuntime();
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();

            long free = rt.freeMemory();
            long total = rt.totalMemory();
            long max = rt.maxMemory();

            MemoryUsage heap = mem.getHeapMemoryUsage();
            MemoryUsage nonHeap = mem.getNonHeapMemoryUsage();

            sender.sendMessage("--- JVM Status ---");
            sender.sendMessage("Heap (used/committed/max): " + human(heap.getUsed()) + " / " + human(heap.getCommitted()) + " / " + human(heap.getMax()));
            sender.sendMessage("Non-heap (used/committed): " + human(nonHeap.getUsed()) + " / " + human(nonHeap.getCommitted()));
            sender.sendMessage("Runtime (free/total/max): " + human(free) + " / " + human(total) + " / " + human(max));
            sender.sendMessage("Threads: " + threads.getThreadCount() + " (peak: " + threads.getPeakThreadCount() + ")");

            long gcCount = 0L, gcTime = 0L;
            for (GarbageCollectorMXBean g : gcs) {
                try { gcCount += g.getCollectionCount(); gcTime += g.getCollectionTime(); } catch (Throwable ignored) {}
            }
            sender.sendMessage("GC: count=" + gcCount + " time(ms)=" + gcTime);
            sender.sendMessage("Uptime (ms): " + ManagementFactory.getRuntimeMXBean().getUptime());
        } catch (Throwable t) {
            try { sender.sendMessage("Error retrieving JVM status: " + t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    public static void runGc(CommandSender sender) {
        try {
            try { sender.sendMessage("Warning: System.gc() may trigger a Stop-The-World pause on some JVMs (G1/ZGC behavior varies)."); } catch (Throwable ignored) {}
            System.gc();
            try { sender.sendMessage("Requested full GC (System.gc())."); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { sender.sendMessage("Failed to request GC: " + t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    public static void heapDump(JavaPlugin plugin, String fileName, CommandSender sender) {
        try {
            if (fileName == null || fileName.isEmpty()) {
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                fileName = "heapdump-" + stamp + ".hprof";
            }
            fileName = sanitizeFileName(fileName);
            if (!fileName.toLowerCase().endsWith(".hprof")) fileName = fileName + ".hprof";
            File dir = plugin.getDataFolder();
            if (!dir.exists()) dir.mkdirs();
            Path out = Paths.get(dir.getAbsolutePath(), fileName);

            try {
                Class<?> hsClass = Class.forName("com.sun.management.HotSpotDiagnosticMXBean");
                Object hs = ManagementFactory.getPlatformMXBean((Class) hsClass);
                if (hs != null) {
                    java.lang.reflect.Method dump = hsClass.getMethod("dumpHeap", String.class, boolean.class);
                    dump.invoke(hs, out.toAbsolutePath().toString(), Boolean.TRUE);
                    try { sender.sendMessage("Heap dump written to: " + out.toAbsolutePath().toString()); } catch (Throwable ignored) {}
                    return;
                }
            } catch (Throwable ignored) {}
            try { sender.sendMessage("Heap dump not supported on this JVM via in-plugin method. You may use jcmd <pid> GC.heap_dump <file> instead."); } catch (Throwable ignored) {}
        } catch (Throwable t) {
            try { sender.sendMessage("Heap dump failed: " + t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return "heapdump.hprof";
        name = name.replace("..", "_");
        name = name.replace('/', '_').replace('\\', '_');
        name = FILENAME_SAFE.matcher(name).replaceAll("_");
        if (name.length() > 128) name = name.substring(name.length() - 128);
        if (name.isEmpty()) name = "heapdump.hprof";
        return name;
    }

    public static void startAutoJfr(JavaPlugin plugin, double thresholdMspt, int consecutiveSeconds, int recordingSeconds, int checkIntervalSeconds) {
        if (plugin == null) return;
        stopAutoJfr();
        autoJfrConsecutive.set(0);
        long periodTicks = Math.max(1, checkIntervalSeconds) * 20L;
        autoJfrTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                double smoothed = ScaleUtils.getSmoothedMspt();
                if (smoothed >= thresholdMspt) {
                    int c = autoJfrConsecutive.incrementAndGet();
                    if (c * checkIntervalSeconds >= consecutiveSeconds) {
                        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
                        String fn = "auto-jfr-" + stamp + ".jfr";
                        try {
                            CommandSender console = plugin.getServer().getConsoleSender();
                            jfrStart(plugin, fn, console);
                            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                try { jfrStop(plugin, fn, plugin.getServer().getConsoleSender()); } catch (Throwable ignored) {}
                            }, Math.max(1, recordingSeconds) * 20L);
                        } catch (Throwable t) {
                            PowerhouseLogger.warn("Auto-JFR start failed: " + t.getMessage());
                        }
                        autoJfrConsecutive.set(0);
                    }
                } else {
                    autoJfrConsecutive.set(0);
                }
            } catch (Throwable ignored) {}
        }, 0L, periodTicks);
        PowerhouseLogger.info("Auto-JFR monitor started (threshold=" + thresholdMspt + "ms, consecutive=" + consecutiveSeconds + "s)");
    }

    public static void startAutoJfr(JavaPlugin plugin) {
        startAutoJfr(plugin, 48.0, 30, 60, 5);
    }

    public static void stopAutoJfr() {
        try {
            if (autoJfrTask != null) {
                autoJfrTask.cancel();
                autoJfrTask = null;
                PowerhouseLogger.info("Auto-JFR monitor stopped");
            }
        } catch (Throwable ignored) {}
    }

    public static void jfrStart(JavaPlugin plugin, String filename, CommandSender sender) {
        if (currentRecording != null) {
            try { sender.sendMessage("A JFR recording is already active."); } catch (Throwable ignored) {}
            return;
        }
        try {
            Class<?> recCls = Class.forName("jdk.jfr.Recording");
            Object rec = recCls.getConstructor().newInstance();
            try {
                java.lang.reflect.Method setToDisk = recCls.getMethod("setToDisk", boolean.class);
                setToDisk.invoke(rec, Boolean.TRUE);
            } catch (Throwable ignored) {}
            if (filename != null && !filename.isEmpty()) {
                try {
                    java.nio.file.Path out = plugin.getDataFolder().toPath();
                    if (!out.toFile().exists()) out.toFile().mkdirs();
                    java.nio.file.Path dest = out.resolve(filename);
                    java.lang.reflect.Method setDestination = recCls.getMethod("setDestination", java.nio.file.Path.class);
                    setDestination.invoke(rec, dest);
                } catch (Throwable ignored) {}
            }
            java.lang.reflect.Method start = recCls.getMethod("start");
            start.invoke(rec);
            currentRecording = rec;
            sender.sendMessage("JFR recording started (best-effort). Use '/powerhouse jvm jfr stop [file]' to stop and dump.");
        } catch (Throwable t) {
            try { sender.sendMessage("JFR start failed or unsupported on this JVM: " + t.getMessage()); } catch (Throwable ignored) {}
        }
    }

    public static void jfrStop(JavaPlugin plugin, String filename, CommandSender sender) {
        if (currentRecording == null) {
            try { sender.sendMessage("No active JFR recording."); } catch (Throwable ignored) {}
            return;
        }
        try {
            Class<?> recCls = Class.forName("jdk.jfr.Recording");
            Object rec = currentRecording;
            try {
                java.lang.reflect.Method stop = recCls.getMethod("stop");
                stop.invoke(rec);
            } catch (Throwable ignored) {}
            if (filename != null && !filename.isEmpty()) {
                try {
                    java.nio.file.Path out = plugin.getDataFolder().toPath();
                    if (!out.toFile().exists()) out.toFile().mkdirs();
                    java.nio.file.Path dest = out.resolve(filename);
                    try {
                        java.lang.reflect.Method dump = recCls.getMethod("dump", java.nio.file.Path.class);
                        dump.invoke(rec, dest);
                        sender.sendMessage("JFR recording dumped to: " + dest.toAbsolutePath().toString());
                    } catch (NoSuchMethodException ns) {
                        try { java.lang.reflect.Method setDestination = recCls.getMethod("setDestination", java.nio.file.Path.class); setDestination.invoke(rec, dest); } catch (Throwable ignored) {}
                        try { java.lang.reflect.Method close = recCls.getMethod("close"); close.invoke(rec); sender.sendMessage("JFR recording stopped; destination may be " + dest.toAbsolutePath().toString()); } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            } else {
                try { java.lang.reflect.Method close = recCls.getMethod("close"); close.invoke(rec); } catch (Throwable ignored) {}
                sender.sendMessage("JFR recording stopped (no file requested).");
            }
        } catch (Throwable t) {
            try { sender.sendMessage("JFR stop failed: " + t.getMessage()); } catch (Throwable ignored) {}
        } finally {
            currentRecording = null;
        }
    }

    private static String human(long v) {
        if (v < 0) return "-";
        long kb = v / 1024L;
        if (kb < 1024) return kb + " KB";
        long mb = kb / 1024;
        if (mb < 1024) return mb + " MB";
        long gb = mb / 1024;
        return gb + " GB";
    }
}


