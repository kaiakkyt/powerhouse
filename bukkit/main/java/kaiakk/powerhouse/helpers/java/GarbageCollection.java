package kaiakk.powerhouse.helpers.java;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GarbageCollection {

	private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "Powerhouse-SoftGC-Scheduler");
		t.setDaemon(true);
		return t;
	});

	private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
	private static final AtomicBoolean JOB_IN_PROGRESS = new AtomicBoolean(false);
	private static ScheduledFuture<?> monitorTask;
	private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();
	private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();
	private static volatile long lastJobEndTime = 0L;
	private static volatile long jobCooldownMillis = 0L;

	public static String getDetectedGCNames() {
		StringBuilder sb = new StringBuilder();
		for (GarbageCollectorMXBean g : GC_BEANS) {
			if (sb.length() > 0) sb.append(',');
			sb.append(g.getName());
		}
		return sb.toString();
	}

	public static boolean isLowLatencyGC() {
		for (GarbageCollectorMXBean g : GC_BEANS) {
			String n = g.getName();
			if (n == null) continue;
			String up = n.toUpperCase();
			if (up.contains("ZGC") || up.contains("SHENANDOAH")) return true;
		}
		return false;
	}

	private GarbageCollection() {}

	public static synchronized void startAutoSoftGC(long checkIntervalMillis, double usageThreshold, int passes, long passIntervalMillis) {
		startAutoSoftGC(checkIntervalMillis, usageThreshold, passes, passIntervalMillis, 0L);
	}

	public static synchronized void startAutoSoftGC(long checkIntervalMillis, double usageThreshold, int passes, long passIntervalMillis, long cooldownMillis) {
		if (RUNNING.get()) return;
		RUNNING.set(true);
		jobCooldownMillis = Math.max(0L, cooldownMillis);

		monitorTask = SCHEDULER.scheduleAtFixedRate(() -> {
			try {
				if (JOB_IN_PROGRESS.get()) return;

				long now = System.currentTimeMillis();
				if (now - lastJobEndTime < jobCooldownMillis) return;

				MemoryUsage heap = MEMORY_BEAN.getHeapMemoryUsage();
				long used = heap.getUsed();
				long max = heap.getMax();
				double usage = max > 0 ? (double) used / max : 0.0;

				if (usage >= usageThreshold) {
					if (!JOB_IN_PROGRESS.getAndSet(true)) {
						final int p = Math.max(1, passes);
						for (int i = 0; i < p; i++) {
							final int idx = i;
							SCHEDULER.schedule(() -> {
								try {
									System.gc();
								} finally {
									if (idx == p - 1) {
										lastJobEndTime = System.currentTimeMillis();
										JOB_IN_PROGRESS.set(false);
									}
								}
							}, i * Math.max(1, passIntervalMillis), TimeUnit.MILLISECONDS);
						}
					}
				}
			} catch (Throwable t) {
				t.printStackTrace();
			}
		}, 0, Math.max(1, checkIntervalMillis), TimeUnit.MILLISECONDS);
	}

	public static synchronized void stopAutoSoftGC() {
		if (!RUNNING.get()) return;
		RUNNING.set(false);
		if (monitorTask != null) monitorTask.cancel(true);
	}

	public static void triggerSoftGC(int passes, long passIntervalMillis) {
		if (JOB_IN_PROGRESS.getAndSet(true)) return;

		final int p = Math.max(1, passes);
		for (int i = 0; i < p; i++) {
			final int idx = i;
			SCHEDULER.schedule(() -> {
				try {
					System.gc();
				} finally {
					if (idx == p - 1) {
						lastJobEndTime = System.currentTimeMillis();
						JOB_IN_PROGRESS.set(false);
					}
				}
			}, i * Math.max(1, passIntervalMillis), TimeUnit.MILLISECONDS);
		}
	}

	public static boolean isRunning() { return RUNNING.get(); }
	public static boolean isJobInProgress() { return JOB_IN_PROGRESS.get(); }

}
