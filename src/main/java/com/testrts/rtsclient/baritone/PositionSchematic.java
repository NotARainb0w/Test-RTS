package com.testrts.rtsclient.baritone;

import baritone.api.schematic.ISchematic;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A sparse schematic: only the listed positions matter, everything else inside the bounding
 * box is left alone. Air entries tell Baritone to clear that block.
 */
final class PositionSchematic implements ISchematic {
    private final BlockPos origin;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final Map<Long, BlockState> states;
    private final boolean orientationFallback;

    private PositionSchematic(BlockPos origin, int sizeX, int sizeY, int sizeZ, Map<Long, BlockState> states,
                              boolean orientationFallback) {
        this.origin = origin;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.states = states;
        this.orientationFallback = orientationFallback;
    }

    static PositionSchematic of(Map<BlockPos, BlockState> blocks, boolean orientationFallback) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.getX());
            minY = Math.min(minY, pos.getY());
            minZ = Math.min(minZ, pos.getZ());
            maxX = Math.max(maxX, pos.getX());
            maxY = Math.max(maxY, pos.getY());
            maxZ = Math.max(maxZ, pos.getZ());
        }
        BlockPos origin = new BlockPos(minX, minY, minZ);
        Map<Long, BlockState> relative = new HashMap<>(blocks.size() * 2);
        for (Map.Entry<BlockPos, BlockState> e : blocks.entrySet()) {
            BlockPos p = e.getKey();
            relative.put(BlockPos.asLong(p.getX() - minX, p.getY() - minY, p.getZ() - minZ), e.getValue());
        }
        return new PositionSchematic(origin, maxX - minX + 1, maxY - minY + 1, maxZ - minZ + 1, relative,
                orientationFallback);
    }

    BlockPos origin() {
        return this.origin;
    }

    @Override
    public boolean inSchematic(int x, int y, int z, BlockState currentState) {
        return this.states.containsKey(BlockPos.asLong(x, y, z));
    }

    @Override
    public BlockState desiredState(int x, int y, int z, BlockState current, List<BlockState> approxPlaceable) {
        BlockState wanted = this.states.get(BlockPos.asLong(x, y, z));
        if (wanted == null) {
            return current;
        }
        if (!this.orientationFallback || wanted.isAir() || approxPlaceable == null || approxPlaceable.contains(wanted)) {
            return wanted;
        }
        // Baritone gives up on states it thinks it can't produce. If the exact orientation
        // isn't on offer, fall back to any placeable state of the same block.
        for (BlockState candidate : approxPlaceable) {
            if (candidate.getBlock() == wanted.getBlock()) {
                return candidate;
            }
        }
        return wanted;
    }

    @Override
    public int widthX() {
        return this.sizeX;
    }

    @Override
    public int heightY() {
        return this.sizeY;
    }

    @Override
    public int lengthZ() {
        return this.sizeZ;
    }
}
