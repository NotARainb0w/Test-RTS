package com.testrts.rtsclient.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Ready-made buildings, generated as blueprints so they place like any other blueprint (follow the
 * cursor, rotate, mirror, material list, built layer by layer). The front of each building is its
 * north side (z = 0); doors and furnaces face accordingly and turn with the blueprint.
 */
public final class Presets {
    public record Preset(String name, String description, Item icon, Supplier<Blueprint> build) {
    }

    private Presets() {
    }

    public static List<Preset> all() {
        return List.of(
                new Preset("Starter house", "7x7 oak cabin: windows, door, gable roof, crafting table, furnace and chests",
                        Items.OAK_DOOR, Presets::house),
                new Preset("Smelter room", "9x7 stone-brick room with a wall of furnaces, a blast furnace, a smoker and chests",
                        Items.FURNACE, Presets::smelter),
                new Preset("Storage room", "7x6 spruce room lined with double chests and barrels",
                        Items.CHEST, Presets::storage),
                new Preset("Enchanting room", "Enchanting table with a full ring of bookshelves (level 30)",
                        Items.ENCHANTING_TABLE, Presets::enchanting),
                new Preset("Workshop", "Open 7x5 shed with crafting, smithing, stonecutter, anvil, furnace and chest",
                        Items.CRAFTING_TABLE, Presets::workshop),
                new Preset("Watchtower", "5x5 cobblestone tower with a ladder, a lookout platform and battlements",
                        Items.LADDER, Presets::watchtower),
                new Preset("Animal pen", "9x9 fenced pen with a gate and lanterns on the corners",
                        Items.OAK_FENCE_GATE, Presets::pen),
                new Preset("Nether portal", "Obsidian frame, ready to light",
                        Items.OBSIDIAN, Presets::portal),
                new Preset("Bridge", "3 wide, 10 long plank bridge with railings",
                        Items.OAK_PLANKS, Presets::bridge),
                new Preset("Lamp post", "Cobblestone base, fence post and a lantern",
                        Items.LANTERN, Presets::lampPost),
                new Preset("Wheat farm", "9x9 dirt plot in a log frame with a water hole in the middle (hoe it and add water)",
                        Items.WHEAT, Presets::wheatFarm),
                new Preset("Sugar cane farm", "Rows of sand with trenches for water between them (fill the trenches with water)",
                        Items.SUGAR_CANE, Presets::caneFarm),
                new Preset("Iron farm frame", "Raised platform with a glass pen and a collection hole (add villagers, beds, a zombie, lava and a hopper)",
                        Items.IRON_INGOT, Presets::ironFarm),
                new Preset("Mob grinder shell", "Dark 9x9 spawning room with a drop hole in the middle (add water streams toward it)",
                        Items.ROTTEN_FLESH, Presets::mobGrinder),
                new Preset("Trading hall", "Five villager cells with workstations in front and a slab lid to keep them in",
                        Items.EMERALD, Presets::tradingHall),
                new Preset("Castle wall", "11 long, 3 thick stone-brick wall with a walkway and battlements",
                        Items.STONE_BRICK_WALL, Presets::castleWall),
                new Preset("Dock", "Plank pier on log posts with railings and lanterns",
                        Items.OAK_BOAT, Presets::dock),
                new Preset("Beacon pyramid", "Full 4-level iron pyramid with a beacon on top (164 iron blocks)",
                        Items.BEACON, Presets::beaconPyramid),
                // Modpack bases: plain vanilla shells laid out for machines, power and storage mods.
                new Preset("Machine hall", "15x11 open hall, 5 high, with a centre aisle, skylight and a 3-wide entrance "
                        + "- room for lines of machines (Mekanism, Create, Thermal...)", Items.SMOOTH_STONE, Presets::machineHall),
                new Preset("Power room", "9x9 deepslate room with obsidian pillars and a marked 3x3 generator pad "
                        + "(reactors, generators, batteries)", Items.OBSIDIAN, Presets::powerRoom),
                new Preset("Storage core", "9x7 room with a bench along the back for drives and terminals, "
                        + "barrels for overflow (AE2, Refined Storage)", Items.BARREL, Presets::storageCore),
                new Preset("Greenhouse", "11x9 glass house with dirt beds either side of a water channel "
                        + "(fill the channel with water, hoe the beds) - crops, botany pots", Items.GLASS, Presets::greenhouse),
                new Preset("Launch pad", "11x11 concrete pad with hazard stripes, a landing mark and corner lights "
                        + "(rockets, jetpacks, airships)", Items.YELLOW_CONCRETE, Presets::launchPad),
                new Preset("Rail station", "13 long platform beside a track, with a roof, lights and benches",
                        Items.RAIL, Presets::railStation),
                new Preset("Bee yard", "9x9 fenced flower meadow with hives and a composter (Productive Bees and friends)",
                        Items.BEEHIVE, Presets::beeYard));
    }

