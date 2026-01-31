package kaiakk.powerhouse.calculations;

import kaiakk.powerhouse.data.collectors.MetricCollector;
import kaiakk.powerhouse.helpers.scaling.ScaleUtils;
import kaiakk.powerhouse.world.controllers.DistanceController;

import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import kaiakk.multimedia.classes.SchedulerHelper;

import java.util.concurrent.atomic.AtomicBoolean;

public class DistanceCalculator {
	private final MetricCollector metrics;
	private final DistanceController controller;
	private final Plugin plugin;
	private volatile BukkitTask task = null;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public DistanceCalculator(Plugin plugin, MetricCollector metrics, DistanceController controller) {
		this.plugin = plugin;
		this.metrics = metrics;
		this.controller = controller;
	}

	public void start() {
		if (!running.compareAndSet(false, true)) return;
		final DistanceCalculator self = this;
		task = SchedulerHelper.runAsyncTimerSeconds(plugin, new Runnable() {
			public void run() {
				self.tickAsync();
			}
		}, 5.0, 5.0);
	}

	public void stop() {
		if (!running.compareAndSet(true, false)) return;
		if (task != null) {
			SchedulerHelper.cancelTask(task);
			task = null;
		}
	}

	private void tickAsync() {
		double raw = metrics.getAverageMspt();
		
		double mspt = ScaleUtils.getSmoothedMspt();

		double vel = 0.0;
		try { vel = metrics.getMsptVelocity(); } catch (Throwable ignored) {}

		double s = ScaleUtils.continuousScaleFromMspt(mspt);
		int targetView = ScaleUtils.lerpInt(6, 10, s);
		int targetSim = ScaleUtils.lerpInt(4, 8, s);

		if (vel > 5.0) {
			targetView = Math.min(targetView, 6);
			targetSim = Math.min(targetSim, 4);
		} else if (vel > 2.0) {
			targetView = Math.max(4, targetView - 1);
			targetSim = Math.max(3, targetSim - 1);
		}

		final int fView = targetView;
		final int fSim = targetSim;
		SchedulerHelper.run(plugin, new Runnable() {
			public void run() {
				controller.applyGlobalDistances(fView, fSim);
			}
		});
	}
}


