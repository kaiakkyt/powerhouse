package kaiakk.powerhouse.external;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ProxyAwareness {
    private ProxyAwareness() {}

    private static final Set<String> detectedChannels = ConcurrentHashMap.newKeySet();
    private static volatile boolean bungeeDetected = false;
    private static volatile boolean velocityDetected = false;
    private static PluginMessageListener listener = null;

    public static boolean isProxyPresent() {
        return isBungeeConfigured() || isVelocityConfigured();
    }

    public static boolean isBungeeConfigured() {
        try {
            return Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false);
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isVelocityConfigured() {
        try {
            Class<?> configClass = Class.forName("io.papermc.paper.configuration.GlobalConfiguration");
            Object config = configClass.getMethod("get").invoke(null);
            Object proxies = config.getClass().getField("proxies").get(config);
            Object velocity = proxies.getClass().getField("velocity").get(proxies);
            return (boolean) velocity.getClass().getField("enabled").get(velocity);
        } catch (Throwable t) {
            try {
                Class<?> paperConfig = Class.forName("com.destroystokyo.paper.PaperConfig");
                return paperConfig.getField("velocitySupport").getBoolean(null);
            } catch (Throwable ignored) {
                return false;
            }
        }
    }

    public static String getDetectedProxyType() {
        if (bungeeDetected && velocityDetected) return "MIXED";
        if (velocityDetected) return "VELOCITY";
        if (bungeeDetected) return "BUNGEE";
        return isProxyPresent() ? "CONFIGURED_ONLY" : "NONE";
    }


    public static void startListening(Plugin plugin) {
        if (listener != null) return;

        listener = (channel, player, message) -> {
            detectedChannels.add(channel);
            
            if (channel.equals("minecraft:brand") || channel.equals("MC|Brand")) {
                String brand = new String(message, StandardCharsets.UTF_8).toLowerCase();
                if (brand.contains("velocity")) velocityDetected = true;
                if (brand.contains("bungeecord") || brand.contains("waterfall")) bungeeDetected = true;
            }
        };

        String[] channels = {"BungeeCord", "bungeecord:main", "minecraft:brand", "MC|Brand"};
        for (String ch : channels) {
            Bukkit.getMessenger().registerIncomingPluginChannel(plugin, ch, listener);
        }
    }

    public static void stopListening(Plugin plugin) {
        if (listener == null) return;
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin);
        listener = null;
    }

    public static Set<String> getDetectedChannels() {
        return Collections.unmodifiableSet(detectedChannels);
    }
}
