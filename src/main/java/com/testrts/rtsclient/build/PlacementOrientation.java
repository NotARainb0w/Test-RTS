package com.testrts.rtsclient.build;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

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
        BlockState state = block.getDefaultState();
        Direction toward = Direction.fromRotation(cameraYaw).getOpposite();
        for (int i = 0; i < this.rotation; i++) {
            toward = toward.rotateYClockwise();
        }
        if (state.contains(Properties.HORIZONTAL_FACING)) {
            state = state.with(Properties.HORIZONTAL_FACING, toward);
        } else if (state.contains(Properties.FACING)) {
            state = state.with(Properties.FACING, toward);
        } else if (state.contains(Properties.AXIS)) {
            Direction.Axis axis = switch (this.rotation) {
                case 1 -> Direction.Axis.X;
                case 2 -> Direction.Axis.Z;
                default -> Direction.Axis.Y;
            };
            state = state.with(Properties.AXIS, axis);
        } else if (state.contains(Properties.HORIZONTAL_AXIS)) {
            state = state.with(Properties.HORIZONTAL_AXIS, toward.getAxis());
        }
        return state;
    }

    public String describe(Block block, float cameraYaw) {
        BlockState state = stateFor(block, cameraYaw);
        if (state.contains(Properties.HORIZONTAL_FACING)) {
            return "facing " + state.get(Properties.HORIZONTAL_FACING).asString();
        }
        if (state.contains(Properties.FACING)) {
            return "facing " + state.get(Properties.FACING).asString();
        }
        if (state.contains(Properties.AXIS)) {
            return "axis " + state.get(Properties.AXIS).asString();
        }
        if (state.contains(Properties.HORIZONTAL_AXIS)) {
            return "axis " + state.get(Properties.HORIZONTAL_AXIS).asString();
        }
        return "";
    }
}
