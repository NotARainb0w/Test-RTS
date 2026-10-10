package com.testrts.rtsclient.render;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * "Cut view": hide every block above a Y level, so you can see into buildings and caves from
 * above. Read by the chunk-building mixins (on chunk builder threads), hence a plain volatile int.
 */
public final class CutView {
    /** Blocks with y greater than this are hidden. MAX_VALUE = off. */
    public static volatile int cutY = Integer.MAX_VALUE;

    private CutView() {
    }

    public static boolean hides(int y) {
        return y > cutY;
    }

    public static void set(Minecraft client, @Nullable Integer y) {
        int value = y == null ? Integer.MAX_VALUE : y;
        if (value == cutY) {
            return;
        }
        int old = cutY;
        cutY = value;
        if (client.levelRenderer == null || client.player == null || client.level == null) {
            return;
        }
        // Rebuild only the sections whose contents change: between the old and new cut levels.
        int lo = Math.min(old, value);
        int hi = Math.max(old, value);
        int bottom = client.level.getMinBuildHeight();
        int top = client.level.getMaxBuildHeight();
        int minY = Math.max(bottom, lo == Integer.MAX_VALUE ? bottom : lo - 1);
        int maxY = Math.min(top - 1, hi == Integer.MAX_VALUE ? top - 1 : hi + 1);
        int r = (client.options.getEffectiveRenderDistance() + 1) * 16;
        BlockPos p = client.player.blockPosition();
        client.levelRenderer.setBlocksDirty(p.getX() - r, minY, p.getZ() - r, p.getX() + r, maxY, p.getZ() + r);
    }
}
