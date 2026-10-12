package com.testrts.rtsclient.build;

/** What a left click in the world does. */
public enum ToolMode {
    BUILD("Build", "Place the selected block, or draw a shape with it"),
    DESTROY("Destroy", "Mine a block, or clear a shape"),
    INTERACT("Interact", "Use a block: open chests, flip levers, press buttons"),
    LINK("Link storage", "Click containers to add or remove them from your storage network"),
    BLUEPRINT("Blueprint", "Copy a region (two clicks), then paste it anywhere, rotated with R"),
    TERRAIN("Terrain", "Flatten ground or light up an area (two clicks)"),
    MEASURE("Measure", "Click two points to see the distance and size between them"),
    PROTECT("Protect", "Mark areas the builder never breaks blocks in, like your base (two corners, all heights); click inside one to remove it");

    private final String label;
    private final String description;

    ToolMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public boolean usesShapes() {
        return this == BUILD || this == DESTROY;
    }

    /** Tools whose two clicks select a box (shapes do their own thing). */
    public boolean selectsRegion() {
        return this == TERRAIN || this == MEASURE || this == BLUEPRINT || this == PROTECT;
    }
}
