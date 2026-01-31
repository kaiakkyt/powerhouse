package kaiakk.powerhouse.external.support;

import fi.iki.elonen.NanoHTTPD;
import kaiakk.powerhouse.world.*;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.io.File;
import org.bukkit.configuration.file.YamlConfiguration;

public class WebManager extends NanoHTTPD {
    private final JavaPlugin plugin;
    private final ConcurrentHashMap<String, RollingAverage> regionMsptAverages = new ConcurrentHashMap<>();
    private final boolean isFolia;
    
    private volatile int totalEntities = 0;
    private volatile int totalChunks = 0;
    private volatile int totalPlayers = 0;
    
    private final RollingAverage manualMsptTracker = new RollingAverage(100);
    private volatile long lastTickTime = System.nanoTime();
    
    public WebManager(JavaPlugin plugin, int port) throws IOException {
        super(port);
        this.plugin = plugin;

        this.isFolia = kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(plugin);

        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
        plugin.getLogger().info("NanoHTTPD web server started on port " + port);
        plugin.getLogger().info("Server type detected: " + (isFolia ? "Folia" : "Bukkit (Spigot/Paper/Purpur)"));

        try { ensureWebConfigDefaults(); } catch (Throwable t) { plugin.getLogger().warning("Failed to ensure webconfig defaults: " + t.getMessage()); }

        if (!this.isFolia) {
            try {
                Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                    updateGlobalStats();
                }, 0L, 20L);
                plugin.getLogger().info("Stats updater scheduled (runs every second on main thread)");
            } catch (Throwable t) {
                plugin.getLogger().warning("Failed to schedule Bukkit stats updater: " + t);
            }
        } else {
            plugin.getLogger().info("Folia detected: skipping global timer to avoid async issues");
        }
    }

    private void ensureWebConfigDefaults() {
        try {
            File cfgFile = new File(plugin.getDataFolder(), "webconfig.yml");
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            YamlConfiguration yc = YamlConfiguration.loadConfiguration(cfgFile);
            boolean changed = false;
            if (!yc.contains("autoRefresh")) { yc.set("autoRefresh", true); changed = true; }
            if (!yc.contains("refreshInterval")) { yc.set("refreshInterval", 2); changed = true; }
            if (changed) yc.save(cfgFile);
        } catch (Throwable t) {
            plugin.getLogger().warning("ensureWebConfigDefaults failed: " + t.getMessage());
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static Object parseValue(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.equalsIgnoreCase("true")) return true;
        if (s.equalsIgnoreCase("false")) return false;
        try { if (s.indexOf('.') >= 0) return Double.parseDouble(s); } catch (Throwable ignored) {}
        try { return Integer.parseInt(s); } catch (Throwable ignored) {}
        return s;
    }
    
    private void updateGlobalStats() {
        try {
            totalPlayers = Bukkit.getOnlinePlayers().size();

            if (isFolia) {
                int entities = 0;
                int chunks = 0;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    try {
                        chunks++;
                        entities += 50;
                    } catch (Throwable ignored) {}
                }

                totalEntities = entities;
                totalChunks = chunks;
            } else {
                totalEntities = 0;
                totalChunks = 0;

                for (World world : Bukkit.getWorlds()) {
                    try {
                        int worldEntities = world.getEntities().size();
                        int worldChunks = world.getLoadedChunks().length;
                        totalEntities += worldEntities;
                        totalChunks += worldChunks;
                    } catch (Throwable t) {
                        plugin.getLogger().warning("Error getting world stats: " + t.getMessage());
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Error in updateGlobalStats: " + t.getMessage());
        }
    }
    
    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        
        if (uri.equals("/") || uri.equals("/index.html")) {
            return serveResource("index.html", "text/html");
        } else if (uri.equals("/app.js")) {
            return serveResource("app.js", "application/javascript");
        } else if (uri.equals("/animation.js")) {
            return serveResource("animation.js", "application/javascript");
        } else if (uri.equals("/style.css")) {
            return serveResource("style.css", "text/css");
        } else if (uri.equals("/favicon.ico") || uri.equals("/icon.ico")) {
            return serveResource("icon.ico", "image/x-icon");
        }
        
        if (uri.equals("/api/stats")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", getStatsJson());
        } else if (uri.equals("/api/regions")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", getRegionsJson());
        } else if (uri.equals("/api/players")) {
            return newFixedLengthResponse(Response.Status.OK, "application/json", getPlayersJson());
        } else if (uri.equals("/api/lag-sources")) {
            String base = getLagSourcesJson();
            String withBreakdown = appendPluginBreakdownJson(base);
            return newFixedLengthResponse(Response.Status.OK, "application/json", withBreakdown);
        } else if (uri.equals("/api/settings")) {
                if (session.getMethod() == Method.GET) {
                try {
                    File cfgFile = new File(plugin.getDataFolder(), "webconfig.yml");
                    YamlConfiguration yc = YamlConfiguration.loadConfiguration(cfgFile);
                    Map<String, Object> values = yc.getValues(false);
                    boolean requiresToken = yc.contains("adminToken");
                    StringBuilder sb = new StringBuilder();
                    sb.append("{");
                    boolean first = true;
                    for (Map.Entry<String, Object> e : values.entrySet()) {
                        if ("adminToken".equals(e.getKey())) continue;
                        if (!first) sb.append(",");
                        first = false;
                        Object v = e.getValue();
                        sb.append("\"").append(escapeJson(e.getKey())).append("\":");
                        if (v instanceof Number || v instanceof Boolean) sb.append(v.toString());
                        else sb.append("\"").append(escapeJson(String.valueOf(v))).append("\"");
                    }
                    if (!first) sb.append(",");
                    sb.append("\"requiresAdminToken\":").append(requiresToken ? "true" : "false");
                    sb.append("}");
                    return newFixedLengthResponse(Response.Status.OK, "application/json", sb.toString());
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to read webconfig.yml: " + t.getMessage());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":true}");
                }
            }

            if (session.getMethod() == Method.POST) {
                try {
                    Map<String, String> files = new HashMap<>();
                    session.parseBody(files);
                    Map<String, String> params = session.getParms();

                    Set<String> allowed = new HashSet<>();
                    allowed.add("autoRefresh");
                    allowed.add("refreshInterval");

                    for (String key : params.keySet()) {
                        if (!allowed.contains(key)) {
                            String msg = String.format("{\"error\":\"unknown_key\",\"key\":\"%s\"}", escapeJson(key));
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", msg);
                        }
                    }

                    Object autoRefreshVal = null;
                    Object refreshIntervalVal = null;

                    if (params.containsKey("autoRefresh")) {
                        String v = params.get("autoRefresh");
                        if (v == null) v = "false";
                        if ("true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v)) {
                            autoRefreshVal = Boolean.parseBoolean(v);
                        } else {
                            String msg = "{\"error\":\"invalid_value\",\"key\":\"autoRefresh\",\"reason\":\"must be true or false\"}";
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", msg);
                        }
                    }

                    if (params.containsKey("refreshInterval")) {
                        String v = params.get("refreshInterval");
                        try {
                            int sec = Integer.parseInt(v);
                            if (sec < 1 || sec > 600) {
                                String msg = "{\"error\":\"invalid_value\",\"key\":\"refreshInterval\",\"reason\":\"must be 1-600\"}";
                                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", msg);
                            }
                            refreshIntervalVal = sec;
                        } catch (NumberFormatException nfe) {
                            String msg = "{\"error\":\"invalid_value\",\"key\":\"refreshInterval\",\"reason\":\"must be integer seconds\"}";
                            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", msg);
                        }
                    }

                    File cfgFile = new File(plugin.getDataFolder(), "webconfig.yml");
                    if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
                    YamlConfiguration yc = YamlConfiguration.loadConfiguration(cfgFile);
                    if (autoRefreshVal != null) yc.set("autoRefresh", autoRefreshVal);
                    if (refreshIntervalVal != null) yc.set("refreshInterval", refreshIntervalVal);

                    File tmp = new File(plugin.getDataFolder(), "webconfig.yml.tmp");
                    yc.save(tmp);
                    File backup = new File(plugin.getDataFolder(), "webconfig.yml.bak");
                    if (cfgFile.exists()) {
                        try { backup.delete(); } catch (Throwable ignored) {}
                        try { cfgFile.renameTo(backup); } catch (Throwable ignored) {}
                    }
                    boolean moved = tmp.renameTo(cfgFile);
                    if (!moved) {
                        try (java.io.InputStream in = new java.io.FileInputStream(tmp); java.io.OutputStream out = new java.io.FileOutputStream(cfgFile)) {
                            byte[] buf = new byte[8192]; int len;
                            while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                        } catch (Throwable t) {
                            plugin.getLogger().warning("Failed to persist webconfig.yml: " + t.getMessage());
                            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":true}");
                        }
                        try { tmp.delete(); } catch (Throwable ignored) {}
                    }

                    return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}");
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to save webconfig.yml: " + ex.getMessage());
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "{\"error\":true}");
                }
            }
        }
        
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found");
    }
    
    private Response serveResource(String filename, String mimeType) {
        InputStream is = getClass().getClassLoader().getResourceAsStream("webres/" + filename);
        
        if (is == null) {
            plugin.getLogger().warning("Resource not found: webres/" + filename);
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "404 - Not Found");
        }
        
        return newChunkedResponse(Response.Status.OK, mimeType, is);
    }
    
    private String getStatsJson() {
        AllOptimizations opts = AllOptimizations.getInstance();
        double mspt = 50.0;
        double tps = 20.0;
        
        if (opts != null) {
            if (isFolia) {
                mspt = -1.0;
                tps = -1.0;
            } else {
                mspt = opts.getAverageMspt();
                tps = Math.min(20.0, Math.max(0.0, 1000.0 / Math.max(1.0, mspt)));
            }
        }
        
        long usedMemory = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024 / 1024;
        long maxMemory = Runtime.getRuntime().maxMemory() / 1024 / 1024;

        StringBuilder playersJson = new StringBuilder();
        playersJson.append("[");
        boolean first = true;
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!first) playersJson.append(",");
                first = false;
                int ping = 0;
                try {
                    ping = p.getPing();
                } catch (Throwable t) {
                    try {
                        Object handle = p.getClass().getMethod("getHandle").invoke(p);
                        try {
                            java.lang.reflect.Field f = handle.getClass().getField("ping");
                            ping = f.getInt(handle);
                        } catch (Throwable ignore2) {
                        }
                    } catch (Throwable ignore) {}
                }
                playersJson.append(String.format("{\"name\":\"%s\",\"ping\":%d}", escapeJson(p.getName()), ping));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to build player list JSON: " + t.getMessage());
        }
        playersJson.append("]");

        String msptPart;
        String tpsPart;
        if (mspt < 0.0) {
            msptPart = "null";
            tpsPart = "null";
        } else {
            msptPart = String.format("%.2f", mspt);
            tpsPart = String.format("%.2f", tps);
        }

        return String.format(
            "{\"tps\":%s,\"mspt\":%s,\"entities\":%d,\"chunks\":%d,\"players\":%d,\"playerList\":%s,\"regions\":%d,\"memory\":{\"used\":%d,\"max\":%d}}",
            tpsPart, msptPart, totalEntities, totalChunks, totalPlayers, playersJson.toString(), regionMsptAverages.size(),
            usedMemory, maxMemory
        );
    }
    
    private String getRegionsJson() {
        if (isFolia) {
            return String.format("{\"isFolia\":%s,\"regions\":[]}", isFolia);
        }

        List<String> regionStats = regionMsptAverages.entrySet().stream()
            .map(entry -> {
                double mspt = entry.getValue().getAverage();
                double tps = Math.min(20.0, Math.max(0.0, 1000.0 / Math.max(1.0, mspt)));
                return String.format(
                    "{\"region\":\"%s\",\"mspt\":%.2f,\"tps\":%.2f}",
                    entry.getKey(), mspt, tps
                );
            })
            .collect(Collectors.toList());

        return String.format("{\"isFolia\":%s,\"regions\":[%s]}", isFolia, String.join(",", regionStats));
    }

    private String getLagSourcesJson() {
        if (isFolia) {
            return String.format("{\"isFolia\":%s,\"lagSources\":[]}", isFolia);
        }

        List<String> items = new ArrayList<>();
        try {
            for (Map.Entry<String, RollingAverage> entry : regionMsptAverages.entrySet()) {
                try {
                    String region = entry.getKey();
                    double mspt = entry.getValue().getAverage();
                    double tps = Math.min(20.0, Math.max(0.0, 1000.0 / Math.max(1.0, mspt)));

                    int entities = 0;
                    int chunks = 0;
                    int tileEntities = -1;
                    World w = Bukkit.getWorld(region);
                    if (w != null) {
                        try {
                            entities = w.getEntities().size();
                        } catch (Throwable ignored) { entities = -1; }
                        try {
                            chunks = w.getLoadedChunks().length;
                        } catch (Throwable ignored) { chunks = -1; }
                        try {
                            int teCount = 0;
                            for (org.bukkit.Chunk c : w.getLoadedChunks()) {
                                try {
                                    Object[] tes = null;
                                    try {
                                        tes = c.getTileEntities();
                                    } catch (NoSuchMethodError nsme) {
                                    } catch (Throwable ignore) {}
                                    if (tes != null) teCount += tes.length;
                                } catch (Throwable ignore) {}
                            }
                            tileEntities = teCount;
                        } catch (Throwable ignored) { tileEntities = -1; }
                    }

                    String cause = "Unknown";
                    if (entities > 300) cause = "Entities";
                    else if (tileEntities > 300) cause = "TileEntities";
                    else if (chunks > 800) cause = "Loaded Chunks";
                    else {
                        String rlow = region.toLowerCase();
                        if (rlow.contains("spawn") || rlow.contains("village") || rlow.contains("market")) cause = "Entities";
                    }

                    String item = String.format(Locale.ROOT,
                        "{\"region\":\"%s\",\"mspt\":%.2f,\"tps\":%.2f,\"cause\":\"%s\",\"entities\":%d,\"tileEntities\":%d,\"chunks\":%d}",
                        escapeJson(region), mspt, tps, escapeJson(cause), entities, tileEntities, chunks
                    );
                    items.add(item);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Failed to build lag source entry: " + t.getMessage());
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Error building lag sources JSON: " + t.getMessage());
        }

        return String.format("{\"isFolia\":%s,\"lagSources\":[%s]}", isFolia, String.join(",", items));
    }

    // Return a JSON object with an array `players` describing each online player.
    private String getPlayersJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"players\":[");
        boolean first = true;
        try {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (!first) sb.append(",");
                first = false;
                int ping = 0;
                try {
                    ping = p.getPing();
                } catch (Throwable t) {
                    try {
                        Object handle = p.getClass().getMethod("getHandle").invoke(p);
                        try {
                            java.lang.reflect.Field f = handle.getClass().getField("ping");
                            ping = f.getInt(handle);
                        } catch (Throwable ignore2) {}
                    } catch (Throwable ignore) {}
                }

                String uuid = "";
                try { uuid = p.getUniqueId().toString(); } catch (Throwable ignored) {}
                String world = "";
                int x=0,y=0,z=0;
                try {
                    if (p.getWorld() != null) world = p.getWorld().getName();
                    org.bukkit.Location loc = p.getLocation();
                    if (loc != null) { x = loc.getBlockX(); y = loc.getBlockY(); z = loc.getBlockZ(); }
                } catch (Throwable ignored) {}

                sb.append(String.format(Locale.ROOT,
                    "{\"name\":\"%s\",\"uuid\":\"%s\",\"ping\":%d,\"world\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d}",
                    escapeJson(p.getName()), escapeJson(uuid), ping, escapeJson(world), x, y, z
                ));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Failed to build players JSON: " + t.getMessage());
        }
        sb.append("]}");
        return sb.toString();
    }
    
    // Augment lag sources response with a lightweight sampling-based plugin breakdown
    // This will sample the main server thread for a short period and attribute stack samples to plugins.
    private String appendPluginBreakdownJson(String baseJson) {
        try {
            Map<String, Integer> counts = sampleMainThreadOwners(200, 25); // 200ms @ 25ms -> ~8 samples
            int total = 0;
            for (int v : counts.values()) total += v;
            if (total == 0) return baseJson;
            List<Map.Entry<String,Integer>> list = new ArrayList<>(counts.entrySet());
            list.sort((a,b) -> Integer.compare(b.getValue(), a.getValue()));
            StringBuilder sb = new StringBuilder();
            sb.append(baseJson.substring(0, baseJson.length()-1)); // drop trailing }
            sb.append(",\"pluginBreakdown\":[");
            boolean first = true;
            for (Map.Entry<String,Integer> e : list) {
                if (!first) sb.append(','); first = false;
                double pct = (e.getValue() * 100.0) / (double) Math.max(1, total);
                sb.append(String.format(Locale.ROOT, "{\"plugin\":\"%s\",\"percent\":%.1f,\"samples\":%d}", escapeJson(e.getKey()), pct, e.getValue()));
            }
            sb.append("]}");
            return sb.toString();
        } catch (Throwable t) {
            plugin.getLogger().warning("appendPluginBreakdownJson failed: " + t.getMessage());
            return baseJson;
        }
    }
    
    public void shutdown() {
        stop();
        plugin.getLogger().info("Web server stopped");
    }
    
    private static class RollingAverage {
        private final double[] values;
        private int index = 0;
        private int count = 0;
        
        RollingAverage(int size) {
            this.values = new double[size];
        }
        
        void add(double value) {
            values[index] = value;
            index = (index + 1) % values.length;
            if (count < values.length) count++;
        }
        
        double getAverage() {
            if (count == 0) return 0.0;
            double sum = 0;
            for (int i = 0; i < count; i++) {
                sum += values[i];
            }
            return sum / count;
        }
    }

    // Sampling profiler helpers: attribute stack frames to plugins by checking plugin ClassLoaders for the class resource.
    private Map<String, Integer> sampleMainThreadOwners(int durationMs, int intervalMs) {
        Map<String, Integer> counts = new HashMap<>();
        int samples = Math.max(1, durationMs / Math.max(1, intervalMs));

        // Build plugin classloader map once
        Map<ClassLoader, String> loaderToPlugin = new HashMap<>();
        try {
            org.bukkit.plugin.Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            for (org.bukkit.plugin.Plugin p : plugins) {
                if (p == null) continue;
                ClassLoader cl = p.getClass().getClassLoader();
                if (cl != null) loaderToPlugin.put(cl, p.getName());
            }
        } catch (Throwable ignored) {}

        Thread target = findServerThread();
        if (target == null) {
            counts.put("server", samples);
            return counts;
        }

        for (int i = 0; i < samples; i++) {
            try {
                Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
                StackTraceElement[] st = all.get(target);
                if (st == null || st.length == 0) {
                    // try again
                    Thread.sleep(Math.max(1, intervalMs));
                    continue;
                }

                // find first meaningful frame (skip jvm, java, net.minecraft/core server internals)
                String owner = "server";
                for (StackTraceElement e : st) {
                    String cls = e.getClassName();
                    if (cls == null) continue;
                    String low = cls.toLowerCase();
                    if (low.startsWith("java.") || low.startsWith("jdk.") || low.startsWith("sun.") || low.startsWith("com.sun.")) continue;
                    if (low.startsWith("net.minecraft") || low.startsWith("org.bukkit") || low.startsWith("org.spigot") || low.startsWith("org.pf4j")) continue;

                    // try to attribute via plugin classloaders: check resource existence
                    String path = cls.replace('.', '/') + ".class";
                    boolean found = false;
                    for (Map.Entry<ClassLoader, String> en : loaderToPlugin.entrySet()) {
                        try {
                            java.net.URL res = en.getKey().getResource(path);
                            if (res != null) {
                                owner = en.getValue();
                                found = true;
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                    if (found) break;

                    // fallback: if class appears to be from a plugin package (contains lowercase plugin-like prefix), attribute to that package
                    if (!low.startsWith("net.minecraft") && !low.startsWith("org.bukkit") && low.contains(".")) {
                        owner = cls.split("\\.")[0];
                        break;
                    }
                }

                counts.put(owner, counts.getOrDefault(owner, 0) + 1);
            } catch (Throwable t) {
                // ignore sampling errors
            }

            try { Thread.sleep(Math.max(1, intervalMs)); } catch (InterruptedException ignored) {}
        }

        return counts;
    }

    private Thread findServerThread() {
        try {
            Map<Thread, StackTraceElement[]> all = Thread.getAllStackTraces();
            for (Thread t : all.keySet()) {
                if (t == null) continue;
                String name = t.getName();
                if (name != null && name.toLowerCase().contains("server")) return t;
            }
            // fallback: find thread whose stack contains net.minecraft or org.bukkit
            for (Map.Entry<Thread, StackTraceElement[]> en : Thread.getAllStackTraces().entrySet()) {
                StackTraceElement[] st = en.getValue();
                if (st == null) continue;
                for (StackTraceElement e : st) {
                    String cls = e.getClassName();
                    if (cls == null) continue;
                    String low = cls.toLowerCase();
                    if (low.startsWith("net.minecraft") || low.startsWith("org.bukkit") || low.startsWith("org.spigot")) return en.getKey();
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
