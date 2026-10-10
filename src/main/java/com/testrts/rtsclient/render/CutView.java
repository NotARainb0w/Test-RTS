package com.testrts.rtsclient.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
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

    public static void set(MinecraftClient client, @Nullable Integer y) {
        int value = y == null ? Integer.MAX_VALUE : y;
        if (value == cutY) {
            return;
        }
        int old = cutY;
        cutY = value;
        if (client.worldRenderer == null || client.player == null || client.world == null) {
            return;
        }
        // Rebuild only the sections whose contents change: between the old and new cut levels.
        int lo = Math.min(old, value);
        int hi = Math.max(old, value);
        int minY = Math.max(client.world.getBottomY(), lo == Integer.MAX_VALUE ? client.world.getBottomY() : lo - 1);
        int maxY = Math.min(client.world.getTopY() - 1, hi == Integer.MAX_VALUE ? client.world.getTopY() - 1 : hi + 1);
        int r = (client.options.getViewDistance().getValue() + 1) * 16;
        BlockPos p = client.player.getBlockPos();
        client.worldRenderer.scheduleBlockRenders(p.getX() - r, minY, p.getZ() - r, p.getX() + r, maxY, p.getZ() + r);
    }
}