    // ------------------------------------------------------------------ designs

    private static Blueprint house() {
        Shape b = new Shape("Starter house");
        BlockState planks = state(Blocks.OAK_PLANKS);
        b.box(0, 0, 0, 6, 0, 6, state(Blocks.COBBLESTONE));
        b.walls(0, 1, 0, 6, 3, 6, planks);
        for (int y = 1; y <= 3; y++) {
            b.set(0, y, 0, state(Blocks.OAK_LOG));
            b.set(6, y, 0, state(Blocks.OAK_LOG));
            b.set(0, y, 6, state(Blocks.OAK_LOG));
            b.set(6, y, 6, state(Blocks.OAK_LOG));
        }
        BlockState glass = state(Blocks.GLASS_PANE);
        b.set(1, 2, 0, glass);
        b.set(5, 2, 0, glass);
        b.set(0, 2, 3, glass);
        b.set(6, 2, 3, glass);
        b.set(3, 2, 6, glass);
        door(b, 3, 1, 0, Blocks.OAK_DOOR, Direction.SOUTH);
        gableRoof(b, 0, 6, 4, 0, 6, Blocks.OAK_STAIRS, Blocks.OAK_SLAB, planks);
        b.set(1, 1, 5, state(Blocks.CRAFTING_TABLE));
        b.set(2, 1, 5, facing(Blocks.FURNACE, Direction.NORTH));
        b.set(4, 1, 5, facing(Blocks.CHEST, Direction.NORTH));
        b.set(5, 1, 5, facing(Blocks.CHEST, Direction.NORTH));
        b.set(1, 1, 1, state(Blocks.TORCH));
        b.set(5, 1, 1, state(Blocks.TORCH));
        return b.blueprint();
    }

    private static Blueprint smelter() {
        Shape b = new Shape("Smelter room");
        BlockState bricks = state(Blocks.STONE_BRICKS);
        b.box(0, 0, 0, 8, 0, 6, state(Blocks.POLISHED_ANDESITE));
        b.walls(0, 1, 0, 8, 3, 6, bricks);
        for (int y = 1; y <= 3; y++) {
            b.set(0, y, 0, state(Blocks.CHISELED_STONE_BRICKS));
            b.set(8, y, 0, state(Blocks.CHISELED_STONE_BRICKS));
            b.set(0, y, 6, state(Blocks.CHISELED_STONE_BRICKS));
            b.set(8, y, 6, state(Blocks.CHISELED_STONE_BRICKS));
        }
        b.set(2, 2, 0, state(Blocks.GLASS));
        b.set(6, 2, 0, state(Blocks.GLASS));
        b.set(0, 2, 3, state(Blocks.GLASS));
        b.set(8, 2, 3, state(Blocks.GLASS));
        door(b, 4, 1, 0, Blocks.SPRUCE_DOOR, Direction.SOUTH);
        b.box(0, 4, 0, 8, 4, 6, slab(Blocks.STONE_BRICK_SLAB, false));
        // The furnace wall.
        b.set(1, 1, 5, facing(Blocks.FURNACE, Direction.NORTH));
        b.set(2, 1, 5, facing(Blocks.FURNACE, Direction.NORTH));
        b.set(3, 1, 5, facing(Blocks.FURNACE, Direction.NORTH));
        b.set(4, 1, 5, state(Blocks.CRAFTING_TABLE));
        b.set(5, 1, 5, facing(Blocks.BLAST_FURNACE, Direction.NORTH));
        b.set(6, 1, 5, facing(Blocks.BLAST_FURNACE, Direction.NORTH));
        b.set(7, 1, 5, facing(Blocks.SMOKER, Direction.NORTH));
        // Fuel and ore chests along the sides.
        b.set(1, 1, 2, facing(Blocks.CHEST, Direction.EAST));
        b.set(1, 1, 3, facing(Blocks.CHEST, Direction.EAST));
        b.set(7, 1, 2, facing(Blocks.CHEST, Direction.WEST));
        b.set(7, 1, 3, facing(Blocks.CHEST, Direction.WEST));
        b.set(4, 3, 3, state(Blocks.LANTERN).setValue(BlockStateProperties.HANGING, true));
        b.set(1, 1, 1, state(Blocks.LANTERN));
        b.set(7, 1, 1, state(Blocks.LANTERN));
        return b.blueprint();
    }

