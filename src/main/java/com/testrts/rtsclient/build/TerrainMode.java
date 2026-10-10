package com.testrts.rtsclient.build;

/** What the Terrain tool does with the area you select. */
public enum TerrainMode {
    FLATTEN("Flatten", "Level the area to the height of your first click: mine everything above, fill holes below"),
    LIGHT_UP("Light up", "Place torches wherever the area is dark enough for mobs to spawn"),
    QUARRY("Quarry", "Dig the area out layer by layer, down to the depth you set; storage trips when the inventory is full"),
    FELL_TREES("Fell trees", "Chop every tree in the area, pick up the wood and replant saplings"),
    HARVEST("Harvest", "Harvest ripe crops in the area, replant them and store the produce"),
    PLANT_TREES("Plant trees", "Plant saplings spread out over the area, with room for every tree to grow "
            + "(the brush sapling, or the one you have most of; dark oak goes in 2x2)");

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

    public TerrainMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
