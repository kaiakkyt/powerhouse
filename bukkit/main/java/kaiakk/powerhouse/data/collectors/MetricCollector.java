package kaiakk.powerhouse.data.collectors;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import kaiakk.multimedia.classes.SchedulerHelper;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Method;

public class MetricCollector {
    private final JavaPlugin plugin;
    private final ConcurrentLinkedDeque<Long> intervalTickNanos = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Long> activeTickNanos = new ConcurrentLinkedDeque<>();
    private final int maxSamples = 120;
    private volatile long lastNanos = -1;
    private volatile BukkitTask task = null;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private static Method cachedGetTps = null;
    private static Method cachedGetTickTimes = null;
    private volatile String lastSampleSource = "interval";

    static {
        try {
            Object server = Bukkit.getServer();
            Class<?> cls = server.getClass();
            try {
                cachedGetTickTimes = cls.getMethod("getTickTimes");
                cachedGetTickTimes.setAccessible(true);
            } catch (Throwable ignored) {}
            try {
                cachedGetTps = cls.getMethod("getTPS");
                cachedGetTps.setAccessible(true);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    public MetricCollector(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        lastNanos = System.nanoTime();
        final MetricCollector self = this;
        task = SchedulerHelper.runTimer(plugin, new Runnable() {
            public void run() {
                self.onTick();
            }
        }, 1L, 1L);
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) return;
        if (task != null) {
            SchedulerHelper.cancelTask(task);
            task = null;
        }
        intervalTickNanos.clear();
        activeTickNanos.clear();
    }

    private void onTick() {
        long now = System.nanoTime();

        if (lastNanos > 0) {
            long delta = now - lastNanos;
            intervalTickNanos.addLast(delta);
            while (intervalTickNanos.size() > maxSamples) intervalTickNanos.pollFirst();
        }

        boolean recordedActive = false;
        if (cachedGetTickTimes != null) {
            try {
                Object server = Bukkit.getServer();
                Object res = cachedGetTickTimes.invoke(server);
                if (res instanceof long[]) {
                    long[] arr = (long[]) res;
                    if (arr.length > 0) {
                        long latest = arr[arr.length - 1];
                        activeTickNanos.addLast(latest);
                        recordedActive = true;
                        while (activeTickNanos.size() > maxSamples) activeTickNanos.pollFirst();
                    }
                } else if (res instanceof double[]) {
                    double[] arr = (double[]) res;
                    if (arr.length > 0) {
                        long latest = (long) arr[arr.length - 1];
                        activeTickNanos.addLast(latest);
                        recordedActive = true;
                        while (activeTickNanos.size() > maxSamples) activeTickNanos.pollFirst();
                    }
                }
            } catch (Throwable ignored) {}
        }

        lastSampleSource = recordedActive ? "tickTimes" : "interval";
        lastNanos = now;
    }

    public double getAverageMspt() {
        if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return -1.0;

        double act = getActiveMspt();
        if (act >= 0.0) return act;

        double interval = getIntervalMspt();
        if (interval >= 0.0) return interval;

        if (cachedGetTickTimes != null) {
            try {
                Object server = Bukkit.getServer();
                Object res = cachedGetTickTimes.invoke(server);
                if (res instanceof long[]) {
                    long[] arr = (long[]) res;
                    if (arr.length > 0) {
                        long s2 = 0L;
                        for (long v : arr) s2 += v;
                        double avgNanos = (double) s2 / arr.length;
                        return avgNanos / 1_000_000.0;
                    }
                } else if (res instanceof double[]) {
                    double[] arr = (double[]) res;
                    if (arr.length > 0) {
                        double sumD = 0.0;
                        for (double v : arr) sumD += v;
                        double avg = sumD / arr.length;
                        if (avg > 1000.0) return avg / 1_000_000.0;
                        return avg;
                    }
                }
            } catch (Throwable ignored) {}
        }

        if (cachedGetTps != null) {
            try {
                Object server = Bukkit.getServer();
                Object res = cachedGetTps.invoke(server);
                if (res instanceof double[]) {
                    double[] arr = (double[]) res;
                    if (arr.length > 0 && arr[0] > 0) return 1000.0 / arr[0];
                } else if (res instanceof Double) {
                    Double tps = (Double) res;
                    if (tps > 0) return 1000.0 / tps;
                }
            } catch (Throwable ignored) {}
        }

        return 50.0;
    }

    public double getActiveMspt() {
        long sum = 0L;
        int count = 0;
        if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return -1.0;
        for (Long v : activeTickNanos) {
            if (v == null) continue;
            sum += v.longValue();
            count++;
        }
        if (count > 0) {
            double avgNanos = (double) sum / count;
            return avgNanos / 1_000_000.0;
        }
        return -1.0;
    }

    public double getIntervalMspt() {
        long sum = 0L;
        int count = 0;
        if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return -1.0;
        for (Long v : intervalTickNanos) {
            if (v == null) continue;
            sum += v.longValue();
            count++;
        }
        if (count > 0) {
            double avgNanos = (double) sum / count;
            return avgNanos / 1_000_000.0;
        }
        return -1.0;
    }

    public String getLastSampleSource() { return lastSampleSource; }

    public double getMsptVelocity() {
        
        final int MAX_LOOK = 12;
        long[] buf = new long[MAX_LOOK];
        int k = 0;
        java.util.Iterator<Long> it = null;
        if (activeTickNanos.size() >= 6) {
            it = activeTickNanos.descendingIterator();
        } else {
            it = intervalTickNanos.descendingIterator();
        }
        while (it.hasNext() && k < MAX_LOOK) {
            Long v = it.next();
            if (v == null) continue;
            buf[k++] = v.longValue();
        }
        if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin)) return 0.0;
        if (k < 6) return 0.0;
        int recent = Math.min(3, k / 2);
        double sumRecent = 0.0;
        double sumPrev = 0.0;
        
        for (int i = 0; i < recent; i++) sumRecent += buf[i];
        for (int i = recent; i < 2 * recent; i++) sumPrev += buf[i];
        double avgRecent = (sumRecent / recent) / 1_000_000.0;
        double avgPrev = (sumPrev / recent) / 1_000_000.0;
        return avgRecent - avgPrev;
    }
}


