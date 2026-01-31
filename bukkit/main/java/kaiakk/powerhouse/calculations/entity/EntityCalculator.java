package kaiakk.powerhouse.calculations.entity;

import org.bukkit.entity.EntityType;

import kaiakk.powerhouse.data.snapshot.EntitySnapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class EntityCalculator {

	public static Set<UUID> calculateCullCandidates(List<EntitySnapshot> snapshots, Map<String, List<double[]>> playersByWorld, int maxPerChunk, double maxDistance) {
		Set<UUID> toRemove = new HashSet<>();
		if (snapshots == null || snapshots.isEmpty()) return toRemove;

		Map<Long, List<EntitySnapshot>> chunks = new HashMap<>();
		double maxDistSq = maxDistance * maxDistance;

		for (EntitySnapshot s : snapshots) {
			if (s == null || s.isPlayer || s.type == EntityType.ENDER_DRAGON || s.type == EntityType.WITHER) continue;

			int cx = ((int) Math.floor(s.x)) >> 4;
			int cz = ((int) Math.floor(s.z)) >> 4;
			long chunkKey = (((long) s.world.hashCode()) << 32) | ((cx & 0xFFFFL) << 16) | (cz & 0xFFFFL);

			chunks.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(s);

			if (maxDistance > 0 && !s.hasCustomName) {
				List<double[]> worldPlayers = playersByWorld.get(s.world);
				if (worldPlayers != null) {
					boolean near = false;
					for (double[] p : worldPlayers) {
						double dx = s.x - p[0];
						double dz = s.z - p[2];
						if (dx*dx + dz*dz <= maxDistSq) { near = true; break; }
					}
					if (!near && s.worth <= 50) toRemove.add(s.uuid);
				}
			}
		}

		for (List<EntitySnapshot> list : chunks.values()) {
			if (list.size() <= maxPerChunk) continue;
			
			list.sort((a, b) -> Integer.compare(a.worth, b.worth));
			int toTrim = list.size() - maxPerChunk;
			for (int i = 0; i < toTrim; i++) {
				toRemove.add(list.get(i).uuid);
			}
		}

		return toRemove;
	}
}



