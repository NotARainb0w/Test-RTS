package com.testrts.rtsclient.build;

/** What the Backrooms shape looks like (picked with the Style button). */
public enum BackroomsLevel {
    LEVEL_0("Level 0", "Yellow walls, damp carpet, buzzing lights",
            new String[]{"minecraft:smooth_sandstone", "minecraft:smooth_sandstone", "minecraft:smooth_sandstone", "minecraft:cut_sandstone"},
            new String[]{"minecraft:yellow_terracotta", "minecraft:yellow_terracotta", "minecraft:yellow_terracotta", "minecraft:brown_terracotta"},
            new String[]{"minecraft:white_terracotta"}, "minecraft:ochre_froglight[axis=y]"),
    POOLROOMS("Poolrooms", "White tiles everywhere, bright and empty",
            new String[]{"minecraft:white_concrete", "minecraft:white_concrete", "minecraft:smooth_quartz"},
            new String[]{"minecraft:white_concrete", "minecraft:white_concrete", "minecraft:light_blue_terracotta"},
            new String[]{"minecraft:white_concrete"}, "minecraft:sea_lantern"),
    WAREHOUSE("Level 1", "Grey concrete halls like a warehouse or car park",
            new String[]{"minecraft:gray_concrete", "minecraft:light_gray_concrete", "minecraft:stone"},
            new String[]{"minecraft:polished_andesite", "minecraft:andesite", "minecraft:gray_concrete"},
            new String[]{"minecraft:light_gray_concrete"}, "minecraft:sea_lantern"),
    OFFICE("Office", "Endless empty offices",
            new String[]{"minecraft:white_concrete", "minecraft:white_concrete", "minecraft:calcite"},
            new String[]{"minecraft:gray_terracotta", "minecraft:gray_terracotta", "minecraft:cyan_terracotta"},
            new String[]{"minecraft:smooth_quartz"}, "minecraft:glowstone"),
    RED("Red rooms", "Red walls under a dim red glow",
            new String[]{"minecraft:red_terracotta", "minecraft:red_terracotta", "minecraft:red_concrete"},
            new String[]{"minecraft:red_nether_bricks", "minecraft:brown_terracotta"},
            new String[]{"minecraft:red_terracotta"}, "minecraft:shroomlight"),
    RANDOM("Random", "A different level every design", new String[]{"minecraft:smooth_sandstone"}, new String[]{"minecraft:yellow_terracotta"},
            new String[]{"minecraft:white_terracotta"}, "minecraft:sea_lantern");

    private final String label;
    private final String description;
    final String[] walls;
    final String[] floor;
    final String[] ceiling;
    final String light;

    BackroomsLevel(String label, String description, String[] walls, String[] floor, String[] ceiling, String light) {
        this.label = label;
        this.description = description;
        this.walls = walls;
        this.floor = floor;
        this.ceiling = ceiling;
        this.light = light;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public BackroomsLevel next() {
        BackroomsLevel[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
