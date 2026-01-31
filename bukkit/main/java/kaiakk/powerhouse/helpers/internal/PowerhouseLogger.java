package kaiakk.powerhouse.helpers.internal;

import kaiakk.multimedia.classes.ConsoleLog;
import kaiakk.powerhouse.world.AllOptimizations;

public final class PowerhouseLogger {
    private PowerhouseLogger() {}

    public static void info(String msg) {
        try {
            AllOptimizations inst = AllOptimizations.getInstance();
            if (inst == null || inst.isDebugEnabled()) {
                try { ConsoleLog.info(msg); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void warn(String msg) {
        try {
            AllOptimizations inst = AllOptimizations.getInstance();
            if (inst == null || inst.isDebugEnabled()) {
                try { ConsoleLog.warn(msg); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }

    public static void error(String msg) {
        try { ConsoleLog.warn(msg); } catch (Throwable ignored) {}
    }
}


