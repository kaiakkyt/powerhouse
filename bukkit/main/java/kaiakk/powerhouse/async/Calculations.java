package kaiakk.powerhouse.async;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import kaiakk.powerhouse.sync.ItemSnapshot;

public class Calculations {

    private static final ConcurrentHashMap<BlockKey, RedstoneData> redstoneStats = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockKey, Long> culledLocations = new ConcurrentHashMap<>();
    private static final java.util.Set<BlockKey> throttledLocations = Collections.newSetFromMap(new ConcurrentHashMap<BlockKey, Boolean>());
    private static final ConcurrentHashMap<BlockKey, Long> throttledTimestamps = new ConcurrentHashMap<>();
    
    private static final ConcurrentHashMap<BlockKey, Long> redstoneExceedTimestamps = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockKey, ParticleData> particleStats = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<BlockKey, Long> culledParticleLocations = new ConcurrentHashMap<>();
    
    private static final ConcurrentHashMap<BlockKey, Long> recentlyUnculled = new ConcurrentHashMap<>();

    private static class ParticleData {
        int count;
        long lastUpdate;
        ParticleData(int count, long lastUpdate) { this.count = count; this.lastUpdate = lastUpdate; }
    }

    private static class RedstoneData {
        int count;
        long lastUpdate;
        RedstoneData(int count, long lastUpdate) { this.count = count; this.lastUpdate = lastUpdate; }
    }
    
    public static Map<ItemSnapshot, List<ItemSnapshot>> scanItemMergeCandidatesSnapshots(List<ItemSnapshot> items) {
        if (items == null || items.size() < 2) return Collections.emptyMap();

        Map<ItemSnapshot, List<ItemSnapshot>> mergeGroups = new HashMap<>();
        java.util.Set<Integer> processed = new HashSet<>();

        Map<Long, List<ItemSnapshot>> spatialMap = new HashMap<>();
        for (ItemSnapshot s : items) {
            if (!isValidForMerging(s) || s.isPlayerNearby) continue;
            int gx = (int) Math.floor(s.x / 4.0);
            int gz = (int) Math.floor(s.z / 4.0);
            long gridKey = (((long) gx) << 32) | ((long) gz & 0xFFFFFFFFL);
            spatialMap.computeIfAbsent(gridKey, k -> new ArrayList<>()).add(s);
        }

        for (List<ItemSnapshot> sector : spatialMap.values()) {
            for (int i = 0; i < sector.size(); i++) {
                ItemSnapshot item1 = sector.get(i);
                if (item1 == null) continue;
                if (processed.contains(item1.originalIndex)) continue;

                List<ItemSnapshot> mergeList = new ArrayList<>();
                for (int j = i + 1; j < sector.size(); j++) {
                    ItemSnapshot item2 = sector.get(j);
                    if (item2 == null) continue;
                    if (processed.contains(item2.originalIndex)) continue;
                    if (!canItemsMerge(item1, item2)) continue;

                    double dx = item1.x - item2.x;
                    double dy = item1.y - item2.y;
                    double dz = item1.z - item2.z;

                    if ((dx * dx + dy * dy + dz * dz) <= 9.0) {
                        mergeList.add(item2);
                        processed.add(item2.originalIndex);
                    }
                }

                if (!mergeList.isEmpty()) {
                    mergeGroups.put(item1, mergeList);
                    processed.add(item1.originalIndex);
                }
            }
        }

        return mergeGroups;
    }
    private static boolean isValidForMerging(ItemSnapshot item) {
        if (item == null) return false;
        if (item.type == null || item.type == Material.AIR) return false;
        if (item.hasMeta) return false;
        return true;
    }
    
    private static boolean canItemsMerge(ItemSnapshot s1, ItemSnapshot s2) {
        if (s1.type != s2.type) return false;
        if (s1.durability != s2.durability) return false;
        int combined = s1.amount + s2.amount;
        return combined <= s1.maxStackSize;
    }
    
