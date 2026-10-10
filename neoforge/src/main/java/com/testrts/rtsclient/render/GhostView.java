package com.testrts.rtsclient.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * The world as the ghost blocks see it: only the planned blocks exist, so faces between two
 * planned blocks are culled. Colours (grass, leaves) and brightness come from the real world,
 * the same way vanilla's chunk builder threads read them.
 */
final class GhostView implements BlockAndTintGetter {
    private final Map<Long, BlockState> states;
    private final ClientLevel world;

    GhostView(Map<Long, BlockState> states, ClientLevel world) {
        this.states = states;
        this.world = world;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return this.states.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
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
    public int getMinBuildHeight() {
        return this.world.getMinBuildHeight();
    }

    @Override
    public float getShade(Direction direction, boolean shaded) {
        return this.world.getShade(direction, shaded);
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return this.world.getLightEngine();
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver colorResolver) {
        return this.world.getBlockTint(pos, colorResolver);
    }
}
