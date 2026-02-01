package kaiakk.powerhouse.helpers.java;

import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class JavaSpike {
	private final long pollIntervalMs;
	private final long gcSpikeThresholdMs;

	private final AtomicLong lastCollectionTime = new AtomicLong(0);
	private final AtomicLong lastSpikeTime = new AtomicLong(0);
	private final AtomicLong lastSpikeDuration = new AtomicLong(0);

	private static final int ROLLING_SIZE = 10;
	private final long[] rollingSpikes = new long[ROLLING_SIZE];
	private int rollingIndex = 0;
	private int rollingCount = 0;

	private volatile GCSpikeListener spikeListener = null;
	private volatile boolean firstSpikeLogged = false;

	public interface GCSpikeListener {
		void onGCSpike(long durationMs, double rollingAvgMs);
	}

	public JavaSpike() {
		this(100, 100);
	}

	public JavaSpike(long pollIntervalMs, long gcSpikeThresholdMs) {
		this.pollIntervalMs = pollIntervalMs;
		this.gcSpikeThresholdMs = gcSpikeThresholdMs;
		Thread t = new Thread(this::pollGC, "Powerhouse-GC-Spike-Detector");
		t.setDaemon(true);
		t.start();
	}

	public void setSpikeListener(GCSpikeListener listener) {
		this.spikeListener = listener;
	}

	public void resetRollingSpikes() {
		synchronized (rollingSpikes) {
			for (int i = 0; i < ROLLING_SIZE; i++) rollingSpikes[i] = 0;
			rollingIndex = 0;
			rollingCount = 0;
		}
	}

	public double getRollingAverageSpike() {
		int count = rollingCount;
		if (count == 0) return 0.0;
		long sum = 0;
		for (int i = 0; i < count; i++) sum += rollingSpikes[i];
		return sum / (double) count;
	}

	private void pollGC() {
		List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getGarbageCollectorMXBeans();
		int logCounter = 0;
		while (!Thread.currentThread().isInterrupted()) {
			long totalCollectionTime = 0;
			for (GarbageCollectorMXBean bean : gcBeans) {
				totalCollectionTime += bean.getCollectionTime();
			}
			long prev = lastCollectionTime.getAndSet(totalCollectionTime);
			long delta = totalCollectionTime - prev;
			if (prev > 0 && delta > gcSpikeThresholdMs) {
				lastSpikeTime.set(System.currentTimeMillis());
				lastSpikeDuration.set(delta);
				rollingSpikes[rollingIndex] = delta;
				rollingIndex = (rollingIndex + 1) % ROLLING_SIZE;
				if (rollingCount < ROLLING_SIZE) rollingCount++;
				double rollingAvg = getRollingAverageSpike();
				if (!firstSpikeLogged) {
					System.out.println("[Powerhouse] GC lag spike detected: " + delta + "ms (rolling avg: " + String.format("%.1f", rollingAvg) + "ms)");
					firstSpikeLogged = true;
				}
				if (spikeListener != null) {
					spikeListener.onGCSpike(delta, rollingAvg);
				}
			}
			if (++logCounter >= 100) {
				double rollingAvg = getRollingAverageSpike();
				System.out.println("[Powerhouse] GC rolling average: " + String.format("%.1f", rollingAvg) + "ms");
				logCounter = 0;
			}
			try {
				Thread.sleep(pollIntervalMs);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public long getLastSpikeTime() {
		return lastSpikeTime.get();
	}

	public long getLastSpikeDuration() {
		return lastSpikeDuration.get();
	}
}
