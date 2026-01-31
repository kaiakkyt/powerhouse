package kaiakk.powerhouse.calculations;

import org.bukkit.Location;

import java.util.List;

public class ExplosionCalculation {

	public static int countWithinSq(List<Location> points, Location center, double radiusSq) {
		if (points == null || center == null) return 0;
		int c = 0;
		double cx = center.getX();
		double cy = center.getY();
		double cz = center.getZ();
		for (Location l : points) {
			if (l == null) continue;
			double dx = l.getX() - cx;
			double dy = l.getY() - cy;
			double dz = l.getZ() - cz;
			if (dx*dx + dy*dy + dz*dz <= radiusSq) c++;
		}
		return c;
	}
}



