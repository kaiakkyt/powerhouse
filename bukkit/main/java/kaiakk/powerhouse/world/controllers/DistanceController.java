package kaiakk.powerhouse.world.controllers;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import kaiakk.powerhouse.helpers.scaling.Scalable;
import kaiakk.powerhouse.helpers.scaling.ScaleUtils;

import java.lang.reflect.Method;

public class DistanceController implements Scalable {

    private volatile int currentView = 10;
    private volatile int currentSim = 8;
    private static final long CHANGE_COOLDOWN_MS = 30_000L;
    private volatile long lastChangeMs = 0L;

    private static final Method WORLD_SET_VIEW_METHOD;
    private static final Method WORLD_SET_SIM_METHOD;
    private static final Method PLAYER_SET_VIEW_METHOD;
    static {
        Method a = null, b = null, c = null;
        try { a = org.bukkit.World.class.getMethod("setViewDistance", int.class); } catch (Throwable ignored) {}
        try { b = org.bukkit.World.class.getMethod("setSimulationDistance", int.class); } catch (Throwable ignored) {}
        try { c = org.bukkit.entity.Player.class.getMethod("setViewDistance", int.class); } catch (Throwable ignored) {}
        WORLD_SET_VIEW_METHOD = a;
        WORLD_SET_SIM_METHOD = b;
        PLAYER_SET_VIEW_METHOD = c;
    }

    public void setScale(double scale) {
        long now = System.currentTimeMillis();
        if (now - lastChangeMs < CHANGE_COOLDOWN_MS) return;
        double m = ScaleUtils.multiplierFromScale(scale, 0.6, 1.5);
        int view = Math.max(6, (int) Math.round(10.0 * m));
        int sim = Math.max(4, (int) Math.round(8.0 * m));
        currentView = view;
        currentSim = sim;
        applyGlobalDistances(view, sim);
        lastChangeMs = now;
    }

    public void applyGlobalDistances(int viewDistance, int simulationDistance) {
        for (World w : Bukkit.getWorlds()) {
            applyToWorld(w, viewDistance, simulationDistance);
        }
    }

    public void applyToWorld(World world, int viewDistance, int simulationDistance) {
        try {
            if (WORLD_SET_VIEW_METHOD != null) {
                try { WORLD_SET_VIEW_METHOD.invoke(world, viewDistance); return; } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try { invokeIfExists(world, "setViewDistance", viewDistance); } catch (Throwable ignored) {}

        try {
            if (WORLD_SET_SIM_METHOD != null) {
                try { WORLD_SET_SIM_METHOD.invoke(world, simulationDistance); return; } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        try { invokeIfExists(world, "setSimulationDistance", simulationDistance); } catch (Throwable ignored) {}
    }

    public void applyPerPlayerViewDistance(int distance) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            try {
                if (PLAYER_SET_VIEW_METHOD != null) {
                    try { PLAYER_SET_VIEW_METHOD.invoke(p, distance); continue; } catch (Throwable ignored) {}
                }
                invokeIfExists(p, "setViewDistance", distance);
            } catch (Throwable ignored) {}
        }
    }

    private static void invokeIfExists(Object target, String methodName, Object arg) {
        try {
            Class<?> paramType;
            if (arg instanceof Integer) paramType = int.class;
            else if (arg instanceof Boolean) paramType = boolean.class;
            else if (arg instanceof Long) paramType = long.class;
            else if (arg instanceof Double) paramType = double.class;
            else if (arg instanceof Short) paramType = short.class;
            else if (arg instanceof Byte) paramType = byte.class;
            else if (arg instanceof Float) paramType = float.class;
            else if (arg instanceof Character) paramType = char.class;
            else paramType = arg.getClass();

            Method m = target.getClass().getMethod(methodName, paramType);
            m.setAccessible(true);
            m.invoke(target, arg);
        } catch (Exception ignored) {
        }
    }
}


