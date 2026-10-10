package com.testrts.rtsclient.build;

/**
 * How the blocks of a mix brush are laid out in a shape. Every pattern is a pure function of the
 * position, so the preview shows exactly what gets built.
 */
public enum MixPattern {
    RANDOM("Random", "Scattered at random, in the proportions of the mix"),
    CLUSTERS("Clusters", "Patches of each block that blend into each other, like weathered stone"),
    GRADIENT("Gradient", "Bottom to top through the mix blocks in the order you added them, with a soft blend"),
    LAYERS("Layers", "Horizontal bands one block high, cycling through the mix blocks"),
    STRIPES("Stripes", "Diagonal vertical stripes, cycling through the mix blocks"),
    CHECKER("Checker", "3D checkerboard through the mix blocks");

    private final String label;
    private final String description;

    MixPattern(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public MixPattern next() {
        return values()[(ordinal() + 1) % values().length];
    }

    /**
     * Which entry to use at a position.
     *
     * @param weighted the mix with each block repeated by its weight
     * @param distinct the mix blocks once each, in the order they were added
     * @param minY     lowest Y of the shape (for {@link #GRADIENT})
     * @param maxY     highest Y of the shape
     * @return an index into {@code weighted} when {@link #usesWeights()}, else into {@code distinct}
     */
    public int pick(int x, int y, int z, int weighted, int distinct, int minY, int maxY) {
        return switch (this) {
            case RANDOM -> (int) Math.floorMod(hash(x, y, z), (long) weighted);
            case CLUSTERS -> Math.min(weighted - 1, (int) (noise(x / 4.0, y / 4.0, z / 4.0) * weighted));
            case GRADIENT -> {
                double t = (y - minY + 0.5) / (maxY - minY + 1);
                // Jitter around the band edges so the change isn't a hard line.
                t += (unit(hash(x, y, z)) - 0.5) * 0.9 / distinct;
                yield Math.max(0, Math.min(distinct - 1, (int) Math.floor(t * distinct)));
            }
            case LAYERS -> Math.floorMod(y, distinct);
            case STRIPES -> Math.floorMod(x + z, distinct);
            case CHECKER -> Math.floorMod(x + y + z, distinct);
        };
    }

    /** RANDOM and CLUSTERS follow the mix proportions; the others step through each block in turn. */
    public boolean usesWeights() {
        return this == RANDOM || this == CLUSTERS;
    }

    private static long hash(int x, int y, int z) {
        long h = (x * 3129871L) ^ (z * 116129781L) ^ y;
        h = h * h * 42317861L + h * 11L;
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 32;
        return h;
    }

    /** 0..1 from a hash. */
    private static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Smooth 3D value noise in 0..1. */
    private static double noise(double x, double y, double z) {
        int x0 = (int) Math.floor(x);
        int y0 = (int) Math.floor(y);
        int z0 = (int) Math.floor(z);
        double fx = smooth(x - x0);
        double fy = smooth(y - y0);
        double fz = smooth(z - z0);
        double v = 0.0;
        for (int i = 0; i < 8; i++) {
            int dx = i & 1;
            int dy = (i >> 1) & 1;
            int dz = (i >> 2) & 1;
            double w = (dx == 1 ? fx : 1 - fx) * (dy == 1 ? fy : 1 - fy) * (dz == 1 ? fz : 1 - fz);
            v += w * unit(hash(x0 + dx, y0 + dy, z0 + dz));
        }
        // Averaging squeezes values toward the middle; stretch them back out so every block shows up.
        return Math.max(0.0, Math.min(0.999999, (v - 0.5) * 1.8 + 0.5));
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);
    }
}
