package com.testrts.rtsclient.build;

/** What the Rocks shape is made of (picked with the Inverted button / [I]). */
public enum RockLook {
    STONE("Stone", new String[]{"minecraft:stone", "minecraft:stone", "minecraft:andesite", "minecraft:cobblestone"}, null, null),
    MOSSY("Mossy", new String[]{"minecraft:mossy_cobblestone", "minecraft:stone", "minecraft:cobblestone", "minecraft:mossy_cobblestone"},
            "minecraft:moss_block", "minecraft:moss_carpet"),
    SANDY("Sandy", new String[]{"minecraft:sandstone", "minecraft:sandstone", "minecraft:smooth_sandstone", "minecraft:cut_sandstone"},
            "minecraft:smooth_sandstone", null),
    DARK("Dark", new String[]{"minecraft:deepslate", "minecraft:cobbled_deepslate", "minecraft:tuff", "minecraft:deepslate"}, null, null);

    private final String label;
    final String[] rock;
    /** The top face of each rock (null: the rock itself), and what may grow on it (null: nothing). */
    final String top;
    final String cover;

    RockLook(String label, String[] rock, String top, String cover) {
        this.label = label;
        this.rock = rock;
        this.top = top;
        this.cover = cover;
    }

    public String label() {
        return this.label;
    }

    public RockLook next() {
        RockLook[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
