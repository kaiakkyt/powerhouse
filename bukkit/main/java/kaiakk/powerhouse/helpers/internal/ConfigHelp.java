package kaiakk.powerhouse.helpers.internal;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;

public final class ConfigHelp {
    private static JavaPlugin plugin;

    public static void init(JavaPlugin p) {
        plugin = p;
    }

    public static boolean getBoolean(String path, boolean def) {
        try { if (plugin == null) return def; return plugin.getConfig().getBoolean(path, def); } catch (Throwable t) { return def; }
    }

    public static int getInt(String path, int def) {
        try { if (plugin == null) return def; return plugin.getConfig().getInt(path, def); } catch (Throwable t) { return def; }
    }

    public static String getString(String path, String def) {
        try { if (plugin == null) return def; String v = plugin.getConfig().getString(path); return v == null ? def : v; } catch (Throwable t) { return def; }
    }

    public static double getDouble(String path, double def) {
        try { if (plugin == null) return def; return plugin.getConfig().getDouble(path, def); } catch (Throwable t) { return def; }
    }

    public static void ensureDefaults(Map<String, Object> defaults) {
        try {
            if (plugin == null || defaults == null) return;
            FileConfiguration cfg = plugin.getConfig();
            boolean changed = false;
            for (Map.Entry<String, Object> e : defaults.entrySet()) {
                if (!cfg.contains(e.getKey())) { cfg.set(e.getKey(), e.getValue()); changed = true; }
            }
            if (changed) plugin.saveConfig();
        } catch (Throwable ignored) {}
    }

    public static void save() {
        try { if (plugin != null) plugin.saveConfig(); } catch (Throwable ignored) {}
    }

    public static void reload() {
        try { if (plugin != null) plugin.reloadConfig(); } catch (Throwable ignored) {}
    }

    public static void shutdown() {
        try { plugin = null; } catch (Throwable ignored) {}
    }
}