    public static int calculateMergedAmountSnapshot(ItemSnapshot target, List<ItemSnapshot> toMerge) {
        int total = target.amount;

        for (ItemSnapshot item : toMerge) {
            total += item.amount;
        }

        return Math.min(total, target.maxStackSize);
    }

    public static Map<kaiakk.powerhouse.sync.ExperienceOrbSnapshot, List<kaiakk.powerhouse.sync.ExperienceOrbSnapshot>> scanXpMergeCandidatesSnapshots(List<kaiakk.powerhouse.sync.ExperienceOrbSnapshot> orbs) {
        Map<kaiakk.powerhouse.sync.ExperienceOrbSnapshot, List<kaiakk.powerhouse.sync.ExperienceOrbSnapshot>> groups = new HashMap<>();
        if (orbs == null || orbs.isEmpty()) return groups;

        Set<kaiakk.powerhouse.sync.ExperienceOrbSnapshot> processed = new HashSet<>();
        for (int i = 0; i < orbs.size(); i++) {
            kaiakk.powerhouse.sync.ExperienceOrbSnapshot a = orbs.get(i);
            if (a == null) continue;
            if (processed.contains(a)) continue;

            List<kaiakk.powerhouse.sync.ExperienceOrbSnapshot> list = new ArrayList<>();
            for (int j = i + 1; j < orbs.size(); j++) {
                kaiakk.powerhouse.sync.ExperienceOrbSnapshot b = orbs.get(j);
                if (b == null) continue;
                double dx = a.x - b.x;
                double dy = a.y - b.y;
                double dz = a.z - b.z;
                if (dx*dx + dy*dy + dz*dz <= 4.0) {
                    list.add(b);
                    processed.add(b);
                }
            }

            if (!list.isEmpty()) {
                groups.put(a, list);
                processed.add(a);
            }
        }

        return groups;
    }

    public static int calculateMergedXpSnapshot(kaiakk.powerhouse.sync.ExperienceOrbSnapshot target, List<kaiakk.powerhouse.sync.ExperienceOrbSnapshot> toMerge) {
        int total = (target == null) ? 0 : target.experience;
        if (toMerge != null) {
            for (kaiakk.powerhouse.sync.ExperienceOrbSnapshot s : toMerge) {
                if (s == null) continue;
                total += s.experience;
            }
        }
        return total;
    }
    
    
    public static void recordBlockUpdate(Location location) {
        BlockKey key = BlockKey.from(location);
        if (key == null) return;

        long now = System.currentTimeMillis();
        redstoneStats.compute(key, (k, v) -> {
            if (v == null || now - v.lastUpdate > 1000) return new RedstoneData(1, now);
            v.count++;
            v.lastUpdate = now;
            return v;
        });
    }

