package com.testrts.rtsclient.exec;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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

    private static boolean dangerous(BlockState state) {
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE) || state.isOf(Blocks.MAGMA_BLOCK)
                || state.isOf(Blocks.CACTUS) || state.isOf(Blocks.SWEET_BERRY_BUSH) || state.isOf(Blocks.POWDER_SNOW)
                || state.isOf(Blocks.CAMPFIRE) || state.isOf(Blocks.SOUL_CAMPFIRE) || state.isOf(Blocks.WITHER_ROSE)
                || state.isOf(Blocks.COBWEB) || state.isOf(Blocks.LAVA);
    }
}
