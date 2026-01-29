package kaiakk.powerhouse.sync;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;

import java.util.UUID;

public final class EntityLookup {
    private EntityLookup() {}

    public static Entity getEntity(UUID id) {
        if (id == null) return null;
        try {
            try {
                java.lang.reflect.Method m = Bukkit.class.getMethod("getEntity", java.util.UUID.class);
                Object res = m.invoke(null, id);
                if (res instanceof Entity) return (Entity) res;
            } catch (Throwable ignored) {}

            for (World w : Bukkit.getWorlds()) {
                try {
                    for (Entity e : w.getEntities()) {
                        if (e != null && id.equals(e.getUniqueId())) return e;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }
}


