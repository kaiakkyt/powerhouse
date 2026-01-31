package kaiakk.powerhouse.data.snapshot;

public class ExperienceOrbSnapshot {
    public final int originalIndex;
    public final double x, y, z;
    public final int experience;

    public ExperienceOrbSnapshot(int originalIndex, double x, double y, double z, int experience) {
        this.originalIndex = originalIndex;
        this.x = x;
        this.y = y;
        this.z = z;
        this.experience = experience;
    }
}


