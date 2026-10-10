package com.testrts.rtsclient.build;

/** How a Build order treats blocks that are already in the shape. */
public enum BuildMode {
    FILL("Fill", "Only fill empty space; existing blocks are left alone"),
    REPLACE("Replace", "Every block in the shape becomes the brush (existing blocks are mined first)"),
    REPLACE_TYPE("Replace type", "Only blocks of the type you click first are swapped for the brush"),
    COVER("Cover", "Only empty spaces resting on solid ground: carpets, paths, snow layers over terrain");

    private final String label;
    private final String description;

    BuildMode(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    /** Replace modes click on existing blocks; the others on the space in front of them. */
    public boolean targetsExisting() {
        return this == REPLACE || this == REPLACE_TYPE;
    }

    public BuildMode next() {
        return values()[(ordinal() + 1) % values().length];
    }
}
