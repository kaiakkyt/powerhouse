package kaiakk.powerhouse;

import org.bukkit.plugin.java.JavaPlugin;
import java.io.File;
import kaiakk.multimedia.classes.*;

import kaiakk.powerhouse.calculations.*;
import kaiakk.powerhouse.data.*;
import kaiakk.powerhouse.external.*;
import kaiakk.powerhouse.external.support.WebManager;
import kaiakk.powerhouse.helpers.*;
import kaiakk.powerhouse.helpers.internal.ConfigHelp;
import kaiakk.powerhouse.helpers.java.*;
import kaiakk.powerhouse.helpers.logs.PowerhouseLogger;
import kaiakk.powerhouse.world.*;

public final class Powerhouse extends JavaPlugin {

    private AllOptimizations optimizations;
    private kaiakk.powerhouse.world.controllers.ServerController serverController;
    private kaiakk.powerhouse.world.explosion.ExplosionCanceller explosionCanceller;
    private kaiakk.powerhouse.world.entity.EntityCulling entityCulling;
    private kaiakk.powerhouse.world.entity.AiManagement aiManagement;
    private kaiakk.powerhouse.world.physics.ItemRemover itemRemover;
    private WebManager webManager;
    private org.bukkit.scheduler.BukkitTask leakPreventionTask;
    private static Powerhouse INSTANCE = null;

