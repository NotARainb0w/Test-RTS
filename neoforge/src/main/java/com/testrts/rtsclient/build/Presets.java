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
                        Items.LANTERN, Presets::lampPost));
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
