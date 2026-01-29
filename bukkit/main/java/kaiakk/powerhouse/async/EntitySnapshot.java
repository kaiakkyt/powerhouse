package kaiakk.powerhouse.async;

import org.bukkit.entity.EntityType;

import java.util.UUID;

public class EntitySnapshot {
    public final UUID uuid;
    public final String world;
    public final double x, y, z;
    public final EntityType type;
    public final boolean hasCustomName;
    public final boolean isPlayer;
    public final int worth;

    public EntitySnapshot(UUID uuid, String world, double x, double y, double z, EntityType type, boolean hasCustomName, boolean isPlayer, int worth) {
        this.uuid = uuid;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.type = type;
        this.hasCustomName = hasCustomName;
        this.isPlayer = isPlayer;
        this.worth = worth;
    }
}


