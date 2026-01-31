package kaiakk.powerhouse.helpers.scaling;

import kaiakk.multimedia.classes.SchedulerHelper;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class DynamicScaler {
    private final Plugin plugin;
    private final kaiakk.powerhouse.data.collectors.MetricCollector metrics;
    private final int pollIntervalTicks;
    private final List<Consumer<Double>> subscribers = new CopyOnWriteArrayList<>();
    private volatile BukkitTask task = null;
    private volatile double lastPublishedScale = Double.NaN;
    private static final double PUBLISH_DELTA = 0.01;

    public DynamicScaler(Plugin plugin, kaiakk.powerhouse.data.collectors.MetricCollector metrics, int pollIntervalTicks) {
        this.plugin = plugin;
        this.metrics = metrics;
        this.pollIntervalTicks = Math.max(1, pollIntervalTicks);
    }

    public void start() {
        if (task != null) return;
        double intervalSeconds = Math.max(0.1, ((double) pollIntervalTicks) / 20.0);
        task = SchedulerHelper.runAsyncTimerSeconds(plugin, new Runnable() {
            public void run() { sampleAndPublish(); }
        }, 0.0, intervalSeconds);
    }

    public void stop() {
        if (task != null) {
            try { SchedulerHelper.cancelTask(task); } catch (Throwable ignored) {}
            task = null;
        }
    }

    public void subscribe(Consumer<Double> c) { if (c != null) subscribers.add(c); }
    public void unsubscribe(Consumer<Double> c) { if (c != null) subscribers.remove(c); }

    private void sampleAndPublish() {
        double raw = 0.0;
        try { raw = metrics == null ? 0.0 : metrics.getAverageMspt(); } catch (Throwable ignored) {}
        ScaleUtils.updateSmoothedMspt(raw);
        double smoothed = ScaleUtils.getSmoothedMspt();
        double scale = ScaleUtils.continuousScaleFromMspt(smoothed);

        if (!Double.isNaN(lastPublishedScale) && Math.abs(scale - lastPublishedScale) < PUBLISH_DELTA) return;
        lastPublishedScale = scale;

        try {
            SchedulerHelper.run(plugin, new Runnable() {
                public void run() {
                    for (Consumer<Double> c : subscribers) {
                        try { c.accept(scale); } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable t) {
            for (Consumer<Double> c : subscribers) {
                try { c.accept(scale); } catch (Throwable ignored) {}
            }
        }
    }
}

