package com.testrts.rtsclient.build;

import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/** What the player has selected in the RTS UI: tool, shape, brush item and the shape's control points. */
public final class BuildSession {
    public ToolMode tool = ToolMode.BUILD;
    public BuildShape shape = BuildShape.BLOCK;
    public BuildMode mode = BuildMode.FILL;
    /** For {@link BuildMode#REPLACE_TYPE}: the block type to swap out, taken from the first click. */
    @Nullable
    public net.minecraft.block.Block replaceTarget;
    public boolean hollow;
    public int height = 3;
    public ItemStack brush = ItemStack.EMPTY;
    public final PlacementOrientation orientation = new PlacementOrientation();
    /** Block-state properties the player picked by hand (slab half, stair shape...), by property name. */
    public final java.util.Map<String, String> stateOverrides = new java.util.HashMap<>();

    /** Blueprint being pasted (Blueprint tool), or null while selecting a region to copy. */
    @Nullable
    public Blueprint clipboard;
    /** Quarter turns clockwise applied to the pasted blueprint. */
    public int pasteTurns;
    /** Where the blueprint's minimum corner is pinned after the first click (null: follows the cursor). */
    @Nullable
    public BlockPos pasteAnchor;
    /** Mine blocks inside the blueprint's box that are air in the blueprint, for an exact copy. */
    public boolean pasteClearsAir;
    /** Paste the blueprint mirrored (left-right). */
    public boolean pasteMirror;
    /** Copying also mines the original (move instead of copy). */
    public boolean cutOnCopy;

    public TerrainMode terrainMode = TerrainMode.FLATTEN;
    /** How far above the level Flatten clears. */
    public int terrainClearHeight = 16;

    /** Saved camera positions (Ctrl+digit saves, Shift+digit jumps back). */
    public final java.util.Map<Integer, com.testrts.rtsclient.camera.RtsCamera.Pose> bookmarks = new java.util.HashMap<>();

    /** First click of a two-point shape. */
    @Nullable
    public BlockPos anchorA;
    /** Second click; once set the shape is a pending order waiting for confirmation. */
    @Nullable
    public BlockPos anchorB;

    public void clearSelection() {
        this.anchorA = null;
        this.anchorB = null;
        this.replaceTarget = null;
    }

    public boolean hasPendingOrder() {
        return this.anchorA != null && this.anchorB != null;
    }

    /** The state the brush places, with facing from the camera and any properties the player picked. */
    @Nullable
    public BlockState brushState(float cameraYaw) {
        if (!(this.brush.getItem() instanceof BlockItem bi)) {
            return null;
        }
        BlockState state = this.orientation.stateFor(bi.getBlock(), cameraYaw);
        for (java.util.Map.Entry<String, String> e : this.stateOverrides.entrySet()) {
            state = withProperty(state, e.getKey(), e.getValue());
        }
        return state;
    }

    private static BlockState withProperty(BlockState state, String name, String value) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return withValue(state, property, value);
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        return property.parse(value).map(v -> state.with(property, v)).orElse(state);
    }

    public void adjustHeight(int delta) {
        int h = this.height + delta;
        if (h == 0) {
            h = delta > 0 ? 1 : -1;
        }
        this.height = Math.max(-64, Math.min(64, h));
    }
}
