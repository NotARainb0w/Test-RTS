package com.testrts.rtsclient.baritone;

import com.testrts.rtsclient.RtsClientMod;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

/**
 * The only door to Baritone. This class touches no Baritone types, so the mod still loads
 * (with movement features disabled) when Baritone isn't installed; everything Baritone-specific
 * lives in {@link BaritoneImpl}, which is only loaded after {@link #isAvailable()} said yes.
 */
public final class BaritoneBridge {
    private static Boolean available;

    private BaritoneBridge() {
    }

    public static boolean isAvailable() {
        if (available == null) {
            boolean found;
            try {
                Class.forName("baritone.api.BaritoneAPI", false, BaritoneBridge.class.getClassLoader());
                found = true;
            } catch (ClassNotFoundException | LinkageError e) {
                found = FabricLoader.getInstance().isModLoaded("baritone");
            }
            available = found;
            if (!found) {
                RtsClientMod.LOGGER.warn("Baritone not found: RTS movement, building and mining are disabled");
            }
        }
        return available;
    }

    /** Walk until standing next to {@code pos} (close enough to click it). */
    public static boolean goNextTo(BlockPos pos) {
        if (!isAvailable()) {
            return false;
        }
        BaritoneImpl.goNextTo(pos);
        return true;
    }

    /** Walk to stand on {@code feet} (the air block above the ground). */
    public static boolean goTo(BlockPos feet) {
        if (!isAvailable()) {
            return false;
        }
        BaritoneImpl.goTo(feet);
        return true;
    }

    /** Hand a set of desired block states to Baritone's builder. Air means "mine this". */
    public static boolean build(String name, Map<BlockPos, BlockState> blocks, boolean strictOrientation) {
        if (!isAvailable() || blocks.isEmpty()) {
            return false;
        }
        BaritoneImpl.build(name, blocks, strictOrientation);
        return true;
    }

    public static boolean isBuilding() {
        return isAvailable() && BaritoneImpl.isBuilding();
    }

    public static boolean isBuilderPaused() {
        return isAvailable() && BaritoneImpl.isBuilderPaused();
    }

    public static void resumeBuilder() {
        if (isAvailable()) {
            BaritoneImpl.resumeBuilder();
        }
    }

    public static boolean isMoving() {
        return isAvailable() && BaritoneImpl.isMoving();
    }

    public static boolean isBusy() {
        return isAvailable() && BaritoneImpl.isBusy();
    }

    public static void stop() {
        if (isAvailable()) {
            BaritoneImpl.stop();
        }
    }

    public static String status() {
        if (!isAvailable()) {
            return "Baritone not installed";
        }
        return BaritoneImpl.status();
    }

    public static void applySettings(boolean strictOrientation) {
        if (isAvailable()) {
            BaritoneImpl.applySettings(strictOrientation);
        }
    }
}
