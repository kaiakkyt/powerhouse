package kaiakk.powerhouse.sync;

import org.bukkit.Material;

public class ItemSnapshot {
    public final int originalIndex;
    public final double x;
    public final double y;
    public final double z;
    public final Material type;
    public final int amount;
    public final short durability;
    public final int maxStackSize;
    public final boolean hasMeta;
    public final boolean isPlayerNearby;

    public ItemSnapshot(int originalIndex, double x, double y, double z, Material type, int amount, short durability, int maxStackSize, boolean hasMeta, boolean isPlayerNearby) {
        this.originalIndex = originalIndex;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.amount = amount;
        this.durability = durability;
        this.maxStackSize = maxStackSize;
        this.hasMeta = hasMeta;
        this.isPlayerNearby = isPlayerNearby;
    }
}


