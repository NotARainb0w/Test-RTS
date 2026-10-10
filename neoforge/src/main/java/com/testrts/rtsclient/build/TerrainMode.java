package com.testrts.rtsclient.build;

/** What the Terrain tool does with the area you select. */
public enum TerrainMode {
    FLATTEN("Flatten", "Level the area to the height of your first click: mine everything above, fill holes below"),
    LIGHT_UP("Light up", "Place torches wherever the area is dark enough for mobs to spawn");

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
