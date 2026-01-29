package kaiakk.powerhouse.sync;

public final class ScaleUtils {
    private ScaleUtils() {}

    public static double continuousScaleFromMspt(double mspt) {
        if (mspt <= 0.0) return 1.0;
        final double mid = 30.0;
        final double steep = 0.20;
        double v = 1.0 / (1.0 + Math.exp(steep * (mspt - mid)));
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static volatile double ewmaMspt = 20.0;
    private static volatile double EWMA_ALPHA = 0.05;
    private static volatile double ONE_MINUS_ALPHA = 1.0 - EWMA_ALPHA;

    public static double updateSmoothedMspt(double sampleMspt) {
        if (sampleMspt <= 0.0) return getSmoothedMspt();
        double prev = ewmaMspt;
        double next = (sampleMspt * EWMA_ALPHA) + (prev * ONE_MINUS_ALPHA);
        ewmaMspt = next;
        return next;
    }

    public static double getSmoothedMspt() {
        try {
            if (kaiakk.powerhouse.sync.FoliaChecker.isFolia(null)) return 0.0;
        } catch (Throwable ignored) {}
        return ewmaMspt;
    }

    public static void setEwmaAlpha(double alpha) {
        if (alpha <= 0.0 || alpha > 1.0) return;
        EWMA_ALPHA = alpha;
        ONE_MINUS_ALPHA = 1.0 - alpha;
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static int lerpInt(int a, int b, double t) {
        return (int) Math.round(lerp(a, b, t));
    }

    public static double multiplierFromScale(double scale, double minMultiplier, double maxMultiplier) {
        if (Double.isNaN(scale)) return 1.0;
        double t = Math.max(0.0, Math.min(1.0, scale));
        return lerp(minMultiplier, maxMultiplier, t);
    }

    public static double multiplierFromMspt(double mspt, double minMultiplier, double maxMultiplier) {
        double s = continuousScaleFromMspt(mspt);
        return multiplierFromScale(s, minMultiplier, maxMultiplier);
    }
}


