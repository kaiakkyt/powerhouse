package kaiakk.powerhouse.data;

public class HardwareStats {

	private int logicalCores = -1;
	private double cpuUsagePercent = -1.0;

	private long ramAllocatedBytes = 0L;
	private long ramUsedBytes = 0L;
	private long ramMaxBytes = 0L;

	private long serverFolderBytes = 0L;

	public HardwareStats() {}

	public void setLogicalCores(int c) { this.logicalCores = c; }
	public int getLogicalCores() { return this.logicalCores; }

	public void setCpuUsagePercent(double p) { this.cpuUsagePercent = p; }
	public double getCpuUsagePercent() { return this.cpuUsagePercent; }

	public void setRamAllocatedBytes(long v) { this.ramAllocatedBytes = v; }
	public void setRamUsedBytes(long v) { this.ramUsedBytes = v; }
	public void setRamMaxBytes(long v) { this.ramMaxBytes = v; }

	public double getRamAllocatedMB() { return this.ramAllocatedBytes / (1024.0 * 1024.0); }
	public double getRamUsedMB() { return this.ramUsedBytes / (1024.0 * 1024.0); }
	public double getRamMaxMB() { return this.ramMaxBytes / (1024.0 * 1024.0); }

	public void setServerFolderBytes(long v) { this.serverFolderBytes = v; }
	public long getServerFolderBytes() { return this.serverFolderBytes; }
	public double getServerFolderMB() { return this.serverFolderBytes / (1024.0 * 1024.0); }

}
