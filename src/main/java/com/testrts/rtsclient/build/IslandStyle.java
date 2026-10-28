package com.testrts.rtsclient.build;

/**
 * Looks for the Island shape: the blocks of the top, the soil under it, the rock of the underside,
 * what grows on top and what hangs underneath. Islands use their own materials, not the brush.
 */
public enum IslandStyle {
    GRASSY("Grassy", "Grass and dirt on a stone underside with ores, oak and birch trees, flowers, hanging roots",
            "minecraft:grass_block[snowy=false]",
            new String[]{"minecraft:dirt", "minecraft:dirt", "minecraft:coarse_dirt", "minecraft:rooted_dirt"},
            new String[]{"minecraft:stone", "minecraft:stone", "minecraft:andesite", "minecraft:cobblestone", "minecraft:tuff",
                    "minecraft:mossy_cobblestone"},
            new String[]{"minecraft:coal_ore", "minecraft:iron_ore", "minecraft:copper_ore"},
            new String[]{"minecraft:grass", "minecraft:grass", "minecraft:grass", "minecraft:grass",
                    "minecraft:poppy", "minecraft:dandelion", "minecraft:oxeye_daisy", "minecraft:cornflower", "minecraft:azure_bluet"},
            0.28, Tree.OAK, "minecraft:hanging_roots"),
    SNOWY("Snowy", "Snowy grass on stone and packed ice, spruce trees, a layer of snow",
            "minecraft:grass_block[snowy=true]",
            new String[]{"minecraft:dirt", "minecraft:coarse_dirt"},
            new String[]{"minecraft:stone", "minecraft:stone", "minecraft:andesite", "minecraft:diorite", "minecraft:packed_ice"},
            new String[]{"minecraft:coal_ore", "minecraft:iron_ore"},
            new String[]{"minecraft:snow[layers=1]"},
            0.85, Tree.SPRUCE, null),
    DESERT("Desert", "Sand on sandstone and bands of terracotta, cactus and dead bushes",
            "minecraft:sand",
            new String[]{"minecraft:sand", "minecraft:sand", "minecraft:sandstone"},
            new String[]{"minecraft:terracotta", "minecraft:orange_terracotta", "minecraft:yellow_terracotta", "minecraft:white_terracotta",
                    "minecraft:red_terracotta", "minecraft:sandstone"},
            new String[]{"minecraft:gold_ore"},
            new String[]{"minecraft:dead_bush"},
            0.05, Tree.CACTUS, null),
    CHERRY("Cherry", "Grass on calcite and stone, pink cherry trees, petals, hanging roots",
            "minecraft:grass_block[snowy=false]",
            new String[]{"minecraft:dirt", "minecraft:dirt", "minecraft:rooted_dirt"},
            new String[]{"minecraft:stone", "minecraft:calcite", "minecraft:diorite", "minecraft:andesite"},
            new String[]{"minecraft:iron_ore", "minecraft:copper_ore"},
            new String[]{"minecraft:pink_petals[facing=north,flower_amount=4]", "minecraft:pink_petals[facing=east,flower_amount=3]",
                    "minecraft:grass"},
            0.3, Tree.CHERRY, "minecraft:hanging_roots"),
    MUSHROOM("Mushroom", "Mycelium on stone and tuff, huge red and brown mushrooms",
            "minecraft:mycelium[snowy=false]",
            new String[]{"minecraft:dirt", "minecraft:dirt"},
            new String[]{"minecraft:stone", "minecraft:tuff", "minecraft:andesite", "minecraft:cobblestone"},
            new String[]{"minecraft:coal_ore", "minecraft:iron_ore"},
            new String[]{"minecraft:red_mushroom", "minecraft:brown_mushroom"},
            0.08, Tree.MUSHROOM, null),
    CRIMSON("Crimson", "Crimson nylium on netherrack and blackstone, crimson fungi trees with shroomlights, weeping vines",
            "minecraft:crimson_nylium",
            new String[]{"minecraft:netherrack"},
            new String[]{"minecraft:netherrack", "minecraft:netherrack", "minecraft:blackstone", "minecraft:basalt[axis=y]"},
            new String[]{"minecraft:nether_quartz_ore", "minecraft:nether_gold_ore"},
            new String[]{"minecraft:crimson_roots", "minecraft:crimson_roots", "minecraft:crimson_fungus"},
            0.3, Tree.CRIMSON, "minecraft:weeping_vines");

    /** What grows on top. */
    public enum Tree { OAK, SPRUCE, CACTUS, CHERRY, MUSHROOM, CRIMSON }

    private final String label;
    private final String description;
    final String top;
    final String[] soil;
    final String[] stone;
    final String[] ores;
    final String[] cover;
    /** Share of the free top blocks that get a plant. */
    final double coverChance;
    final Tree tree;
    /** Hangs from the underside (null: nothing). */
    final String hanging;

    IslandStyle(String label, String description, String top, String[] soil, String[] stone, String[] ores, String[] cover,
                double coverChance, Tree tree, String hanging) {
        this.label = label;
        this.description = description;
        this.top = top;
        this.soil = soil;
        this.stone = stone;
        this.ores = ores;
        this.cover = cover;
        this.coverChance = coverChance;
        this.tree = tree;
        this.hanging = hanging;
    }

    public String label() {
        return this.label;
    }

    public String description() {
        return this.description;
    }

    public IslandStyle next() {
        IslandStyle[] all = values();
        return all[(ordinal() + 1) % all.length];
    }
}
