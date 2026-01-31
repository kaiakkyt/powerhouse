package kaiakk.powerhouse.data.collectors;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;

public class HardwareStats {

	private int logicalCores = -1;
	private int physicalCores = -1;
	private double cpuUsagePercent = -1.0;

	private long ramAllocatedBytes = 0L;
	private long ramUsedBytes = 0L;
	private long ramMaxBytes = 0L;

	private long serverFolderBytes = 0L;

	public HardwareStats() {}

	public void setLogicalCores(int c) { this.logicalCores = c; }
	public int getLogicalCores() { return this.logicalCores; }

	public void setPhysicalCores(int c) { this.physicalCores = c; }
	public int getPhysicalCores() { return this.physicalCores; }

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

	public void detectPhysicalCores() {
		int physical = -1;
		try {
			String os = System.getProperty("os.name").toLowerCase();
			if (os.contains("win")) {
				try {
					ProcessBuilder pb = new ProcessBuilder("wmic", "cpu", "get", "NumberOfCores,NumberOfLogicalProcessors", "/format:csv");
					pb.redirectErrorStream(true);
					Process p = pb.start();
					BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
					String line; String last = null;
					while ((line = br.readLine()) != null) {
						line = line.trim();
						if (line.isEmpty()) continue;
						if (line.toLowerCase().startsWith("node")) continue;
						last = line;
					}
					p.waitFor();
					if (last != null) {
						String[] parts = last.split(",");
						if (parts.length >= 3) {
							try { physical = Integer.parseInt(parts[1].trim()); } catch (Throwable ignored) {}
						}
					}
				} catch (Throwable ignored) {}
			} else if (os.contains("linux")) {
				try {
					List<String> lines = Files.readAllLines(Paths.get("/proc/cpuinfo"));
					Integer coresPer = null;
					java.util.Set<String> physIds = new HashSet<>();
					for (String l : lines) {
						String s = l.toLowerCase();
						if (s.startsWith("cpu cores")) {
							int idx = s.indexOf(':');
							if (idx >= 0) {
								try { coresPer = Integer.parseInt(s.substring(idx+1).trim()); } catch (Throwable ignored) {}
							}
						}
						if (s.startsWith("physical id")) {
							int idx = s.indexOf(':');
							if (idx >= 0) physIds.add(s.substring(idx+1).trim());
						}
					}
					if (coresPer != null && !physIds.isEmpty()) physical = coresPer * physIds.size();
					else if (coresPer != null) physical = coresPer;
					else if (!physIds.isEmpty()) physical = physIds.size();
				} catch (Throwable ignored) {}
			} else if (os.contains("mac") || os.contains("darwin")) {
				try {
					ProcessBuilder pb = new ProcessBuilder("sysctl", "-n", "hw.physicalcpu");
					pb.redirectErrorStream(true);
					Process p = pb.start();
					BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
					String line = br.readLine();
					p.waitFor();
					if (line != null) {
						try { physical = Integer.parseInt(line.trim()); } catch (Throwable ignored) {}
					}
				} catch (Throwable ignored) {}
			}
		} catch (Throwable ignored) {}
		this.physicalCores = physical;
	}

}
