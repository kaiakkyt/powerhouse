package kaiakk.powerhouse.async;

import org.bukkit.Location;
import java.util.Objects;
import kaiakk.powerhouse.sync.ScaleUtils;

public class HopperCalculation {

    public static long getPackedLocation(Location loc) {
        if (loc == null || loc.getWorld() == null) return 0L;

        long x = (long) loc.getBlockX() & 0x1FFFFFFL;
        long z = (long) loc.getBlockZ() & 0x1FFFFFFL;
        long y = (long) (loc.getBlockY() + 64) & 0x3FFL;
        long w = (long) (Objects.toString(loc.getWorld().getName(), "").hashCode() & 0xFL);

        return (w << 60) | (y << 50) | (x << 25) | z;
    }

    public static boolean isLagMachinePattern(int transfersPerSecond, int nearbyHopperCount) {
        double scale = ScaleUtils.continuousScaleFromMspt(ScaleUtils.getSmoothedMspt());
        return isLagMachinePattern(transfersPerSecond, nearbyHopperCount, scale);
    }

    public static boolean isLagMachinePattern(int transfersPerSecond, int nearbyHopperCount, double scale) {
        int freqThreshold = (scale < 0.5) ? 50 : 100;
        if (transfersPerSecond >= freqThreshold) return true;

        int densityThreshold = (scale < 0.5) ? 30 : 50;
        if (nearbyHopperCount >= densityThreshold && transfersPerSecond >= 20) return true;

        return false;
    }
}


