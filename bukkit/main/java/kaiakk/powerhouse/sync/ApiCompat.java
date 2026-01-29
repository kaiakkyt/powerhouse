package kaiakk.powerhouse.sync;

import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.Location;
import org.bukkit.Sound;

public final class ApiCompat {
    private ApiCompat() {}

    public static Entity getEntity(java.util.UUID id) {
        return EntityLookup.getEntity(id);
    }

    public static void setAI(LivingEntity ent, boolean enabled) {
        if (ent == null) return;
        try {
            ent.setAI(enabled);
            return;
        } catch (NoSuchMethodError ignored) {}
        try {
            java.lang.reflect.Method m = ent.getClass().getMethod("setAI", boolean.class);
            m.invoke(ent, enabled);
            return;
        } catch (Throwable ignored) {}
    }

    public static double getMaxHealth(LivingEntity ent) {
        if (ent == null) return 0.0;
        try {
            try {
                Object attr = ent.getClass().getMethod("getAttribute", org.bukkit.attribute.Attribute.class).invoke(ent, org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
                if (attr != null) {
                    Object val = attr.getClass().getMethod("getBaseValue").invoke(attr);
                    if (val instanceof Number) return ((Number) val).doubleValue();
                }
            } catch (Throwable ignored) {}

            try {
                return ent.getMaxHealth();
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        return 0.0;
    }

    public static void playSoundSafe(World world, Location loc, Sound sound, float vol, float pitch) {
        if (world == null || loc == null || sound == null) return;
        try {
            world.playSound(loc, sound, vol, pitch);
        } catch (NoSuchMethodError | NoClassDefFoundError ex) {
            try {
                java.lang.reflect.Method m = world.getClass().getMethod("playSound", Location.class, Sound.class, float.class, float.class);
                m.invoke(world, loc, sound, vol, pitch);
            } catch (Throwable ex2) {}
        } catch (Throwable ex3) {}
    }
}


