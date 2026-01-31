package kaiakk.powerhouse.calculations;

import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import kaiakk.powerhouse.data.snapshot.ItemSnapshot;

import java.util.*;

public class CalculationsSync {

    public static Map<Item, List<Item>> scanItemMergeCandidates(List<Item> items) {
        if (items == null || items.isEmpty()) return Collections.emptyMap();

        List<ItemSnapshot> snaps = new ArrayList<>();
        Map<Integer, Item> indexToItem = new HashMap<>();
        
        List<Player> worldPlayers = items.get(0).getWorld().getPlayers();

        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it == null || !it.isValid()) continue;

            ItemStack stack = it.getItemStack();
            if (stack == null || stack.getType() == org.bukkit.Material.AIR) continue;

            Location loc = it.getLocation();
            boolean hasMeta = false;
            if (stack.hasItemMeta()) {
                ItemMeta meta = stack.getItemMeta();
                hasMeta = meta.hasDisplayName() || meta.hasLore() || meta.hasEnchants();
            }

            boolean playerNearby = false;
            for (Player p : worldPlayers) {
                if (p != null && p.getLocation().distanceSquared(loc) <= 2.25) { 
                    playerNearby = true; 
                    break; 
                }
            }

            ItemSnapshot s = new ItemSnapshot(i, loc.getX(), loc.getY(), loc.getZ(), 
                stack.getType(), stack.getAmount(), stack.getDurability(), 
                stack.getMaxStackSize(), hasMeta, playerNearby);
                
            snaps.add(s);
            indexToItem.put(i, it);
        }

        Map<ItemSnapshot, List<ItemSnapshot>> snapResult = kaiakk.powerhouse.calculations.Calculations.scanItemMergeCandidatesSnapshots(snaps);

        Map<Item, List<Item>> result = new HashMap<>();
        for (Map.Entry<ItemSnapshot, List<ItemSnapshot>> e : snapResult.entrySet()) {
            Item key = indexToItem.get(e.getKey().originalIndex);
            if (key == null) continue;

            List<Item> merged = new ArrayList<>();
            for (ItemSnapshot s : e.getValue()) {
                Item o = indexToItem.get(s.originalIndex);
                if (o != null) merged.add(o);
            }

            if (!merged.isEmpty()) result.put(key, merged);
        }

        return result;
    }

    public static int calculateMergedAmount(Item target, List<Item> toMerge) {
        if (target == null || !target.isValid()) return 0;

        ItemStack tStack = target.getItemStack();
        if (tStack == null) return 0;

        ItemSnapshot tSnap = new ItemSnapshot(-1, target.getLocation().getX(), target.getLocation().getY(), target.getLocation().getZ(), 
            tStack.getType(), tStack.getAmount(), tStack.getDurability(), tStack.getMaxStackSize(), tStack.hasItemMeta(), false);

        List<ItemSnapshot> snaps = new ArrayList<>();
        for (Item it : toMerge) {
            if (it == null || !it.isValid()) continue;
            ItemStack s = it.getItemStack();
            if (s == null) continue;
            snaps.add(new ItemSnapshot(-1, it.getLocation().getX(), it.getLocation().getY(), it.getLocation().getZ(), 
                s.getType(), s.getAmount(), s.getDurability(), s.getMaxStackSize(), s.hasItemMeta(), false));
        }

        return kaiakk.powerhouse.calculations.Calculations.calculateMergedAmountSnapshot(tSnap, snaps);
    }
}


