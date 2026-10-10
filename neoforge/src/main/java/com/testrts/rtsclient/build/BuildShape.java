package com.testrts.rtsclient.build;

/** Geometry for build/destroy orders. Every shape except BLOCK is defined by two clicks. */
public enum BuildShape {
    BLOCK("Block", "One block per click", false, false),
    LINE("Line", "Straight line between two points", false, false),
    FLOOR("Floor", "Flat rectangle at the first point's height", true, false),
    WALL("Wall", "Vertical wall from A to B, Height tall", true, true),
    BOX("Box", "Cuboid between A and B (at least Height tall)", true, true),
    CIRCLE("Circle", "Disc centred on A through B", true, false),
    CYLINDER("Cylinder", "Disc centred on A, Height tall", true, true),
    SPHERE("Sphere", "Ball centred on A through B", true, false),
    DOME("Dome", "Half sphere standing on A's level, radius to B", true, false),
    VEIN("Vein", "Every touching block of the clicked type (mine a vein, or replace it with the brush)", false, false),
    HOLE("Hole", "Every empty or water block in the enclosed hole you click, up to that level (fills pits, ponds)", false, false);

    private final String label;
    private final String description;
    private final boolean supportsHollow;
    private final boolean usesHeight;

    BuildShape(String label, String description, boolean supportsHollow, boolean usesHeight) {
        this.label = label;
        this.description = description;
        this.supportsHollow = supportsHollow;
        this.usesHeight = usesHeight;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public boolean supportsHollow() {
        return this.supportsHollow;
    }

    public boolean usesHeight() {
        return this.usesHeight;
    }

    /** Shapes placed with one click instead of two. */
    public boolean singleClick() {
        return this == BLOCK || this == VEIN || this == HOLE;
    }

    public BuildShape next() {
        BuildShape[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public BuildShape previous() {
        BuildShape[] values = values();
        return values[(ordinal() + values.length - 1) % values.length];
    }
}
