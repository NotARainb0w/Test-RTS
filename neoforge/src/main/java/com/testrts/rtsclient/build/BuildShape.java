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
    PYRAMID("Pyramid", "Stepped pyramid centred on A, half width to B. Hollow: only the outside", true, false),
    CONE("Cone", "Cone on a disc centred on A through B, Height tall. Hollow: only the outside", true, true),
    TORUS("Torus", "Ring (doughnut) centred on A, radius to B; Height sets how thick the tube is", true, true),
    ARCH("Arch", "Arch standing between A and B; Height is how high it rises (0-1: a half circle). "
            + "Solid: filled above the arch, like a gateway", true, true),
    BRIDGE("Bridge", "Deck from A to B with a gentle hump, fence railings and lantern posts; Height sets the width. "
            + "The railing matches the block (oak planks: oak fence, stone bricks: stone brick wall). Hollow: deck only", true, true),
    STAIRS("Stairs", "Staircase from A toward B, one block up per block; Height sets the width. "
            + "Stair blocks face up the stairs. Solid: filled underneath", true, true),
    SPIRAL("Spiral", "Spiral staircase around A, radius to B, Height tall. Solid: with a centre pole", true, true),
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

    /** What the Height setting means for this shape. */
    public String heightLabel() {
        return switch (this) {
            case TORUS -> "Tube";
            case BRIDGE, STAIRS -> "Width";
            case ARCH -> "Rise";
            default -> "Height";
        };
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
