package com.testrts.rtsclient.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.process.IBaritoneProcess;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.Optional;

/** All direct Baritone API calls. Only reached through {@link BaritoneBridge}. */
final class BaritoneImpl {
    private BaritoneImpl() {
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    static void goNextTo(BlockPos pos) {
        IBaritone b = baritone();
        b.getPathingBehavior().cancelEverything();
        b.getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(pos));
    }

    static void goTo(BlockPos feet) {
        IBaritone b = baritone();
        b.getPathingBehavior().cancelEverything();
        b.getCustomGoalProcess().setGoalAndPath(new GoalBlock(feet));
    }

    static void build(String name, Map<BlockPos, BlockState> blocks, boolean strictOrientation) {
        PositionSchematic schematic = PositionSchematic.of(blocks, !strictOrientation);
        IBaritone b = baritone();
        b.getPathingBehavior().cancelEverything();
        b.getBuilderProcess().build(name, schematic, schematic.origin());
    }

    static boolean isBuilding() {
        return baritone().getBuilderProcess().isActive();
    }

    static boolean isBuilderPaused() {
        return baritone().getBuilderProcess().isPaused();
    }

    static void resumeBuilder() {
        baritone().getBuilderProcess().resume();
    }

    static boolean isMoving() {
        IBaritone b = baritone();
        return b.getCustomGoalProcess().isActive() || b.getPathingBehavior().isPathing();
    }

    static boolean isBusy() {
        IBaritone b = baritone();
        return b.getPathingBehavior().isPathing()
                || b.getBuilderProcess().isActive()
                || b.getCustomGoalProcess().isActive();
    }

    static void stop() {
        baritone().getPathingBehavior().cancelEverything();
    }

    static String status() {
        IBaritone b = baritone();
        Optional<IBaritoneProcess> process = b.getPathingControlManager().mostRecentInControl();
        if (process.isPresent() && process.get().isActive()) {
            String name = process.get().displayName();
            if (b.getBuilderProcess().isActive() && b.getBuilderProcess().isPaused()) {
                return "Builder paused";
            }
            return name;
        }
        return b.getPathingBehavior().isPathing() ? "Pathing" : "Idle";
    }

    static void applySettings(boolean strictOrientation, boolean layered, boolean topDown) {
        Settings s = BaritoneAPI.getSettings();
        s.buildInLayers.value = layered;
        s.layerOrder.value = topDown;
        s.layerHeight.value = 1;
        // Throwaway blocks for bridging and pillaring on the way (the builder places the schematic regardless).
        s.allowPlace.value = com.testrts.rtsclient.config.RtsConfig.get().useScaffolding;
        s.allowBreak.value = true;
        // Lets the builder pull blocks out of the main inventory instead of only the hotbar.
        s.allowInventory.value = true;
        s.buildIgnoreDirection.value = !strictOrientation;
    }
}
