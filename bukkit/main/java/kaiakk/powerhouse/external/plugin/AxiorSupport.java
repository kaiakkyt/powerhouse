package kaiakk.powerhouse.external.plugin;

public class AxiorSupport {
	private AxiorSupport() {}

	public static boolean isAxiorPresent() {
		try {
			return org.bukkit.Bukkit.getPluginManager().getPlugin("Axior") != null;
		} catch (Throwable t) {
			return false;
		}
	}

	public static void notifyAdminsOfSpike(double mspt) {
		String message = "[Powerhouse] Spike detected: average MSPT=" + String.format("%.2f", mspt) + "ms";

		org.bukkit.plugin.Plugin ax = null;
		try {
			ax = org.bukkit.Bukkit.getPluginManager().getPlugin("Axior");
		} catch (Throwable ignored) {}

		if (ax != null) {
			try {
				try {
					java.lang.reflect.Method getConfig = ax.getClass().getMethod("getConfig");
					Object cfg = getConfig.invoke(ax);
					if (cfg != null) {
						try {
							java.lang.reflect.Method getString = cfg.getClass().getMethod("getString", String.class, String.class);
							Object webhookObj = getString.invoke(cfg, "discord-webhook-url", "");
							String webhook = webhookObj == null ? "" : String.valueOf(webhookObj);
							if (webhook != null && !webhook.trim().isEmpty()) {
								try {
									Class<?> discordCls = Class.forName("kaiakk.axior.integrations.DiscordBukkit");
									java.lang.reflect.Method send = discordCls.getMethod("sendReportAsync", org.bukkit.plugin.java.JavaPlugin.class, String.class, String.class, String.class, String.class, long.class);
									send.invoke(null, ax, webhook, "Server", "Powerhouse", message, System.currentTimeMillis());
								} catch (ClassNotFoundException cnf) {
								}
							}
						} catch (NoSuchMethodException ignored) {}
					}
				} catch (NoSuchMethodException ignored) {}
			} catch (Throwable ignored) {}
		}

			try {
				String safe = message.replace("\"", "\\\"");
				org.bukkit.Bukkit.dispatchCommand(org.bukkit.Bukkit.getConsoleSender(), "axalert " + safe);
			} catch (Throwable ignored) {}

		try {
			for (org.bukkit.entity.Player p : org.bukkit.Bukkit.getOnlinePlayers()) {
				try {
					if (p.hasPermission("axior.admin") || p.hasPermission("axior.owner")) {
						p.sendMessage(message);
					}
				} catch (Throwable ignored) {}
			}
		} catch (Throwable ignored) {}
	}
}
