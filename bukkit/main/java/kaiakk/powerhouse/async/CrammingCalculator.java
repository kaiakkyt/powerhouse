package kaiakk.powerhouse.async;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class CrammingCalculator {

    public static Set<UUID> calculateCrammingRemovals(List<EntitySnapshot> snaps, double radius, int threshold) {
        Set<UUID> toRemove = new HashSet<>();
        if (snaps == null || snaps.isEmpty()) return toRemove;

        final double rSq = radius * radius;

        for (int i = 0; i < snaps.size(); i++) {
            EntitySnapshot a = snaps.get(i);
            if (a == null) continue;
            if (a.isPlayer) continue;
            if (toRemove.contains(a.uuid)) continue;

            List<EntitySnapshot> neighbors = new ArrayList<>();
            neighbors.add(a);
            for (int j = 0; j < snaps.size(); j++) {
                if (i == j) continue;
                EntitySnapshot b = snaps.get(j);
                if (b == null) continue;
                if (b.isPlayer) continue;
                if (!a.world.equals(b.world)) continue;
                double dx = a.x - b.x;
                double dy = a.y - b.y;
                double dz = a.z - b.z;
                if (dx*dx + dy*dy + dz*dz <= rSq) neighbors.add(b);
            }

            if (neighbors.size() > threshold) {
                int toTrim = neighbors.size() - threshold;
                for (EntitySnapshot s : neighbors) {
                    if (toTrim <= 0) break;
                    if (!s.hasCustomName) {
                        toRemove.add(s.uuid);
                        toTrim--;
                    }
                }
                if (toTrim > 0) {
                    for (EntitySnapshot s : neighbors) {
                        if (toTrim <= 0) break;
                        if (s.hasCustomName) {
                            toRemove.add(s.uuid);
                            toTrim--;
                        }
                    }
                }
            }
        }

        return toRemove;
    }
}


