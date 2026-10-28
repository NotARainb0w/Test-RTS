package com.testrts.rtsclient.exec;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;

/** Rules for where a player can stand and walk, used by the built-in pathfinder. */
public final class Walkability {
    private Walkability() {
    }

    /** No collision and nothing that hurts. */
    public static boolean passable(ClientWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (dangerous(state)) {
            return false;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.isIn(FluidTags.LAVA)) {
            return false;
        }
        if (state.isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE)) {
            return true; // ladders, vines, scaffolding: climbed through like a player does
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    /** Something solid to stand on, no taller than a full block. */
    public static boolean floor(ClientWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (dangerous(state)) {
            return false;
        }
        if (state.isOf(Blocks.SCAFFOLDING)) {
            return true; // stood on from above (no collision box without a player to measure against)
        }
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            // Swimming: still water counts as somewhere we can be.
            return state.getFluidState().isIn(FluidTags.WATER) && state.getFluidState().isStill();
        }
        return shape.getMax(Direction.Axis.Y) <= 1.0;
    }

    /** Feet at {@code feet}: body fits and there is ground (or water) under it. */
    public static boolean standable(ClientWorld world, BlockPos feet) {
        return passable(world, feet) && passable(world, feet.up()) && (floor(world, feet.down()) || climbable(world, feet));
    }

    /**
     * Minecraft's scaffolding distance for a piece at {@code pos}: 0 over solid ground, the piece below's on top of
     * scaffolding, else one more than the nearest piece beside it. At 7 it falls (6 out from a column at most).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int scaffoldDistance(net.minecraft.world.World world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.down());
        int dist = 7;
        if (below.isOf(net.minecraft.block.Blocks.SCAFFOLDING)) {
            dist = distanceOf(below);
        } else if (below.isSideSolidFullSquare(world, pos.down(), Direction.UP)) {
            return 0;
        }
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            BlockState n = world.getBlockState(pos.offset(dir));
            if (n.isOf(net.minecraft.block.Blocks.SCAFFOLDING)) {
                dist = Math.min(dist, distanceOf(n) + 1);
            }
        }
        return dist;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int distanceOf(BlockState s) {
        for (net.minecraft.state.property.Property p : s.getProperties()) {
            if (p.getName().equals("distance")) {
                return (Integer) s.get(p);
            }
        }
        return 7;
    }

    /** Climbed like a ladder: ladders, vines, scaffolding (the climbable tag). */
    @SuppressWarnings("deprecation")
    public static boolean climbable(ClientWorld world, BlockPos pos) {
        return world.isChunkLoaded(pos) && world.getBlockState(pos).isIn(net.minecraft.registry.tag.BlockTags.CLIMBABLE);
    }

    /**
     * Height (0..1) of the top of what a player stands on in {@code pos}: 0.5 for a bottom slab,
     * 0.0625 for a carpet, 1 for a full block. Still water counts as 0.9 (swimming at the surface).
     */
    public static double floorTop(ClientWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) {
            return 1.0;
        }
        BlockState state = world.getBlockState(pos);
        if (state.isOf(Blocks.SCAFFOLDING)) {
            return 1.0;
        }
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return state.getFluidState().isIn(FluidTags.WATER) ? 0.9 : 0.0;
        }
        return Math.min(1.0, shape.getMax(Direction.Axis.Y));
    }

    /** The Y the player's feet are actually at when standing on the node {@code feet}. */
    public static double standY(ClientWorld world, BlockPos feet) {
        if (climbable(world, feet) && !floor(world, feet.down())) {
            return feet.getY(); // holding on to a ladder or vine
        }
        return feet.getY() - 1 + floorTop(world, feet.down());
    }

    /**
     * The node the player is standing on. On a slab, carpet or other low block the player's block
     * position is the low block itself; the node is the one above it.
     */
    public static BlockPos feetNode(ClientWorld world, ClientPlayerEntity player) {
        BlockPos pos = player.getBlockPos();
        if (standable(world, pos)) {
            return pos;
        }
        if (standable(world, pos.up())) {
            return pos.up();
        }
        // Mid-jump (e.g. re-planning during a recovery jump): the node is the one below.
        if (standable(world, pos.down())) {
            return pos.down();
        }
        return pos;
    }

    private static boolean dangerous(BlockState state) {
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE) || state.isOf(Blocks.WITHER_ROSE)
                || state.isOf(Blocks.COBWEB) || state.isOf(Blocks.LAVA);
    }
}