    public static void markThrottled(Location location) {
        try {
            BlockKey key = BlockKey.from(location);
            if (key != null) {
                throttledLocations.add(key);
                try { throttledTimestamps.put(key, System.currentTimeMillis()); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
    }
    public static List<Location> drainThrottledLocations() {
        List<Location> out = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            final long MAX_AGE_MS = 60_000L; // drop very old entries to avoid leaks
            // Snapshot keys to iterate safely and clear immediately to avoid memory leaks
            List<BlockKey> keys = new ArrayList<>(throttledLocations);
            try { throttledLocations.clear(); } catch (Throwable ignored) {}

            for (BlockKey k : keys) {
                try {
                    Long stamped = throttledTimestamps.get(k);
                    if (stamped == null) stamped = 0L;
                    // purge very old entries
                    if (now - stamped > MAX_AGE_MS) {
                        try { throttledTimestamps.remove(k); } catch (Throwable ignored) {}
                        continue;
                    }

                    // Only process if the location is no longer culled
                    if (!culledLocations.containsKey(k)) {
                        try { throttledTimestamps.remove(k); } catch (Throwable ignored) {}
                        try {
                            Location l = k.toLocation();
                            if (l != null) out.add(l);
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void recordParticle(Location location) {
        BlockKey key = BlockKey.from(location);
        if (key == null) return;

        long now = System.currentTimeMillis();
        particleStats.compute(key, (k, v) -> {
            if (v == null || now - v.lastUpdate > 1000) return new ParticleData(1, now);
            v.count++;
            v.lastUpdate = now;
            return v;
        });
    }
    
    public static List<Location> scanRedstoneCullingCandidates() {
        return scanRedstoneCullingCandidates(15);
    }

    public static List<Location> scanRedstoneCullingCandidates(int dynamicThreshold) {
        List<Location> toCull = new ArrayList<>();
        long now = System.currentTimeMillis();

        redstoneStats.entrySet().removeIf(entry -> {
            BlockKey key = entry.getKey();
            RedstoneData data = entry.getValue();
            if (data == null) return true;

            if (now - data.lastUpdate > 5000) {
                return true;
            }

            if (data.count > dynamicThreshold && (now - data.lastUpdate) <= 1000) {
                if (!culledLocations.containsKey(key)) {
                    Location loc = key.toLocation();
                    if (loc != null) toCull.add(loc);
                }
            }
            return false;
        });

        return toCull;
    }

    public static java.util.Map<Location, Integer> scanRedstoneCullingCandidatesWithCounts(int minUpdates) {
        java.util.Map<Location, Integer> results = new java.util.HashMap<Location, Integer>();
        long now = System.currentTimeMillis();
        final long GRACE_MS = 3000L; 

        for (Map.Entry<BlockKey, RedstoneData> entry : redstoneStats.entrySet()) {
            BlockKey key = entry.getKey();
            RedstoneData data = entry.getValue();
            if (data == null) continue;

            
            if (now - data.lastUpdate <= 1000 && data.count >= minUpdates) {
                
                if (culledLocations.containsKey(key)) continue;

                Long first = redstoneExceedTimestamps.get(key);
                if (first == null) {
                    redstoneExceedTimestamps.put(key, now);
                    continue;
                }

                if (now - first >= GRACE_MS) {
                    Location loc = key.toLocation();
                    if (loc != null) results.put(loc, data.count);
                }
            } else {
                
                redstoneExceedTimestamps.remove(key);
            }
        }

        
        redstoneStats.entrySet().removeIf(en -> en.getValue() == null || (now - en.getValue().lastUpdate) > 5000);

        return results;
    }

    public static java.util.Map<Location, Integer> scanParticleCullingCandidatesWithCounts(int minUpdates) {
        java.util.Map<Location, Integer> results = new java.util.HashMap<Location, Integer>();
        long now = System.currentTimeMillis();
        for (Map.Entry<BlockKey, ParticleData> entry : particleStats.entrySet()) {
            BlockKey key = entry.getKey();
            ParticleData data = entry.getValue();
            if (data == null) continue;
            if (now - data.lastUpdate <= 1000 && data.count >= minUpdates) {
                Location loc = key.toLocation();
                if (loc != null) results.put(loc, data.count);
            }
        }

        particleStats.entrySet().removeIf(en -> en.getValue() == null || (now - en.getValue().lastUpdate) > 5000);

        return results;
    }
    
    public static void markLocationCulled(Location location) {
        BlockKey key = BlockKey.from(location);
        if (key != null) {
            culledLocations.put(key, System.currentTimeMillis());
            redstoneExceedTimestamps.remove(key);
        }
    }

    public static void markParticleLocationCulled(Location location) {
        BlockKey key = BlockKey.from(location);
        if (key != null) culledParticleLocations.put(key, System.currentTimeMillis());
    }

    public static boolean isParticleLocationCulled(Location location) {
        BlockKey key = BlockKey.from(location);
        return key != null && culledParticleLocations.containsKey(key);
    }

    
    public static boolean isBlockCulled(Block block) {
        if (block == null || block.getWorld() == null) return false;
        BlockKey key = new BlockKey(block.getX(), block.getY(), block.getZ(), block.getWorld().getName());
        return culledLocations.containsKey(key);
    }

    public static boolean isLocationCulled(Location location) {
        BlockKey key = BlockKey.from(location);
        if (key == null) return false;
        Long exempt = recentlyUnculled.get(key);
        if (exempt != null && System.currentTimeMillis() < exempt) return false;
        return culledLocations.containsKey(key);
    }

    public static List<Location> getExpiredCulledLocations() {
        List<Location> expired = new ArrayList<>();
        long now = System.currentTimeMillis();
        
        culledLocations.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > 10000) {
                Location loc = entry.getKey().toLocation();
                if (loc != null) expired.add(loc);
                return true;
            }
            return false;
        });
        return expired;
    }

    public static void uncullLocation(Location location) {
        BlockKey key = BlockKey.from(location);
        if (key == null) return;
        culledLocations.remove(key);
        redstoneExceedTimestamps.remove(key);
        redstoneStats.remove(key);
        
        recentlyUnculled.put(key, System.currentTimeMillis() + 2000L);
    }

    public static void uncullAll() {
        culledLocations.clear();
        recentlyUnculled.clear();
    }

    public static List<Location> getExpiredParticleCulledLocations() {
        List<Location> expired = new ArrayList<>();
        long now = System.currentTimeMillis();

        culledParticleLocations.entrySet().removeIf(entry -> {
            if (now - entry.getValue() > 30000) {
                Location loc = entry.getKey().toLocation();
                if (loc != null) expired.add(loc);
                return true;
            }
            return false;
        });
        return expired;
    }

    public static void uncullParticleLocation(Location location) {
        BlockKey key = BlockKey.from(location);
        if (key == null) return;
        culledParticleLocations.remove(key);
        particleStats.remove(key);
    }

    public static void uncullAllParticles() {
        culledParticleLocations.clear();
    }
    
    public static Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("monitored_locations", redstoneStats.size());
        stats.put("culled_locations", culledLocations.size());
        stats.put("total_tracked", redstoneStats.size());
        stats.put("particle_tracked", particleStats.size());
        stats.put("particle_culled", culledParticleLocations.size());
        return stats;
    }
    
    public static void clearAllData() {
        redstoneStats.clear();
        culledLocations.clear();
        particleStats.clear();
        culledParticleLocations.clear();
        recentlyUnculled.clear();
    }

    public static void clearWorldData(String worldName) {
        if (worldName == null) return;
        try {
            redstoneStats.entrySet().removeIf(en -> en.getKey() != null && worldName.equals(en.getKey().worldName));
        } catch (Throwable ignored) {}
        try {
            culledLocations.entrySet().removeIf(en -> en.getKey() != null && worldName.equals(en.getKey().worldName));
        } catch (Throwable ignored) {}
        try {
            recentlyUnculled.entrySet().removeIf(en -> en.getKey() != null && worldName.equals(en.getKey().worldName));
        } catch (Throwable ignored) {}
        try {
            particleStats.entrySet().removeIf(en -> en.getKey() != null && worldName.equals(en.getKey().worldName));
        } catch (Throwable ignored) {}
        try {
            culledParticleLocations.entrySet().removeIf(en -> en.getKey() != null && worldName.equals(en.getKey().worldName));
        } catch (Throwable ignored) {}
    }

    
    public static void applyListenerCounts(java.util.Map<BlockKey, Integer> counts) {
        if (counts == null || counts.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (java.util.Map.Entry<BlockKey, Integer> e : counts.entrySet()) {
            BlockKey key = e.getKey();
            int delta = e.getValue() == null ? 0 : e.getValue();
            if (delta <= 0) continue;
            redstoneStats.compute(key, (k, v) -> {
                if (v == null || now - v.lastUpdate > 1000) return new RedstoneData(delta, now);
                v.count += delta;
                v.lastUpdate = now;
                return v;
            });
        }
    }
}


