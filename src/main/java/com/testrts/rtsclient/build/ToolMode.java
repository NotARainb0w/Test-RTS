package com.testrts.rtsclient.build;

/** What a left click in the world does. */
public enum ToolMode {
    BUILD("Build", "Place the selected block, or draw a shape with it"),
    DESTROY("Destroy", "Mine a block, or clear a shape"),
    INTERACT("Interact", "Use a block: open chests, flip levers, press buttons"),
    LINK("Link storage", "Click containers to add or remove them from your storage network"),
    BLUEPRINT("Blueprint", "Copy a region (two clicks), then paste it anywhere, rotated with R");

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
}
