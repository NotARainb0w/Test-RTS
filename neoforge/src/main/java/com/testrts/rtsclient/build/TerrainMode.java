package com.testrts.rtsclient.build;

/** What the Terrain tool does with the area you select. */
public enum TerrainMode {
    FLATTEN("Flatten", "Level the area to the height of your first click: mine everything above, fill holes below"),
    BLEND("Blend", "Paint over rough ground with the brush (drag; Shift+drag erases), then Enter: bumps are mined, "
            + "dips filled, so it slopes smoothly into the land around it. Only natural ground is changed"),
    LEVEL("Level", "Paint over ground with the brush (drag; Shift+drag erases), then Enter: it is mined or filled flat "
            + "to one height (where you start painting, -/+ to change), with sloped edges into the land around it"),
    LIGHT_UP("Light up", "Place torches wherever the area is dark enough for mobs to spawn"),
    QUARRY("Quarry", "Dig the area out layer by layer, down to the depth you set; storage trips when the inventory is full"),
    FELL_TREES("Fell trees", "Chop every tree in the area, pick up the wood and replant saplings"),
    HARVEST("Harvest", "Harvest ripe crops in the area, replant them and store the produce"),
    PLANT_TREES("Plant trees", "Plant saplings spread out over the area, with room for every tree to grow "
            + "(the brush sapling, or the one you have most of; dark oak goes in 2x2)"),
    ROAD("Road", "Click two points: a road between them that follows the ground (stairs where it climbs), borders, "
            + "lanterns every 8 blocks and a bridge on posts over water or drops. The brush is the road block"),
    ROOM("Furnish", "Click two floor corners inside a room: floor where it's missing, a lantern in each corner, "
            + "torches on the walls and chests along the far wall"),
    FARM("Farm", "Two corners: a fenced field with water channels every 9 rows, hoed and sown (needs a hoe, a water "
            + "bucket or two and seeds - the brush if it's a seed)"),
    MOB_PROOF("Mob-proof", "Cover every dark spot in the area with a slab (or the brush if it's a slab, carpet or button) "
            + "so nothing can spawn there"),
    RESTYLE("Restyle", "Two corners round queued builds: redo them in the brush's material (blocks, stairs, slabs, "
            + "walls keep their shape and facing)"),
    HILL("Hill", "Two corners: a hill on the oval between them, Height blocks high in the middle (grass, dirt, stone; "
            + "snow on top past 20)"),
    LAKE("Lake", "Two corners: dig an oval basin Depth deep with sand at the edge and clay below, and fill it with "
            + "water buckets"),
    RIVER("River", "Two points: a winding riverbed Width wide between them, filled with water buckets"),
    TERRACE("Terrace", "Two corners on a slope: cut it into flat grass steps Step high, from the first corner down "
            + "(or up) to the second"),
    CAVE("Cave", "Two points: a rounded tunnel Size across between them, flat floor, torches every 8 blocks"),
    ISLAND_BRIDGE("Island bridge", "Click one island, then another: a bridge between their nearest edges, in the "
            + "Bridge look picked in the Build tool"),
    DECORATE("Decorate", "Two corners: grass, flowers, ferns, little bushes and rocks scattered over the grass "
            + "(Density: how thick)");

    private final String label;
    private final String description;

    TerrainMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    /** What the size setting means for this mode, or null when it has none of its own. */
    public String sizeLabel() {
        return switch (this) {
            case ROAD, RIVER, ISLAND_BRIDGE -> "WIDTH";
            case HILL -> "HEIGHT";
            case LAKE -> "DEPTH";
            case TERRACE -> "STEP";
            case CAVE -> "SIZE";
            case DECORATE -> "DENSITY";
            default -> null;
        };
    }

    /** Two points (a line) rather than two corners (an area). */
    public boolean line() {
        return this == ROAD || this == RIVER || this == CAVE || this == ISLAND_BRIDGE;
    }

    /** Painted with a brush (drag) instead of two corners. */
    public boolean brush() {
        return this == BLEND || this == LEVEL;
    }

    public TerrainMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