    @Override
    public void onEnable() {
        INSTANCE = this;


        ConfigHelp.init(this);

        java.util.Map<String, Object> defaults = new java.util.HashMap<>();
        defaults.put("enabled", true);
        defaults.put("item-merging.enabled", true);
        defaults.put("item-merging.interval-seconds", 3);
        defaults.put("redstone-culling.enabled", true);
        defaults.put("redstone-culling.max-updates-per-second", 200);
        defaults.put("item-hiding.distance", 16.0);
        defaults.put("item-hiding.velocity-threshold", 5.0);
        defaults.put("web-server.enabled", true);
        defaults.put("web-server.port", 8080);

        if (!ConfigHelp.getBoolean("enabled", true)) {
            PowerhouseLogger.error("Powerhouse is disabled in the config. Disabling plugin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ConfigHelp.ensureDefaults(defaults);

        CommandHelper.register(this, "powerhouse", (sender, label, args) -> {
            try {
                if (args.length > 0) {
                    String sub = args[0].toLowerCase();
                    switch (sub) {
                        case "stats":
                            return handleStats(sender);
                        case "reload":
                            return handleReload(sender);
                        case "mspt":
                            return handleMspt(sender);
                        case "debug":
                            return handleDebug(sender, args);
                        case "isculled":
                            return handleIsCulled(sender, args);
                        case "ai":
                            return handleAi(sender, args);
                        case "jvm":
                            return handleJvm(sender, args);
                        case "purge":
                            return handlePurge(sender, args);
                        case "web":
                            return handleWeb(sender, args);
                        case "hardware":
                            return handleHardware(sender, args);
                        default:
                            break;
                    }
                }
            } catch (Throwable t) {
                PowerhouseLogger.error("Error handling /powerhouse command: " + t.getMessage());
            }
            sender.sendMessage(ColorConverter.colorize("&a&lPowerhouse &7v" + this.getDescription().getVersion()));
            sender.sendMessage(ColorConverter.colorize("&7High-performance optimization plugin!"));
            return true;
        });

        try {
            TabCompleter.register(this, "powerhouse", (sender, args) -> {
                java.util.List<String> suggestions = new java.util.ArrayList<>();
                String[] subs = new String[]{"stats","mspt","debug","isculled","ai","jvm","purge","web","reload","hardware"};
                if (args.length == 0 || args.length == 1) {
                    String prefix = (args.length == 0) ? "" : args[0].toLowerCase();
                    for (String s : subs) if (s.startsWith(prefix)) suggestions.add(s);
                    return suggestions;
                }

                if (args.length == 2) {
                    String first = args[0].toLowerCase();
                    if ("purge".equals(first)) {
                        String prefix = args[1].toLowerCase();
                        if ("items".startsWith(prefix)) suggestions.add("items");
                        if ("mobs".startsWith(prefix)) suggestions.add("mobs");
                        return suggestions;
                    }
                    if ("webhook".equals(first)) {
                        if ("test".startsWith(args[1].toLowerCase())) suggestions.add("test");
                        return suggestions;
                    }
                    if ("jvm".equals(first)) {
                        String prefix = args[1].toLowerCase();
                        if ("status".startsWith(prefix)) suggestions.add("status");
                        if ("gc".startsWith(prefix)) suggestions.add("gc");
                        if ("heapdump".startsWith(prefix)) suggestions.add("heapdump");
                        if ("jfr".startsWith(prefix)) suggestions.add("jfr");
                        return suggestions;
                    }
                    if ("ai".equals(first)) {
                        try {
                            return PlayerGather.getOnlinePlayerNames().stream()
                                .filter(n -> n.toLowerCase().startsWith(args[1].toLowerCase()))
                                .limit(20)
                                .collect(java.util.stream.Collectors.toList());
                        } catch (Throwable t) {
                        }
                        return suggestions;
                    
                    }
                    if ("hardware".equals(first)) {
                        String prefix = args[1].toLowerCase();
                        if ("cpu".startsWith(prefix)) suggestions.add("cpu");
                        if ("ram".startsWith(prefix)) suggestions.add("ram");
                        if ("storage".startsWith(prefix)) suggestions.add("storage");
                        return suggestions;
                    }
                }

                if (args.length == 5 && "isculled".equalsIgnoreCase(args[0])) {
                    for (org.bukkit.World w : getServer().getWorlds()) {
                        if (w.getName().startsWith(args[4])) suggestions.add(w.getName());
                    }
                    return suggestions;
                }

                return suggestions;
            });
        } catch (Throwable t) {
        }

        ConsoleLog.init(this);
        PowerhouseLogger.info("Powerhouse is running on version " + this.getDescription().getVersion());
        try { kaiakk.powerhouse.external.support.ProxyAwareness.startListening(this); } catch (Throwable ignored) {}
        
        optimizations = new AllOptimizations(this);
        optimizations.start();

        try {
            try {
                explosionCanceller = new kaiakk.powerhouse.world.explosion.ExplosionCanceller(this, 20, 5000L, new java.util.function.DoubleSupplier() {
                    public double getAsDouble() { return optimizations.getAverageMspt(); }
                });
                explosionCanceller.start();
            } catch (Throwable ignored) {}

            try {
                entityCulling = new kaiakk.powerhouse.world.entity.EntityCulling(this, 16, 64.0, 10.0);
                entityCulling.start();
            } catch (Throwable ignored) {}

            try {
                aiManagement = new kaiakk.powerhouse.world.entity.AiManagement(new java.util.function.DoubleSupplier() {
                    public double getAsDouble() { return optimizations.getAverageMspt(); }
                });
                aiManagement.start(this);
                try { optimizations.registerScalable(aiManagement); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}
            try {
                itemRemover = new kaiakk.powerhouse.world.physics.ItemRemover(this);
                itemRemover.start();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        try {
            serverController = new kaiakk.powerhouse.world.controllers.ServerController(this);
            serverController.start();
        } catch (Throwable ignored) {}
        
        try {
            boolean webEnabled = ConfigHelp.getBoolean("web-server.enabled", true);
            if (webEnabled) {
                int webPort = ConfigHelp.getInt("web-server.port", 8080);
                boolean portInUse = false;
                try (java.net.ServerSocket ss = new java.net.ServerSocket()) {
                    ss.setReuseAddress(true);
                    ss.bind(new java.net.InetSocketAddress("0.0.0.0", webPort));
                } catch (java.net.BindException e) {
                    portInUse = true;
                } catch (Throwable ignored) {}
                if (portInUse) {
                    PowerhouseLogger.error("Web dashboard port " + webPort + " is already in use by another application. Please choose a different port in the config.");
                } else {
                    webManager = new WebManager(this, webPort);
                    String ip = "127.0.0.1";
                    try {
                        java.util.Enumeration<java.net.NetworkInterface> nets = java.net.NetworkInterface.getNetworkInterfaces();
                        java.util.regex.Pattern privatePattern = java.util.regex.Pattern.compile("^(127\\.|0\\.0\\.0\\.0|10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.)");
                        java.util.List<String> candidates = new java.util.ArrayList<>();
                        while (nets.hasMoreElements()) {
                            java.net.NetworkInterface netint = nets.nextElement();
                            if (!netint.isUp() || netint.isLoopback() || netint.isVirtual()) continue;
                            java.util.Enumeration<java.net.InetAddress> addrs = netint.getInetAddresses();
                            while (addrs.hasMoreElements()) {
                                java.net.InetAddress addr = addrs.nextElement();
                                if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                                    String candidate = addr.getHostAddress();
                                    candidates.add(candidate);
                                    if (!privatePattern.matcher(candidate).find()) {
                                        ip = candidate;
                                        break;
                                    }
                                }
                            }
                        }
                        if ("127.0.0.1".equals(ip) && !candidates.isEmpty()) ip = candidates.get(0);
                    } catch (Throwable ignored) {}
                    PowerhouseLogger.info("Web dashboard available at http://" + ip + ":" + webPort);
                    if (ip.matches("^(127\\.|0\\.0\\.0\\.0|10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.).*")) {
                        PowerhouseLogger.warn("The detected IP address (" + ip + ") is private or local. If you want to access the dashboard remotely, use your server's public IP or hostname.");
                    }
                }
            } else {
                PowerhouseLogger.info("Web server is disabled in config");
            }
        } catch (Throwable t) {
            PowerhouseLogger.error("Failed to start web server: " + t.getMessage());
        }
        
        PowerhouseLogger.info("Powerhouse initialized!");
        PowerhouseLogger.info("Optimizing your server in the background!");

        try {
            leakPreventionTask = kaiakk.multimedia.classes.SchedulerHelper.runTimerMinutes(this, new Runnable() {
                public void run() {
                    try {
                        kaiakk.powerhouse.helpers.java.LeakPrevention.purgeCollections();
                    } catch (Throwable t) {
                        try { kaiakk.powerhouse.helpers.logs.PowerhouseLogger.error("LeakPrevention periodic purge failed: " + t.getMessage()); } catch (Throwable ignored) {}
                    }
                }
            }, 10.0, 10.0);
        } catch (Throwable ignored) {}
    }

    private boolean handleStats(org.bukkit.command.CommandSender sender) {
        if (optimizations != null) {
            java.util.Map<String, Object> stats = optimizations.getStatistics();
            sender.sendMessage(ColorConverter.colorize("&a&l=== Powerhouse Statistics ==="));
            sender.sendMessage(ColorConverter.colorize("&7Monitored Locations: &e" + stats.get("monitored_locations")));
            sender.sendMessage(ColorConverter.colorize("&7Culled Locations: &c" + stats.get("culled_locations")));
            sender.sendMessage(ColorConverter.colorize("&7Total Tracked: &b" + stats.get("total_tracked")));
            try {
                if (itemRemover != null) sender.sendMessage(ColorConverter.colorize("&7Hidden items: &e" + itemRemover.getHiddenCount()));
            } catch (Throwable ignored) {}
        } else {
            sender.sendMessage(ColorConverter.colorize("&cOptimization system not initialized!"));
        }
        return true;
    }

    private boolean handleMspt(org.bukkit.command.CommandSender sender) {
        if (kaiakk.powerhouse.helpers.internal.FoliaChecker.isFolia(this)) {
            sender.sendMessage(ColorConverter.colorize("&cMSPT is disabled on Folia due to incorrect reporting!"));
            return true;
        }

        if (optimizations != null) {
            double active = -1.0;
            double interval = -1.0;
            try { active = optimizations.getActiveMspt(); } catch (Throwable ignored) {}
            try { interval = optimizations.getIntervalMspt(); } catch (Throwable ignored) {}
            double raw = -1.0;
            try { raw = optimizations.getAverageMspt(); } catch (Throwable ignored) {}
            double smoothed = kaiakk.powerhouse.helpers.scaling.ScaleUtils.getSmoothedMspt();

            String source = "unknown";
            try { source = optimizations.getMsptSource(); } catch (Throwable ignored) {}

            sender.sendMessage(ColorConverter.colorize("&aMSPT source: &e" + source));
            if (active >= 0.0) sender.sendMessage(ColorConverter.colorize("&aActive MSPT (work-only): &e" + String.format("%.2f", active)));
            sender.sendMessage(ColorConverter.colorize("&aInterval MSPT (wall-clock): &e" + (interval >= 0.0 ? String.format("%.2f", interval) : "n/a")));
            sender.sendMessage(ColorConverter.colorize("&aPrimary MSPT: &e" + (raw >= 0.0 ? String.format("%.2f", raw) : "n/a")));
        } else {
            sender.sendMessage(ColorConverter.colorize("&cOptimization system not initialized!"));
        }
        return true;
    }

    private boolean handleReload(org.bukkit.command.CommandSender sender) {
        if (!sender.hasPermission("powerhouse")) {
            sender.sendMessage(ColorConverter.colorize("&cYou don't have permission to reload Powerhouse."));
            return true;
        }
        sender.sendMessage(ColorConverter.colorize("&eReloading Powerhouse configuration..."));
        try {
            ConfigHelp.reload();
            ConfigHelp.ensureDefaults(new java.util.HashMap<>());
        } catch (Throwable ignored) {}

        try {
            try { if (optimizations != null) optimizations.stop(); } catch (Throwable ignored) {}
            try { if (optimizations != null) optimizations.start(); } catch (Throwable ignored) {}
                try { if (itemRemover != null) itemRemover.reloadSettings(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}

        sender.sendMessage(ColorConverter.colorize("&aPowerhouse reloaded."));
        return true;
    }

    private boolean handleDebug(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("powerhouse")) {
            sender.sendMessage(ColorConverter.colorize("&cYou don't have permission to toggle debug."));
            return true;
        }
        if (optimizations == null) {
            sender.sendMessage(ColorConverter.colorize("&cOptimization system not initialized!"));
            return true;
        }

        boolean isPlayer = sender instanceof org.bukkit.entity.Player;
        String senderName = isPlayer ? ((org.bukkit.entity.Player) sender).getName() : "CONSOLE";

        if (args.length >= 2 && "global".equalsIgnoreCase(args[1])) {
            if (!sender.hasPermission("powerhouse")) {
                sender.sendMessage(ColorConverter.colorize("&cYou don't have permission to change global debug."));
                return true;
            }
            if (args.length >= 3) {
                String v = args[2].toLowerCase();
                if ("on".equals(v) || "enable".equals(v)) {
                    optimizations.setDebugEnabled(true, senderName);
                    sender.sendMessage(ColorConverter.colorize("&cPowerhouse global debug enabled"));
                    return true;
                } else if ("off".equals(v) || "disable".equals(v)) {
                    optimizations.setDebugEnabled(false, null);
                    sender.sendMessage(ColorConverter.colorize("&aPowerhouse global debug disabled"));
                    return true;
                }
            }
            sender.sendMessage(ColorConverter.colorize("&cUsage: /powerhouse debug global <on|off>"));
            return true;
        }

        if (!isPlayer) {
            sender.sendMessage(ColorConverter.colorize("&cConsole must use '/powerhouse debug global <on|off>' to control global debug."));
            return true;
        }

        if (optimizations.isDebugEnabledForUser(senderName)) {
            optimizations.disableDebugForUser(senderName);
            sender.sendMessage(ColorConverter.colorize("&aPowerhouse debug disabled for you."));
        } else {
            optimizations.enableDebugForUser(senderName);
            sender.sendMessage(ColorConverter.colorize("&cPowerhouse debug enabled for you. Debug messages will be sent privately."));
        }
        return true;
    }

    private boolean handleIsCulled(org.bukkit.command.CommandSender sender, String[] args) {
        if (optimizations == null) {
            sender.sendMessage(ColorConverter.colorize("&cOptimization system not initialized!"));
            return true;
        }
        org.bukkit.Location loc = null;
        if (args.length >= 4) {
            try {
                int x = Integer.parseInt(args[1]);
                int y = Integer.parseInt(args[2]);
                int z = Integer.parseInt(args[3]);
                org.bukkit.World w = (args.length >= 5) ? getServer().getWorld(args[4]) : null;
                if (w == null && sender instanceof org.bukkit.entity.Player) {
                    w = ((org.bukkit.entity.Player) sender).getWorld();
                }
                if (w == null) {
                    sender.sendMessage(ColorConverter.colorize("&cWorld not specified or unknown."));
                    return true;
                }
                loc = new org.bukkit.Location(w, x, y, z);
            } catch (Throwable ex) { loc = null; }
        }
        if (loc == null && sender instanceof org.bukkit.entity.Player) {
            loc = ((org.bukkit.entity.Player) sender).getLocation();
        }
        if (loc == null) {
            sender.sendMessage(ColorConverter.colorize("&cUsage: /powerhouse isculled [x y z [world]] or run as player to use your location"));
            return true;
        }
        boolean culled = optimizations.isLocationCulled(loc);
        sender.sendMessage(ColorConverter.colorize(culled ? "&cLocation is currently culled" : "&aLocation is not culled"));
        return true;
    }

    private boolean handleAi(org.bukkit.command.CommandSender sender, String[] args) {
        if (optimizations == null) {
            sender.sendMessage(ColorConverter.colorize("&cOptimization system not initialized!"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ColorConverter.colorize("&cUsage: /powerhouse ai <entity-uuid>"));
            return true;
        }
        try {
            java.util.UUID id = java.util.UUID.fromString(args[1]);
            boolean disabled = optimizations.isEntityAiDisabled(id);
            sender.sendMessage(ColorConverter.colorize(disabled ? "&cAI is disabled for that entity" : "&aAI is enabled for that entity"));
        } catch (Throwable ex) {
            sender.sendMessage(ColorConverter.colorize("&cInvalid UUID."));
        }
        return true;
    }

    private boolean handleJvm(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("powerhouse")) {
            sender.sendMessage(ColorConverter.colorize("&cYou don't have permission to use JVM commands."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ColorConverter.colorize("&aPowerhouse JVM commands:"));
            sender.sendMessage(ColorConverter.colorize("&7/powerhouse jvm status &8- show JVM stats"));
            sender.sendMessage(ColorConverter.colorize("&7/powerhouse jvm gc &8- request GC"));
            sender.sendMessage(ColorConverter.colorize("&7/powerhouse jvm heapdump [file] &8- attempt heap dump to plugin folder"));
            sender.sendMessage(ColorConverter.colorize("&7/powerhouse jvm jfr &8- use external jcmd/jfr for recordings (see console)"));
            return true;
        }

        String sub = args[1].toLowerCase();
        try {
            switch (sub) {
                case "status":
                    kaiakk.powerhouse.helpers.java.JvmMonitor.status(sender);
                    return true;
                case "gc":
                    kaiakk.powerhouse.helpers.java.JvmMonitor.runGc(sender);
                    return true;
                case "heapdump": {
                    String file = (args.length >= 3) ? args[2] : null;
                    kaiakk.powerhouse.helpers.java.JvmMonitor.heapDump(this, file, sender);
                    return true;
                }
                case "jfr":
                    sender.sendMessage(ColorConverter.colorize("&eJFR control is not implemented in-plugin. Use jcmd <pid> JFR.start|JFR.stop|JFR.dump or jcmd <pid> JFR.start name=rec settings=profile"));
                    return true;
                default:
                    sender.sendMessage(ColorConverter.colorize("&cUnknown jvm subcommand."));
                    return true;
            }
        } catch (Throwable t) {
            sender.sendMessage(ColorConverter.colorize("&cError executing JVM command: " + t.getMessage()));
            return true;
        }
    }

    private boolean handlePurge(org.bukkit.command.CommandSender sender, String[] args) {
        if (!sender.hasPermission("powerhouse")) {
            sender.sendMessage(ColorConverter.colorize("&cYou don't have permission to use this command."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ColorConverter.colorize("&cUsage: /powerhouse purge <items|mobs> [world]"));
            return true;
        }

        final String target = args[1].toLowerCase();
        org.bukkit.World w = null;
        if (args.length >= 3) {
            w = getServer().getWorld(args[2]);
            if (w == null) {
                sender.sendMessage(ColorConverter.colorize("&cUnknown world: " + args[2]));
                return true;
            }
        } else if (sender instanceof org.bukkit.entity.Player) {
            w = ((org.bukkit.entity.Player) sender).getWorld();
        }

        final org.bukkit.World worldParam = w;

        if ("items".equals(target) || "item".equals(target)) {
            sender.sendMessage(ColorConverter.colorize("&ePurging items..."));
            SchedulerHelper.run(this, new Runnable() {
                public void run() {
                    long removed = 0;
                    try {
                        for (org.bukkit.World world : (worldParam == null ? getServer().getWorlds() : java.util.Collections.singletonList(worldParam))) {
                            for (org.bukkit.entity.Entity e : new java.util.ArrayList<>(world.getEntities())) {
                                if (e == null) continue;
                                try { if (!e.isValid()) continue; } catch (Throwable ignored) { continue; }
                                if (e instanceof org.bukkit.entity.Item) {
                                    try { e.remove(); removed++; } catch (Throwable ignored) {}
                                }
                            }
                        }
                    } catch (Throwable t) {}
                    try {
                        sender.sendMessage(ColorConverter.colorize("&aPurged items: &e" + removed));
                        try { if (optimizations != null) optimizations.addItemRemovals(removed); } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                }
            });
            return true;
        }

        if ("mobs".equals(target) || "mob".equals(target)) {
            sender.sendMessage(ColorConverter.colorize("&ePurging mobs..."));
            SchedulerHelper.run(this, new Runnable() {
                public void run() {
                    long removed = 0;
                    try {
                        for (org.bukkit.World world : (worldParam == null ? getServer().getWorlds() : java.util.Collections.singletonList(worldParam))) {
                            for (org.bukkit.entity.Entity e : new java.util.ArrayList<>(world.getEntities())) {
                                if (e == null) continue;
                                try { if (!e.isValid()) continue; } catch (Throwable ignored) { continue; }
                                if (e instanceof org.bukkit.entity.LivingEntity && !(e instanceof org.bukkit.entity.Player)) {
                                    try { e.remove(); removed++; } catch (Throwable ignored) {}
                                }
                            }
                        }
                    } catch (Throwable t) {}
                    try {
                        sender.sendMessage(ColorConverter.colorize("&aPurged mobs: &e" + removed));
                        try { if (optimizations != null) optimizations.addCrammingRemovals(removed); } catch (Throwable ignored) {}
                    } catch (Throwable ignored) {}
                }
            });
            return true;
        }

        sender.sendMessage(ColorConverter.colorize("&cUnknown purge target. Use 'items' or 'mobs'."));
        return true;
    }
    
    private boolean handleWeb(org.bukkit.command.CommandSender sender, String[] args) {
        int port = ConfigHelp.getInt("web-server.port", 8080);
        boolean enabled = ConfigHelp.getBoolean("web-server.enabled", true);

        if (!enabled) {
            sender.sendMessage(ColorConverter.colorize("&cWeb server is disabled in config!"));
            sender.sendMessage(ColorConverter.colorize("&7Enable it in config.yml: web-server.enabled: true"));
            return true;
        }

        if (webManager == null) {
            sender.sendMessage(ColorConverter.colorize("&cWeb server failed to start! Check console for errors."));
            return true;
        }

        String ip = "127.0.0.1";
        boolean isIpv6 = false;
        try {
            java.util.Enumeration<java.net.NetworkInterface> nets = java.net.NetworkInterface.getNetworkInterfaces();
            java.util.regex.Pattern privatePattern = java.util.regex.Pattern.compile("^(127\\.|0\\.0\\.0\\.0|10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.)");
            java.util.List<String> ipv4Candidates = new java.util.ArrayList<>();
            java.util.List<String> ipv6Candidates = new java.util.ArrayList<>();
            String publicIpv4 = null;
            String publicIpv6 = null;
            while (nets.hasMoreElements()) {
                java.net.NetworkInterface netint = nets.nextElement();
                if (!netint.isUp() || netint.isLoopback() || netint.isVirtual()) continue;
                java.util.Enumeration<java.net.InetAddress> addrs = netint.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr.isLoopbackAddress()) continue;
                    String candidate = addr.getHostAddress();
                    if (addr instanceof java.net.Inet4Address) {
                        ipv4Candidates.add(candidate);
                        if (!privatePattern.matcher(candidate).find() && publicIpv4 == null) {
                            publicIpv4 = candidate;
                        }
                    } else if (addr instanceof java.net.Inet6Address) {
                        if (candidate.startsWith("fe80") || candidate.startsWith("fd")) continue;
                        int percent = candidate.indexOf('%');
                        if (percent > 0) candidate = candidate.substring(0, percent);
                        ipv6Candidates.add(candidate);
                        if (publicIpv6 == null) publicIpv6 = candidate;
                    }
                }
            }
            if (publicIpv4 != null) {
                ip = publicIpv4;
            } else if (!ipv4Candidates.isEmpty()) {
                ip = ipv4Candidates.get(0);
            } else if (publicIpv6 != null) {
                ip = publicIpv6;
                isIpv6 = true;
            } else if (!ipv6Candidates.isEmpty()) {
                ip = ipv6Candidates.get(0);
                isIpv6 = true;
            }
        } catch (Throwable ignored) {}

        String url = isIpv6 ? ("http://[" + ip + "]:" + port) : ("http://" + ip + ":" + port);

        sender.sendMessage(ColorConverter.colorize("&a&l=== Powerhouse Web Dashboard ==="));
        sender.sendMessage(ColorConverter.colorize("&7Status: &aRunning"));
        sender.sendMessage(ColorConverter.colorize("&7Detected IP: &e" + ip));
        sender.sendMessage(ColorConverter.colorize("&7Port: &e" + port));
        sender.sendMessage(ColorConverter.colorize(""));
        sender.sendMessage(ColorConverter.colorize("&7Access the dashboard at:"));
        sender.sendMessage(ColorConverter.colorize("&b&n" + url));
        return true;
    }
    
    private boolean handleHardware(org.bukkit.command.CommandSender sender, String[] args) {
        sender.sendMessage(ColorConverter.colorize("&eCollecting hardware stats—this may take a moment..."));

        SchedulerHelper.run(this, new Runnable() {
            public void run() {
                kaiakk.powerhouse.data.collectors.HardwareStats stats = new kaiakk.powerhouse.data.collectors.HardwareStats();

                try {
                    int logical = Runtime.getRuntime().availableProcessors();
                    stats.setLogicalCores(logical);

                    double cpuLoad = -1.0;
                    try {
                        java.lang.management.OperatingSystemMXBean mx = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
                        if (mx != null) {
                            try {
                                java.lang.reflect.Method m = mx.getClass().getMethod("getSystemCpuLoad");
                                Object o = m.invoke(mx);
                                if (o instanceof Number) {
                                    double v = ((Number) o).doubleValue();
                                    if (v >= 0.0) cpuLoad = v * 100.0;
                                }
                            } catch (NoSuchMethodException ns) {
                                try {
                                    java.lang.reflect.Method m2 = mx.getClass().getMethod("getCpuLoad");
                                    Object o2 = m2.invoke(mx);
                                    if (o2 instanceof Number) {
                                        double v = ((Number) o2).doubleValue();
                                        if (v >= 0.0) cpuLoad = v * 100.0;
                                    }
                                } catch (Throwable ignored2) {}
                            } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                    stats.setCpuUsagePercent(cpuLoad);

                    try {
                        stats.detectPhysicalCores();
                    } catch (Throwable ignored) {}

                    Runtime rt = Runtime.getRuntime();
                    long total = rt.totalMemory();
                    long free = rt.freeMemory();
                    long used = total - free;
                    long max = rt.maxMemory();
                    stats.setRamAllocatedBytes(total);
                    stats.setRamUsedBytes(used);
                    stats.setRamMaxBytes(max);

                    File serverRoot = null;
                    try {
                        serverRoot = getServer().getWorldContainer();
                    } catch (Throwable ignored) {}
                    if (serverRoot == null) serverRoot = new File(".");

                    long size = getFolderSize(serverRoot);
                    stats.setServerFolderBytes(size);
                } catch (Throwable t) {
                    try { PowerhouseLogger.error("hardware probe failed: " + t.getMessage()); } catch (Throwable ignored) {}
                }

                final kaiakk.powerhouse.data.collectors.HardwareStats toSend = stats;
                getServer().getScheduler().runTask(Powerhouse.this, new Runnable() {
                    public void run() {
                        sender.sendMessage(ColorConverter.colorize("&a=== Hardware Stats ==="));
                        sender.sendMessage(ColorConverter.colorize("&7CPU logical cores: &e" + toSend.getLogicalCores()));
                        double cpu = toSend.getCpuUsagePercent();
                        sender.sendMessage(ColorConverter.colorize("&7CPU usage: &e" + (cpu >= 0.0 ? String.format("%.2f%%", cpu) : "n/a")));
                        sender.sendMessage(ColorConverter.colorize("&7JVM allocated RAM: &e" + String.format("%.2f MB", toSend.getRamAllocatedMB())));
                        sender.sendMessage(ColorConverter.colorize("&7JVM used RAM: &e" + String.format("%.2f MB", toSend.getRamUsedMB())));
                        sender.sendMessage(ColorConverter.colorize("&7JVM max RAM: &e" + String.format("%.2f MB", toSend.getRamMaxMB())));
                        sender.sendMessage(ColorConverter.colorize("&7Server folder size: &e" + String.format("%.2f MB", toSend.getServerFolderMB())));
                    }
                });
            }
        });

        return true;
    }
    
    private long getFolderSize(File folder) {
        long length = 0L;
        java.util.Deque<File> stack = new java.util.ArrayDeque<>();
        if (folder != null) stack.push(folder);
        while (!stack.isEmpty()) {
            File current = stack.pop();
            File[] files = current.listFiles();
            if (files == null) continue;
            for (File f : files) {
                try {
                    if (f.isFile()) length += f.length();
                    else if (f.isDirectory()) stack.push(f);
                } catch (Throwable ignored) {}
            }
        }
        return length;
    }
    
    @Override
    public void onDisable() {
        PowerhouseLogger.info("Powerhouse shutting down!");
        try { kaiakk.powerhouse.external.support.ProxyAwareness.stopListening(this); } catch (Throwable ignored) {}
        try { if (leakPreventionTask != null) kaiakk.multimedia.classes.SchedulerHelper.cancelTask(leakPreventionTask); } catch (Throwable ignored) {}
        
        if (optimizations != null) {
            optimizations.stop();
        }
        try {
            if (aiManagement != null) aiManagement.stop();
        } catch (Throwable ignored) {}
        try {
            if (entityCulling != null) entityCulling.stop();
        } catch (Throwable ignored) {}
        try {
            if (explosionCanceller != null) explosionCanceller.stop();
        } catch (Throwable ignored) {}
        try {
            if (itemRemover != null) itemRemover.stop();
        } catch (Throwable ignored) {}
        try {
            if (serverController != null) serverController.stop();
        } catch (Throwable ignored) {}
        try {
            if (webManager != null) webManager.shutdown();
        } catch (Throwable ignored) {}
        
        PowerhouseLogger.info("Configuration settings saved!");
        try { ConfigHelp.reload(); } catch (Throwable ignored) {}
        ConfigHelp.save();
        try { LeakPrevention.shutdownAll(); } catch (Throwable ignored) {}
        try { LeakPrevention.purgeCollections(); } catch (Throwable ignored) {}

        try { INSTANCE = null; } catch (Throwable ignored) {}
        PowerhouseLogger.info("Powerhouse out!");
        PowerhouseLogger.info("Goodbye.");
    }

    public static Powerhouse getInstance() { return INSTANCE; }
}
