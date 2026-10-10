package com.testrts.rtsclient.build;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Picks the block state to ask Baritone for. Directional blocks face the camera (like vanilla
 * placement faces the player), and the player can cycle extra rotation with R.
 */
public final class PlacementOrientation {
    private int rotation;

    public void cycle() {
        this.rotation = (this.rotation + 1) % 4;
    }

    public int rotation() {
        return this.rotation;
    }

    public BlockState stateFor(Block block, float cameraYaw) {
        BlockState state = block.defaultBlockState();
        Direction toward = Direction.fromYRot(cameraYaw).getOpposite();
        for (int i = 0; i < this.rotation; i++) {
            toward = toward.getClockWise();
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_FACING, toward);
        } else if (state.hasProperty(BlockStateProperties.FACING)) {
            state = state.setValue(BlockStateProperties.FACING, toward);
        } else if (state.hasProperty(BlockStateProperties.AXIS)) {
            Direction.Axis axis = switch (this.rotation) {
                case 1 -> Direction.Axis.X;
                case 2 -> Direction.Axis.Z;
                default -> Direction.Axis.Y;
            };
            state = state.setValue(BlockStateProperties.AXIS, axis);
        } else if (state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            state = state.setValue(BlockStateProperties.HORIZONTAL_AXIS, toward.getAxis());
        }
        return state;
    }

    public String describe(Block block, float cameraYaw) {
        BlockState state = stateFor(block, cameraYaw);
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return "facing " + state.getValue(BlockStateProperties.HORIZONTAL_FACING).getSerializedName();
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return "facing " + state.getValue(BlockStateProperties.FACING).getSerializedName();
        }
        if (state.hasProperty(BlockStateProperties.AXIS)) {
            return "axis " + state.getValue(BlockStateProperties.AXIS).getSerializedName();
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_AXIS)) {
            return "axis " + state.getValue(BlockStateProperties.HORIZONTAL_AXIS).getSerializedName();
        }
        return "";
    }
}
