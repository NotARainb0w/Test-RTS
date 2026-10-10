package com.testrts.rtsclient.render;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockRenderView;
import net.minecraft.world.biome.ColorResolver;
import net.minecraft.world.chunk.light.LightingProvider;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The world as the ghost blocks see it: only the planned blocks exist, so faces between two
 * planned blocks are culled. Colours (grass, leaves) and brightness come from the real world,
 * the same way vanilla's chunk builder threads read them.
 */
final class GhostView implements BlockRenderView {
    private final Map<Long, BlockState> states;
    private final ClientWorld world;

    GhostView(Map<Long, BlockState> states, ClientWorld world) {
        this.states = states;
        this.world = world;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.states.getOrDefault(pos.asLong(), Blocks.AIR.getDefaultState());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return this.world.getHeight();
    }

    @Override
    public int getBottomY() {
        return this.world.getBottomY();
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded) {
        return this.world.getBrightness(direction, shaded);
    }

    @Override
    public LightingProvider getLightingProvider() {
        return this.world.getLightingProvider();
    }

    @Override
    public int getColor(BlockPos pos, ColorResolver colorResolver) {
        return this.world.getColor(pos, colorResolver);
    }
}
