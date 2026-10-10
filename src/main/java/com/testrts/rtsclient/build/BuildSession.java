package com.testrts.rtsclient.build;

import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** What the player has selected in the RTS UI: tool, shape, brush item and the shape's control points. */
public final class BuildSession {
    public ToolMode tool = ToolMode.BUILD;
    public BuildShape shape = BuildShape.BLOCK;
    public boolean hollow;
    public int height = 3;
    public ItemStack brush = ItemStack.EMPTY;
    public final PlacementOrientation orientation = new PlacementOrientation();

    /** First click of a two-point shape. */
    @Nullable
    public BlockPos anchorA;
    /** Second click; once set the shape is a pending order waiting for confirmation. */
    @Nullable
    public BlockPos anchorB;

    public void clearSelection() {
        this.anchorA = null;
        this.anchorB = null;
    }

    public boolean hasPendingOrder() {
        return this.anchorA != null && this.anchorB != null;
    }

    public void adjustHeight(int delta) {
        int h = this.height + delta;
        if (h == 0) {
            h = delta > 0 ? 1 : -1;
        }
        this.height = Math.max(-64, Math.min(64, h));
    }
}
