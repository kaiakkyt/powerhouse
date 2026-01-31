package kaiakk.powerhouse.data;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

public final class BlockKey {
    public final int x;
    public final int y;
    public final int z;
    public final String worldName;
    private final int memoizedHash;

    public BlockKey(int x, int y, int z, String worldName) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.worldName = (worldName != null) ? worldName.intern() : "world";
        
        int result = x;
        result = 31 * result + y;
        result = 31 * result + z;
        result = 31 * result + this.worldName.hashCode();
        this.memoizedHash = result;
    }

    public static BlockKey from(Location loc) {
        if (loc == null || loc.getWorld() == null) return null;
        return new BlockKey(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), loc.getWorld().getName());
    }

    public Location toLocation() {
        World w = Bukkit.getWorld(this.worldName);
        return (w != null) ? new Location(w, x, y, z) : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockKey)) return false;
        BlockKey other = (BlockKey) o;
        return x == other.x && y == other.y && z == other.z && 
               worldName.equals(other.worldName);
    }

    @Override
    public int hashCode() {
        return memoizedHash;
    }
}


