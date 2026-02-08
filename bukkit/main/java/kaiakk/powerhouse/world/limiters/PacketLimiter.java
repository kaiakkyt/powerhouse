package kaiakk.powerhouse.world.limiters;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;

import kaiakk.powerhouse.helpers.logs.PowerhouseLogger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import kaiakk.multimedia.classes.SchedulerHelper;

public class PacketLimiter implements Listener {

    private final Plugin plugin;

    private enum ActionType {
        MOVEMENT(80),
        INTERACT(30),
        GUI(40),
        CHAT(10);

        final int maxPerSecond;
        ActionType(int max) { this.maxPerSecond = max; }
    }

    private static final Map<UUID, PlayerWindow> userStats = new ConcurrentHashMap<>();

    private static final class PlayerWindow {
        final AtomicLong windowStartMs;
        final Map<ActionType, LongAdder> counts = new ConcurrentHashMap<>();
        final AtomicInteger violationCount = new AtomicInteger(0);
        PlayerWindow(long now) { this.windowStartMs = new AtomicLong(now); }
        boolean tryReset(long now) {
            long prev = windowStartMs.get();
            if (now - prev >= 5000L) {
                if (windowStartMs.compareAndSet(prev, now)) {
                    counts.clear();
                    violationCount.set(0);
                    return true;
                }
            }
            return false;
        }
    }

    public PacketLimiter(Plugin plugin) {
        this.plugin = plugin;
    }

    private boolean checkLimit(Player player, ActionType type) {
        if (player == null) return false;
        try {
            if (player.isOp()
                    || player.hasPermission("axior.mod")
                    || player.hasPermission("axior.admin")
                    || player.hasPermission("axior.owner")
                    || player.hasPermission("powerhouse")) {
                return false;
            }
        } catch (Throwable ignored) {}

        UUID id = player.getUniqueId();

        long now = System.currentTimeMillis();
        PlayerWindow win = userStats.computeIfAbsent(id, k -> new PlayerWindow(now));
        if (now - win.windowStartMs.get() >= 5000L) {
            win.tryReset(now);
        }
        Map<ActionType, LongAdder> counts = win.counts;
        LongAdder adder = counts.computeIfAbsent(type, k -> new LongAdder());
        adder.increment();
        int current = adder.intValue();

        if (current > type.maxPerSecond) {
            int vl = 0;
            try { vl = win.violationCount.incrementAndGet(); } catch (Throwable ignored) {}

            final int violations = vl;
            final String playerMsg = "§cYou are sending packets too fast (" + type.name() + "). Violation: " + violations;
            final String consoleMsg = "PacketLimiter: " + player.getName() + " (" + id + ") exceeded " + type.name() + " threshold (" + current + ") vl=" + violations;

            try {
                SchedulerHelper.run(plugin, () -> {
                    try { player.sendMessage(playerMsg); } catch (Throwable ignored) {}
                    try { PowerhouseLogger.warn(consoleMsg); } catch (Throwable ignored) {}
                    try {
                        if (violations > 10) {
                            try { player.kickPlayer("§cKicked for excessive packet spam."); } catch (Throwable ignored) {}
                            try { userStats.remove(id); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}

            return true;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.getFrom() == null || event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX() || event.getFrom().getZ() != event.getTo().getZ()) {
            if (checkLimit(event.getPlayer(), ActionType.MOVEMENT)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (checkLimit(event.getPlayer(), ActionType.INTERACT)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventory(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player) {
            if (checkLimit((Player) event.getWhoClicked(), ActionType.GUI)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (checkLimit(event.getPlayer(), ActionType.CHAT)) {
            event.setCancelled(true);
            try { event.getPlayer().sendMessage("§cSlow down with the commands!"); } catch (Throwable ignored) {}
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent ev) {
        try { if (ev != null && ev.getPlayer() != null) userStats.remove(ev.getPlayer().getUniqueId()); } catch (Throwable ignored) {}
    }

    public static void shutdown() {
        try { userStats.clear(); } catch (Throwable ignored) {}
    }
}
