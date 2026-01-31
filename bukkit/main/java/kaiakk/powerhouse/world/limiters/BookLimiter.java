package kaiakk.powerhouse.world.limiters;

import org.bukkit.Bukkit;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.plugin.Plugin;

import kaiakk.powerhouse.calculations.BookCalculator;

import java.util.Collections;

public class BookLimiter implements Listener {
    private static BookLimiter INSTANCE = null;
    private final Plugin plugin;

    private BookLimiter(Plugin plugin) {
        this.plugin = plugin;
    }

    public static synchronized void init(Plugin plugin) {
        if (INSTANCE == null) {
            INSTANCE = new BookLimiter(plugin);
            try { Bukkit.getPluginManager().registerEvents(INSTANCE, plugin); } catch (Throwable ignored) {}
        }
    }

    public static synchronized void shutdown() {
        if (INSTANCE == null) return;
        try { org.bukkit.event.HandlerList.unregisterAll(INSTANCE); } catch (Throwable ignored) {}
        INSTANCE = null;
    }

    private ItemStack createSanitized(ItemStack orig) {
        if (orig == null) return null;
        try {
            ItemStack copy = orig.clone();
            org.bukkit.inventory.meta.ItemMeta im = null;
            try { im = copy.getItemMeta(); } catch (Throwable ignored) {}
            if (!(im instanceof BookMeta)) return copy;
            BookMeta meta = (BookMeta) im;
            meta.setPages(Collections.singletonList("[Sanitized by Powerhouse]"));
            try { meta.setAuthor(null); } catch (Throwable ignored) {}
            try { meta.setTitle(null); } catch (Throwable ignored) {}
            copy.setItemMeta(meta);
            return copy;
        } catch (Throwable ignored) { return orig; }
    }

    private boolean sanitizeIfNeeded(ItemStack stack) {
        try {
            if (stack == null) return false;
            if (!BookCalculator.isBookType(stack)) return false;
            if (!BookCalculator.isMalicious(stack)) return false;
            ItemStack sanitized = createSanitized(stack);
            if (sanitized != null) {
                
                stack.setItemMeta(sanitized.getItemMeta());
                try { kaiakk.powerhouse.helpers.internal.DebugLog.debug("BookLagger: sanitized a malicious book"); } catch (Throwable ignored) {}
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent ev) {
        try {
            ItemStack cur = ev.getCurrentItem();
            if (cur != null) sanitizeIfNeeded(cur);
            ItemStack cursor = ev.getCursor();
            if (cursor != null) sanitizeIfNeeded(cursor);
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent ev) {
        try {
            ItemStack main = ev.getPlayer().getInventory().getItemInMainHand();
            if (main != null) sanitizeIfNeeded(main);
            ItemStack off = ev.getPlayer().getInventory().getItemInOffHand();
            if (off != null) sanitizeIfNeeded(off);
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerEditBook(PlayerEditBookEvent ev) {
        try {
            
            BookMeta meta = null;
            try { meta = ev.getNewBookMeta(); } catch (Throwable ignored) {}
            if (meta == null) return;
            ItemStack temp = new ItemStack(org.bukkit.Material.WRITTEN_BOOK);
            temp.setItemMeta(meta);
            if (sanitizeIfNeeded(temp)) {
                try { ev.setNewBookMeta((BookMeta) temp.getItemMeta()); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent ev) {
        try {
            Item it = ev.getEntity();
            if (it == null) return;
            ItemStack stack = it.getItemStack();
            if (stack == null) return;
            if (sanitizeIfNeeded(stack)) {
                try { it.setItemStack(stack); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent ev) {
        try {
            ItemStack stack = ev.getItem();
            if (stack == null) return;
            
            if (!BookCalculator.isBookType(stack)) return;
            if (sanitizeIfNeeded(stack)) {
                try { ev.setItem(stack); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
}