    private static Blueprint storage() {
        Shape b = new Shape("Storage room");
        BlockState planks = state(Blocks.SPRUCE_PLANKS);
        b.box(0, 0, 0, 6, 0, 5, planks);
        b.walls(0, 1, 0, 6, 3, 5, planks);
        for (int y = 1; y <= 3; y++) {
            b.set(0, y, 0, state(Blocks.STRIPPED_SPRUCE_LOG));
            b.set(6, y, 0, state(Blocks.STRIPPED_SPRUCE_LOG));
            b.set(0, y, 5, state(Blocks.STRIPPED_SPRUCE_LOG));
            b.set(6, y, 5, state(Blocks.STRIPPED_SPRUCE_LOG));
        }
        door(b, 3, 1, 0, Blocks.SPRUCE_DOOR, Direction.SOUTH);
        b.box(0, 4, 0, 6, 4, 5, slab(Blocks.SPRUCE_SLAB, false));
        // Two rows of double chests on the back wall, single rows on the sides.
        for (int x = 1; x <= 4; x++) {
            b.set(x, 1, 4, facing(Blocks.CHEST, Direction.NORTH));
            b.set(x, 2, 4, facing(Blocks.CHEST, Direction.NORTH));
        }
        b.set(5, 1, 4, state(Blocks.BARREL));
        b.set(5, 2, 4, state(Blocks.BARREL));
        b.set(1, 1, 2, facing(Blocks.CHEST, Direction.EAST));
        b.set(1, 1, 3, facing(Blocks.CHEST, Direction.EAST));
        b.set(5, 1, 2, facing(Blocks.CHEST, Direction.WEST));
        b.set(5, 1, 3, facing(Blocks.CHEST, Direction.WEST));
        b.set(3, 3, 2, state(Blocks.LANTERN).setValue(BlockStateProperties.HANGING, true));
        return b.blueprint();
    }

