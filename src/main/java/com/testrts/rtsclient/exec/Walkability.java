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
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            // Swimming: still water counts as somewhere we can be.
            return state.getFluidState().isIn(FluidTags.WATER) && state.getFluidState().isStill();
        }
        return shape.getMax(Direction.Axis.Y) <= 1.0;
    }

    /** Feet at {@code feet}: body fits and there is ground (or water) under it. */
    public static boolean standable(ClientWorld world, BlockPos feet) {
        return passable(world, feet) && passable(world, feet.up()) && floor(world, feet.down());
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
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return state.getFluidState().isIn(FluidTags.WATER) ? 0.9 : 0.0;
        }
        return Math.min(1.0, shape.getMax(Direction.Axis.Y));
    }

    /** The Y the player's feet are actually at when standing on the node {@code feet}. */
    public static double standY(ClientWorld world, BlockPos feet) {
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
