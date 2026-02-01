package kaiakk.powerhouse.helpers.internal;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import kaiakk.powerhouse.helpers.logs.PowerhouseLogger;

public class FoliaChecker {
	private static volatile Boolean cached = null;

	public static boolean isFolia(Plugin plugin) {
		if (cached != null) return cached.booleanValue();

		synchronized (FoliaChecker.class) {
			if (cached != null) return cached.booleanValue();
			boolean foliaDetected = false;
			String version = "";
			String bukkitName = "";
			String serverClass = "";
			try {
				version = Bukkit.getVersion().toLowerCase();
			} catch (Throwable ignored) {}
			try { bukkitName = Bukkit.getName().toLowerCase(); } catch (Throwable ignored) {}
			try { serverClass = Bukkit.getServer().getClass().getName().toLowerCase(); } catch (Throwable ignored) {}

			try {
				boolean versionFolia = version.contains("folia") && !version.contains("paper") && !version.contains("spigot") && !version.contains("purpur");
				boolean nameFolia = bukkitName.contains("folia");
				boolean classFolia = serverClass.contains("folia");
				foliaDetected = (versionFolia || nameFolia || classFolia);
			} catch (Throwable ignored) {
				foliaDetected = false;
			}

			try {
				PowerhouseLogger.info("Server version string: " + version);
				PowerhouseLogger.info("Bukkit.getName(): " + bukkitName);
				PowerhouseLogger.info("Server class: " + serverClass);
				PowerhouseLogger.info(foliaDetected ? "Folia detected!" : "Folia not detected!");
			} catch (Throwable ignored) {}

			cached = foliaDetected;
			return foliaDetected;
		}
	}
}
