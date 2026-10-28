package com.testrts.rtsclient.exec;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Rules for where a player can stand and walk, used by the built-in pathfinder. */
public final class Walkability {
    private Walkability() {
    }

    /** No collision and nothing that hurts. */
    @SuppressWarnings("deprecation") // hasChunkAt(BlockPos) is the exact equivalent of Yarn isChunkLoaded(BlockPos)
    public static boolean passable(ClientLevel world, BlockPos pos) {
        if (!world.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (dangerous(state)) {
            return false;
        }
        FluidState fluid = state.getFluidState();
        if (fluid.is(FluidTags.LAVA)) {
            return false;
        }
        if (state.is(net.minecraft.tags.BlockTags.CLIMBABLE)) {
            return true; // ladders, vines, scaffolding: climbed through like a player does
        }
        return state.getCollisionShape(world, pos).isEmpty();
    }

    /** Something solid to stand on, no taller than a full block. */
    @SuppressWarnings("deprecation")
    public static boolean floor(ClientLevel world, BlockPos pos) {
        if (!world.hasChunkAt(pos)) {
            return false;
        }
        BlockState state = world.getBlockState(pos);
        if (dangerous(state)) {
            return false;
        }
        if (state.is(Blocks.SCAFFOLDING)) {
            return true; // stood on from above (no collision box without a player to measure against)
        }
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            // Swimming: still water counts as somewhere we can be.
            return state.getFluidState().is(FluidTags.WATER) && state.getFluidState().isSource();
        }
        return shape.max(Direction.Axis.Y) <= 1.0;
    }

    /** Feet at {@code feet}: body fits and there is ground (or water) under it. */
    public static boolean standable(ClientLevel world, BlockPos feet) {
        return passable(world, feet) && passable(world, feet.above()) && (floor(world, feet.below()) || climbable(world, feet));
    }

    /**
     * Minecraft's scaffolding distance for a piece at {@code pos}: 0 over solid ground, the piece below's on top of
     * scaffolding, else one more than the nearest piece beside it. At 7 it falls (6 out from a column at most).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static int scaffoldDistance(net.minecraft.world.level.Level world, BlockPos pos) {
        BlockState below = world.getBlockState(pos.below());
        int dist = 7;
        if (below.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
            dist = distanceOf(below);
        } else if (below.isFaceSturdy(world, pos.below(), Direction.UP)) {
            return 0;
        }
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            BlockState n = world.getBlockState(pos.relative(dir));
            if (n.is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                dist = Math.min(dist, distanceOf(n) + 1);
            }
        }
        return dist;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int distanceOf(BlockState s) {
        for (net.minecraft.world.level.block.state.properties.Property p : s.getProperties()) {
            if (p.getName().equals("distance")) {
                return (Integer) s.getValue(p);
            }
        }
        return 7;
    }

    /** Climbed like a ladder: ladders, vines, scaffolding (the climbable tag). */
    @SuppressWarnings("deprecation")
    public static boolean climbable(ClientLevel world, BlockPos pos) {
        return world.hasChunkAt(pos) && world.getBlockState(pos).is(net.minecraft.tags.BlockTags.CLIMBABLE);
    }

    /**
     * Height (0..1) of the top of what a player stands on in {@code pos}: 0.5 for a bottom slab,
     * 0.0625 for a carpet, 1 for a full block. Still water counts as 0.9 (swimming at the surface).
     */
    @SuppressWarnings("deprecation")
    public static double floorTop(ClientLevel world, BlockPos pos) {
        if (!world.hasChunkAt(pos)) {
            return 1.0;
        }
        BlockState state = world.getBlockState(pos);
        if (state.is(Blocks.SCAFFOLDING)) {
            return 1.0;
        }
        VoxelShape shape = state.getCollisionShape(world, pos);
        if (shape.isEmpty()) {
            return state.getFluidState().is(FluidTags.WATER) ? 0.9 : 0.0;
        }
        return Math.min(1.0, shape.max(Direction.Axis.Y));
    }

    /** The Y the player's feet are actually at when standing on the node {@code feet}. */
    public static double standY(ClientLevel world, BlockPos feet) {
        if (climbable(world, feet) && !floor(world, feet.below())) {
            return feet.getY(); // holding on to a ladder or vine
        }
        return feet.getY() - 1 + floorTop(world, feet.below());
    }

    /**
     * The node the player is standing on. On a slab, carpet or other low block the player's block
     * position is the low block itself; the node is the one above it.
     */
    public static BlockPos feetNode(ClientLevel world, LocalPlayer player) {
        BlockPos pos = player.blockPosition();
        if (standable(world, pos)) {
            return pos;
        }
        if (standable(world, pos.above())) {
            return pos.above();
        }
        // Mid-jump (e.g. re-planning during a recovery jump): the node is the one below.
        if (standable(world, pos.below())) {
            return pos.below();
        }
        return pos;
    }

    private static boolean dangerous(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.MAGMA_BLOCK)
                || state.is(Blocks.CACTUS) || state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.POWDER_SNOW)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE) || state.is(Blocks.WITHER_ROSE)
                || state.is(Blocks.COBWEB) || state.is(Blocks.LAVA);
    }
}
