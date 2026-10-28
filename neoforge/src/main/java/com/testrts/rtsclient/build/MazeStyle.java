package com.testrts.rtsclient.build;

/** What the Maze shape's walls are made of (picked with the Style button). */
public enum MazeStyle {
    HEDGE("Hedge", "Leafy hedges with the odd flowering bush",
            new String[]{"minecraft:oak_leaves[persistent=true]", "minecraft:oak_leaves[persistent=true]", "minecraft:oak_leaves[persistent=true]",
                    "minecraft:azalea_leaves[persistent=true]", "minecraft:flowering_azalea_leaves[persistent=true]"}, null, null),
    STONE("Stone", "Old stone brick, mossy and cracked in places",
            new String[]{"minecraft:stone_bricks", "minecraft:stone_bricks", "minecraft:mossy_stone_bricks", "minecraft:cracked_stone_bricks"},
            "minecraft:chiseled_stone_bricks", "minecraft:lantern"),
    SANDSTONE("Sandstone", "Desert sandstone walls",
            new String[]{"minecraft:sandstone", "minecraft:cut_sandstone", "minecraft:smooth_sandstone"}, "minecraft:chiseled_sandstone",
            "minecraft:lantern"),
    QUARTZ("Quartz", "Clean white quartz",
            new String[]{"minecraft:quartz_block", "minecraft:quartz_bricks", "minecraft:smooth_quartz"}, "minecraft:chiseled_quartz_block",
            "minecraft:lantern"),
    DEEPSLATE("Deepslate", "Dark deepslate brick and tile",
            new String[]{"minecraft:deepslate_bricks", "minecraft:deepslate_tiles", "minecraft:cracked_deepslate_bricks", "minecraft:polished_deepslate"},
            null, "minecraft:soul_lantern"),
    NETHER("Nether", "Nether brick, red and black",
            new String[]{"minecraft:nether_bricks", "minecraft:nether_bricks", "minecraft:red_nether_bricks", "minecraft:cracked_nether_bricks"},
            null, "minecraft:soul_lantern"),
    PRISMARINE("Prismarine", "Ocean-temple prismarine",
            new String[]{"minecraft:prismarine", "minecraft:prismarine_bricks", "minecraft:dark_prismarine"}, null, "minecraft:sea_lantern"),
    RANDOM("Random", "A different look every design", new String[]{"minecraft:stone_bricks"}, null, null);

    private final String label;
    private final String description;
    final String[] walls;
    /** The top row of the walls (null: the walls themselves), and what sometimes stands on a corner post (null: nothing). */
    final String cap;
    final String light;

    MazeStyle(String label, String description, String[] walls, String cap, String light) {
        this.label = label;
        this.description = description;
        this.walls = walls;
        this.cap = cap;
        this.light = light;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public MazeStyle next() {
        MazeStyle[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
