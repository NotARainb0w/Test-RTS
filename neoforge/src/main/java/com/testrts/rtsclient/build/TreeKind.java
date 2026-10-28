package com.testrts.rtsclient.build;

/** What kind of tree the Giant tree shape grows (picked with the Style button). */
public enum TreeKind {
    OAK("Oak", "Thick trunk, spreading branches and round leaf clusters", "minecraft:oak_log", "minecraft:oak_leaves"),
    BIRCH("Birch", "Slim white trunk with leaf clusters", "minecraft:birch_log", "minecraft:birch_leaves"),
    SPRUCE("Spruce", "A tall cone of dark needles", "minecraft:spruce_log", "minecraft:spruce_leaves"),
    DARK_OAK("Dark oak", "A fat trunk and a wide, flat crown", "minecraft:dark_oak_log", "minecraft:dark_oak_leaves"),
    CHERRY("Cherry", "Spreading branches covered in pink blossom", "minecraft:cherry_log", "minecraft:cherry_leaves"),
    JUNGLE("Jungle", "Very tall, a big leafy top and a few side crowns", "minecraft:jungle_log", "minecraft:jungle_leaves"),
    ACACIA("Acacia", "A bent trunk with flat umbrella crowns", "minecraft:acacia_log", "minecraft:acacia_leaves"),
    WILLOW("Weeping willow", "A red mangrove trunk under a dome with a curtain of hanging leaves", "minecraft:mangrove_log",
            "minecraft:mangrove_leaves"),
    DEAD("Dead", "Bare, twisted branches, no leaves", "minecraft:spruce_log", null),
    RANDOM("Random", "A different kind of tree every design", "minecraft:oak_log", "minecraft:oak_leaves");

    private final String label;
    private final String description;
    final String log;
    /** Null: no leaves. */
    final String leaves;

    TreeKind(String label, String description, String log, String leaves) {
        this.label = label;
        this.description = description;
        this.log = log;
        this.leaves = leaves;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public TreeKind next() {
        TreeKind[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