    private static Blueprint enchanting() {
        Shape b = new Shape("Enchanting room");
        b.box(0, 0, 0, 6, 0, 6, state(Blocks.POLISHED_DEEPSLATE));
        b.set(3, 1, 3, state(Blocks.ENCHANTING_TABLE));
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != 2 || dx == 0 && dz == -2) {
                    continue; // only the ring, with a gap at the front to walk in
                }
                b.set(3 + dx, 1, 3 + dz, state(Blocks.BOOKSHELF));
                b.set(3 + dx, 2, 3 + dz, state(Blocks.BOOKSHELF));
            }
        }
        b.set(0, 1, 0, state(Blocks.LANTERN));
        b.set(6, 1, 0, state(Blocks.LANTERN));
        b.set(0, 1, 6, state(Blocks.LANTERN));
        b.set(6, 1, 6, state(Blocks.LANTERN));
        return b.blueprint();
    }

    private static Blueprint workshop() {
        Shape b = new Shape("Workshop");
        b.box(0, 0, 0, 6, 0, 4, state(Blocks.OAK_PLANKS));
        for (int y = 1; y <= 3; y++) {
            b.set(0, y, 0, state(Blocks.OAK_LOG));
            b.set(6, y, 0, state(Blocks.OAK_LOG));
            b.set(0, y, 4, state(Blocks.OAK_LOG));
            b.set(6, y, 4, state(Blocks.OAK_LOG));
        }
        b.box(1, 1, 4, 5, 3, 4, state(Blocks.OAK_PLANKS));
        b.box(0, 4, 0, 6, 4, 4, slab(Blocks.OAK_SLAB, false));
        b.set(1, 1, 3, state(Blocks.CRAFTING_TABLE));
        b.set(2, 1, 3, state(Blocks.SMITHING_TABLE));
        b.set(3, 1, 3, state(Blocks.STONECUTTER));
        b.set(4, 1, 3, facing(Blocks.FURNACE, Direction.NORTH));
        b.set(5, 1, 3, facing(Blocks.CHEST, Direction.NORTH));
        b.set(5, 1, 1, state(Blocks.ANVIL));
        b.set(3, 3, 2, state(Blocks.LANTERN).setValue(BlockStateProperties.HANGING, true));
        return b.blueprint();
    }

    private static Blueprint watchtower() {
        Shape b = new Shape("Watchtower");
        BlockState cobble = state(Blocks.COBBLESTONE);
        b.box(0, 0, 0, 4, 0, 4, cobble);
        b.walls(0, 1, 0, 4, 9, 4, cobble);
        door(b, 2, 1, 0, Blocks.SPRUCE_DOOR, Direction.SOUTH);
        b.set(2, 5, 0, state(Blocks.GLASS));
        b.set(0, 6, 2, state(Blocks.GLASS));
        b.set(4, 6, 2, state(Blocks.GLASS));
        b.box(0, 10, 0, 4, 10, 4, state(Blocks.STONE_BRICKS));
        // Ladder up the inside of the back wall, through a hole in the platform.
        for (int y = 1; y <= 10; y++) {
            b.set(2, y, 3, facing(Blocks.LADDER, Direction.NORTH));
        }
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                if (edge && (x + z) % 2 == 0) {
                    b.set(x, 11, z, state(Blocks.STONE_BRICK_WALL));
                }
            }
        }
        b.set(1, 11, 1, state(Blocks.TORCH));
        b.set(3, 11, 3, state(Blocks.TORCH));
        b.set(2, 3, 2, state(Blocks.LANTERN).setValue(BlockStateProperties.HANGING, true));
        return b.blueprint();
    }

    private static Blueprint pen() {
        Shape b = new Shape("Animal pen");
        BlockState fence = state(Blocks.OAK_FENCE);
        b.walls(0, 0, 0, 8, 0, 8, fence);
        b.set(4, 0, 0, facing(Blocks.OAK_FENCE_GATE, Direction.NORTH));
        for (int[] c : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}}) {
            b.set(c[0], 1, c[1], fence);
            b.set(c[0], 2, c[1], state(Blocks.LANTERN));
        }
        return b.blueprint();
    }

    private static Blueprint portal() {
        Shape b = new Shape("Nether portal");
        b.walls2d(0, 0, 3, 4, state(Blocks.OBSIDIAN));
        return b.blueprint();
    }

    private static Blueprint bridge() {
        Shape b = new Shape("Bridge");
        b.box(0, 0, 0, 2, 0, 9, state(Blocks.OAK_PLANKS));
        for (int z = 0; z <= 9; z++) {
            b.set(0, 1, z, state(Blocks.OAK_FENCE));
            b.set(2, 1, z, state(Blocks.OAK_FENCE));
        }
        b.set(0, 2, 0, state(Blocks.LANTERN));
        b.set(2, 2, 9, state(Blocks.LANTERN));
        return b.blueprint();
    }

    private static Blueprint lampPost() {
        Shape b = new Shape("Lamp post");
        b.set(0, 0, 0, state(Blocks.COBBLESTONE));
        b.set(0, 1, 0, state(Blocks.COBBLESTONE_WALL));
        b.set(0, 2, 0, state(Blocks.OAK_FENCE));
        b.set(0, 3, 0, state(Blocks.OAK_FENCE));
        b.set(0, 4, 0, state(Blocks.LANTERN));
        return b.blueprint();
    }

    private static Blueprint wheatFarm() {
        Shape b = new Shape("Wheat farm");
        b.walls(0, 0, 0, 8, 0, 8, state(Blocks.OAK_LOG));
        b.box(1, 0, 1, 7, 0, 7, state(Blocks.DIRT));
        b.remove(4, 0, 4);
        for (int[] c : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}}) {
            b.set(c[0], 1, c[1], state(Blocks.TORCH));
        }
        return b.blueprint();
    }

    private static Blueprint caneFarm() {
        Shape b = new Shape("Sugar cane farm");
        b.box(0, 0, 0, 8, 0, 8, state(Blocks.DIRT));
        for (int x = 0; x <= 8; x++) {
            for (int z = 0; z <= 8; z++) {
                if (x % 3 != 1) {
                    b.set(x, 1, z, state(Blocks.SAND));
                }
            }
        }
        // Cap both ends of each trench so the water stays in.
        for (int x = 0; x <= 8; x++) {
            if (x % 3 == 1) {
                b.set(x, 1, 0, state(Blocks.SAND));
                b.set(x, 1, 8, state(Blocks.SAND));
            }
        }
        return b.blueprint();
    }

    private static Blueprint ironFarm() {
        Shape b = new Shape("Iron farm frame");
        BlockState bricks = state(Blocks.STONE_BRICKS);
        b.box(0, 0, 0, 6, 0, 6, bricks);
        b.remove(3, 0, 3);
        b.walls(0, 1, 0, 6, 3, 6, state(Blocks.GLASS));
        for (int y = 1; y <= 3; y++) {
            b.set(0, y, 0, bricks);
            b.set(6, y, 0, bricks);
            b.set(0, y, 6, bricks);
            b.set(6, y, 6, bricks);
        }
        b.walls(2, 1, 2, 4, 1, 4, state(Blocks.GLASS));
        b.remove(3, 1, 2);
        return b.blueprint();
    }

    private static Blueprint mobGrinder() {
        Shape b = new Shape("Mob grinder shell");
        BlockState cobble = state(Blocks.COBBLESTONE);
        b.box(0, 0, 0, 8, 0, 8, cobble);
        b.walls(0, 1, 0, 8, 3, 8, cobble);
        b.box(0, 4, 0, 8, 4, 8, cobble);
        b.remove(4, 0, 4);
        return b.blueprint();
    }

    private static Blueprint tradingHall() {
        Shape b = new Shape("Trading hall");
        BlockState bricks = state(Blocks.STONE_BRICKS);
        b.box(0, 0, 0, 10, 0, 2, bricks);
        b.box(0, 1, 2, 10, 3, 2, bricks);
        b.box(0, 3, 0, 10, 3, 2, bricks);
        for (int x = 0; x <= 10; x += 2) {
            b.set(x, 1, 0, bricks);
            b.set(x, 2, 0, bricks);
            b.set(x, 1, 1, bricks);
            b.set(x, 2, 1, bricks);
        }
        Block[] stations = {Blocks.LECTERN, Blocks.COMPOSTER, Blocks.SMITHING_TABLE, Blocks.BLAST_FURNACE, Blocks.BREWING_STAND};
        for (int i = 0; i < 5; i++) {
            int x = 1 + 2 * i;
            b.set(x, 1, 0, facing(stations[i], Direction.NORTH));
            b.set(x, 2, 0, slab(Blocks.STONE_BRICK_SLAB, false));
        }
        return b.blueprint();
    }

    private static Blueprint castleWall() {
        Shape b = new Shape("Castle wall");
        b.box(0, 0, 0, 10, 5, 2, state(Blocks.STONE_BRICKS));
        for (int x = 0; x <= 10; x++) {
            b.set(x, 0, 0, state(Blocks.COBBLESTONE));
            b.set(x, 0, 2, state(Blocks.COBBLESTONE));
            if (x % 2 == 0) {
                b.set(x, 6, 0, state(Blocks.STONE_BRICKS));
                b.set(x, 6, 2, state(Blocks.STONE_BRICKS));
            }
        }
        b.set(0, 6, 1, state(Blocks.TORCH));
        b.set(10, 6, 1, state(Blocks.TORCH));
        return b.blueprint();
    }

    private static Blueprint dock() {
        Shape b = new Shape("Dock");
        for (int[] p : new int[][]{{0, 0}, {2, 0}, {0, 5}, {2, 5}, {0, 11}, {2, 11}}) {
            for (int y = 0; y <= 2; y++) {
                b.set(p[0], y, p[1], state(Blocks.OAK_LOG));
            }
        }
        b.box(0, 3, 0, 2, 3, 11, state(Blocks.OAK_PLANKS));
        for (int z = 0; z <= 11; z++) {
            if (z % 4 != 1) {
                b.set(0, 4, z, state(Blocks.OAK_FENCE));
                b.set(2, 4, z, state(Blocks.OAK_FENCE));
            }
        }
        b.set(0, 5, 11, state(Blocks.LANTERN));
        b.set(2, 5, 11, state(Blocks.LANTERN));
        return b.blueprint();
    }

    // ------------------------------------------------------------------ modpack bases

    private static Blueprint machineHall() {
        Shape b = new Shape("Machine hall");
        b.box(0, 0, 0, 14, 0, 10, state(Blocks.SMOOTH_STONE));
        // A lighter aisle down the middle; machines go either side of it.
        b.box(6, 0, 0, 8, 0, 10, state(Blocks.POLISHED_ANDESITE));
        b.walls(0, 1, 0, 14, 5, 10, state(Blocks.STONE_BRICKS));
        // Window band between the pillars.
        b.box(1, 3, 0, 13, 4, 0, state(Blocks.GLASS));
        b.box(1, 3, 10, 13, 4, 10, state(Blocks.GLASS));
        b.box(0, 3, 1, 0, 4, 9, state(Blocks.GLASS));
        b.box(14, 3, 1, 14, 4, 9, state(Blocks.GLASS));
        for (int x : new int[]{0, 7, 14}) {
            for (int z : new int[]{0, 5, 10}) {
                if (x == 0 || x == 14 || z == 0 || z == 10) {
                    b.box(x, 1, z, x, 5, z, state(Blocks.POLISHED_DEEPSLATE));
                }
            }
        }
        // Flat roof with a skylight down the middle and glowstone for light.
        b.box(0, 6, 0, 14, 6, 10, state(Blocks.SMOOTH_STONE));
        b.box(2, 6, 5, 12, 6, 5, state(Blocks.GLASS));
        for (int[] q : new int[][]{{3, 2}, {11, 2}, {3, 8}, {11, 8}, {7, 2}, {7, 8}}) {
            b.set(q[0], 6, q[1], state(Blocks.GLOWSTONE));
        }
        // 3-wide, 3-high entrance.
        for (int x = 6; x <= 8; x++) {
            for (int y = 1; y <= 3; y++) {
                b.remove(x, y, 0);
            }
        }
        return b.blueprint();
    }

    private static Blueprint powerRoom() {
        Shape b = new Shape("Power room");
        b.box(0, 0, 0, 8, 0, 8, state(Blocks.POLISHED_DEEPSLATE));
        // The generator pad, and a darker cable lane round it.
        b.box(2, 0, 2, 6, 0, 6, state(Blocks.DEEPSLATE_TILES));
        b.box(3, 0, 3, 5, 0, 5, state(Blocks.IRON_BLOCK));
        b.walls(0, 1, 0, 8, 4, 8, state(Blocks.DEEPSLATE_TILES));
        for (int[] q : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}, {4, 8}, {0, 4}, {8, 4}}) {
            b.box(q[0], 1, q[1], q[0], 4, q[1], state(Blocks.OBSIDIAN));
        }
        b.box(0, 5, 0, 8, 5, 8, state(Blocks.DEEPSLATE_TILES));
        for (int[] q : new int[][]{{2, 2}, {6, 2}, {2, 6}, {6, 6}}) {
            b.set(q[0], 5, q[1], state(Blocks.SEA_LANTERN));
        }
        door(b, 4, 1, 0, Blocks.IRON_DOOR, Direction.SOUTH);
        b.set(3, 2, 0, state(Blocks.TINTED_GLASS));
        b.set(5, 2, 0, state(Blocks.TINTED_GLASS));
        return b.blueprint();
    }

    private static Blueprint storageCore() {
        Shape b = new Shape("Storage core");
        b.box(0, 0, 0, 8, 0, 6, state(Blocks.SMOOTH_QUARTZ));
        b.walls(0, 1, 0, 8, 4, 6, state(Blocks.LIGHT_GRAY_CONCRETE));
        b.box(1, 3, 0, 7, 3, 0, state(Blocks.GLASS));
        for (int[] q : new int[][]{{0, 0}, {8, 0}, {0, 6}, {8, 6}}) {
            b.box(q[0], 1, q[1], q[0], 4, q[1], state(Blocks.QUARTZ_PILLAR));
        }
        // Bench along the back wall: put drives, the controller and terminals on and in front of it.
        b.box(1, 1, 5, 7, 1, 5, state(Blocks.SMOOTH_STONE));
        b.box(1, 2, 5, 7, 2, 5, slab(Blocks.SMOOTH_STONE_SLAB, false));
        // Overflow barrels along the sides.
        for (int z = 1; z <= 3; z++) {
            b.set(1, 1, z, facing(Blocks.BARREL, Direction.EAST));
            b.set(7, 1, z, facing(Blocks.BARREL, Direction.WEST));
        }
        b.box(0, 5, 0, 8, 5, 6, slab(Blocks.SMOOTH_STONE_SLAB, false));
        b.set(2, 5, 3, state(Blocks.SEA_LANTERN));
        b.set(6, 5, 3, state(Blocks.SEA_LANTERN));
        door(b, 4, 1, 0, Blocks.IRON_DOOR, Direction.SOUTH);
        return b.blueprint();
    }

    private static Blueprint greenhouse() {
        Shape b = new Shape("Greenhouse");
        b.walls(0, 0, 0, 10, 0, 8, state(Blocks.STONE_BRICKS));
        // Beds either side of a water channel (left open) down the middle, a path round them.
        b.box(1, 0, 1, 9, 0, 3, state(Blocks.DIRT));
        b.box(1, 0, 5, 9, 0, 7, state(Blocks.DIRT));
        b.set(1, 0, 4, state(Blocks.STONE_BRICKS));
        b.set(9, 0, 4, state(Blocks.STONE_BRICKS));
        b.walls(0, 1, 0, 10, 3, 8, state(Blocks.GLASS));
        for (int[] q : new int[][]{{0, 0}, {10, 0}, {0, 8}, {10, 8}, {5, 0}, {5, 8}}) {
            b.box(q[0], 1, q[1], q[0], 4, q[1], state(Blocks.SPRUCE_LOG));
        }
        b.box(0, 4, 0, 10, 4, 8, state(Blocks.GLASS));
        b.box(0, 4, 0, 10, 4, 0, state(Blocks.STRIPPED_SPRUCE_LOG));
        b.box(0, 4, 8, 10, 4, 8, state(Blocks.STRIPPED_SPRUCE_LOG));
        b.box(1, 5, 4, 9, 5, 4, state(Blocks.GLASS));
        door(b, 5, 1, 0, Blocks.SPRUCE_DOOR, Direction.SOUTH);
        b.remove(5, 0, 0);
        b.set(5, 0, 0, state(Blocks.STONE_BRICKS));
        b.set(1, 1, 1, state(Blocks.COMPOSTER));
        b.set(9, 1, 1, facing(Blocks.CHEST, Direction.WEST));
        return b.blueprint();
    }

    private static Blueprint launchPad() {
        Shape b = new Shape("Launch pad");
        b.box(0, 0, 0, 10, 0, 10, state(Blocks.GRAY_CONCRETE));
        // Hazard stripes round the edge.
        for (int i = 0; i <= 10; i++) {
            BlockState stripe = state(i % 2 == 0 ? Blocks.YELLOW_CONCRETE : Blocks.BLACK_CONCRETE);
            b.set(i, 0, 0, stripe);
            b.set(i, 0, 10, stripe);
            b.set(0, 0, i, stripe);
            b.set(10, 0, i, stripe);
        }
        // Landing mark: a white ring with a cross in the middle.
        for (int i = 3; i <= 7; i++) {
            b.set(i, 0, 3, state(Blocks.WHITE_CONCRETE));
            b.set(i, 0, 7, state(Blocks.WHITE_CONCRETE));
            b.set(3, 0, i, state(Blocks.WHITE_CONCRETE));
            b.set(7, 0, i, state(Blocks.WHITE_CONCRETE));
        }
        b.box(5, 0, 4, 5, 0, 6, state(Blocks.WHITE_CONCRETE));
        b.box(4, 0, 5, 6, 0, 5, state(Blocks.WHITE_CONCRETE));
        for (int[] q : new int[][]{{0, 0}, {10, 0}, {0, 10}, {10, 10}}) {
            b.set(q[0], 1, q[1], state(Blocks.IRON_BARS));
            b.set(q[0], 2, q[1], state(Blocks.SEA_LANTERN));
        }
        return b.blueprint();
    }

    private static Blueprint railStation() {
        Shape b = new Shape("Rail station");
        // Track bed and rails along x at z = 0.
        b.box(0, 0, 0, 12, 0, 0, state(Blocks.STONE_BRICKS));
        b.box(0, 1, 0, 12, 1, 0, state(Blocks.RAIL));
        // Platform one block up beside it, with a yellow safety edge.
        b.box(0, 0, 1, 12, 1, 4, state(Blocks.STONE_BRICKS));
        b.box(0, 1, 1, 12, 1, 1, state(Blocks.YELLOW_CONCRETE));
        b.box(0, 1, 2, 12, 1, 4, state(Blocks.SMOOTH_STONE));
        // Roof on posts along the back.
        for (int x : new int[]{0, 6, 12}) {
            b.box(x, 2, 4, x, 4, 4, state(Blocks.SPRUCE_LOG));
            b.set(x, 2, 1, state(Blocks.SPRUCE_FENCE));
            b.set(x, 3, 1, state(Blocks.SPRUCE_FENCE));
            b.set(x, 4, 1, state(Blocks.SPRUCE_FENCE));
        }
        b.box(0, 5, 1, 12, 5, 4, slab(Blocks.SPRUCE_SLAB, false));
        for (int x : new int[]{3, 9}) {
            b.set(x, 2, 3, facing(Blocks.SPRUCE_STAIRS, Direction.SOUTH));
            b.set(x + 1, 2, 3, facing(Blocks.SPRUCE_STAIRS, Direction.SOUTH));
            b.set(x, 4, 2, state(Blocks.LANTERN));
        }
        return b.blueprint();
    }

    private static Blueprint beeYard() {
        Shape b = new Shape("Bee yard");
        b.box(0, 0, 0, 8, 0, 8, state(Blocks.GRASS_BLOCK));
        b.box(4, 0, 0, 4, 0, 4, state(Blocks.DIRT_PATH));
        b.walls(0, 1, 0, 8, 1, 8, state(Blocks.OAK_FENCE));
        b.set(4, 1, 0, facing(Blocks.OAK_FENCE_GATE, Direction.SOUTH));
        Block[] flowers = {Blocks.DANDELION, Blocks.POPPY, Blocks.CORNFLOWER, Blocks.ALLIUM, Blocks.OXEYE_DAISY,
                Blocks.AZURE_BLUET, Blocks.ORANGE_TULIP, Blocks.LILY_OF_THE_VALLEY};
        int f = 0;
        for (int x = 1; x <= 7; x++) {
            for (int z = 1; z <= 7; z++) {
                if (x != 4 && (x + z) % 2 == 0 && z != 6) {
                    b.set(x, 1, z, state(flowers[f++ % flowers.length]));
                }
            }
        }
        // Hives along the back, facing the gate, and a composter.
        for (int x : new int[]{2, 4, 6}) {
            b.set(x, 0, 6, state(Blocks.OAK_PLANKS));
            b.set(x, 1, 6, facing(Blocks.BEEHIVE, Direction.NORTH));
        }
        b.set(7, 1, 7, state(Blocks.COMPOSTER));
        for (int[] q : new int[][]{{0, 0}, {8, 0}, {0, 8}, {8, 8}}) {
            b.set(q[0], 2, q[1], state(Blocks.LANTERN));
        }
        return b.blueprint();
    }

    private static Blueprint beaconPyramid() {
        Shape b = new Shape("Beacon pyramid");
        BlockState iron = state(Blocks.IRON_BLOCK);
        for (int level = 0; level < 4; level++) {
            b.box(level, level, level, 8 - level, level, 8 - level, iron);
        }
        b.set(4, 4, 4, state(Blocks.BEACON));
        return b.blueprint();
    }

    // ------------------------------------------------------------------ pieces

    /**
     * A gable roof running along x over z0..z1, starting at height y: stairs climb from both sides to a
     * slab ridge, and the two ends are closed with {@code fill}.
     */
    private static void gableRoof(Shape b, int x0, int x1, int y, int z0, int z1, Block stairs, Block slab, BlockState fill) {
        int front = z0;
        int back = z1;
        while (front < back) {
            for (int x = x0; x <= x1; x++) {
                b.set(x, y, front, stair(stairs, Direction.SOUTH, false));
                b.set(x, y, back, stair(stairs, Direction.NORTH, false));
            }
            for (int z = front + 1; z < back; z++) {
                b.set(x0, y, z, fill);
                b.set(x1, y, z, fill);
            }
            front++;
            back--;
            y++;
        }
        if (front == back) {
            for (int x = x0; x <= x1; x++) {
                b.set(x, y, front, slab(slab, false));
            }
        }
    }

    /** A door's lower half (the upper half appears by itself), leaving the doorway above it open. */
    private static void door(Shape b, int x, int y, int z, Block door, Direction facing) {
        b.set(x, y, z, facing(door, facing));
        b.remove(x, y + 1, z);
    }

    // ------------------------------------------------------------------ block states

    private static BlockState state(Block block) {
        return block.defaultBlockState();
    }

    private static BlockState facing(Block block, Direction direction) {
        BlockState s = block.defaultBlockState();
        return s.hasProperty(BlockStateProperties.HORIZONTAL_FACING) ? s.setValue(BlockStateProperties.HORIZONTAL_FACING, direction) : s;
    }

    private static BlockState stair(Block block, Direction direction, boolean upsideDown) {
        return facing(block, direction).setValue(BlockStateProperties.HALF, upsideDown ? Half.TOP : Half.BOTTOM);
    }

    private static BlockState slab(Block block, boolean top) {
        return block.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
    }

    /** Collects blocks by relative position; the blueprint's size is whatever the blocks span. */
    private static final class Shape {
        private final String name;
        private final Map<BlockPos, BlockState> blocks = new HashMap<>();

        Shape(String name) {
            this.name = name;
        }

        void set(int x, int y, int z, BlockState state) {
            this.blocks.put(new BlockPos(x, y, z), state);
        }

        void remove(int x, int y, int z) {
            this.blocks.remove(new BlockPos(x, y, z));
        }

        void box(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        set(x, y, z, state);
                    }
                }
            }
        }

        /** The four side walls of a box (no floor or ceiling). */
        void walls(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
            for (int y = y0; y <= y1; y++) {
                for (int x = x0; x <= x1; x++) {
                    set(x, y, z0, state);
                    set(x, y, z1, state);
                }
                for (int z = z0; z <= z1; z++) {
                    set(x0, y, z, state);
                    set(x1, y, z, state);
                }
            }
        }

        /** A rectangular frame standing in the x/y plane at z = 0. */
        void walls2d(int x0, int y0, int x1, int y1, BlockState state) {
            for (int x = x0; x <= x1; x++) {
                set(x, y0, 0, state);
                set(x, y1, 0, state);
            }
            for (int y = y0; y <= y1; y++) {
                set(x0, y, 0, state);
                set(x1, y, 0, state);
            }
        }

        Blueprint blueprint() {
            int sx = 0;
            int sy = 0;
            int sz = 0;
            for (BlockPos p : this.blocks.keySet()) {
                sx = Math.max(sx, p.getX() + 1);
                sy = Math.max(sy, p.getY() + 1);
                sz = Math.max(sz, p.getZ() + 1);
            }
            return new Blueprint(this.name, sx, sy, sz, new HashMap<>(this.blocks));
        }
    }
}
