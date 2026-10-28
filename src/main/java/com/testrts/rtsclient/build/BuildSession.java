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
    /** Dome, pyramid and cone upside down (see {@link BuildShape#invertible()}). */
    public boolean inverted;
    /** The look of the Bridge shape. */
    public BridgeStyle bridgeStyle = BridgeStyle.MEDIEVAL;
    /** The look of the Island shape. */
    public IslandStyle islandStyle = IslandStyle.GRASSY;
    /** The underside of the Island shape. */
    public IslandBottom islandBottom = IslandBottom.ROUNDED;
    /** The kind of mountain the Mountain shape makes. */
    public MountainForm mountainForm = MountainForm.PEAKED;
    /** The Giant tree's kind, and what the Rocks shape makes and of what. */
    public TreeKind treeKind = TreeKind.OAK;
    public RockForm rockForm = RockForm.BOULDERS;
    public RockLook rockLook = RockLook.STONE;
    /** The Maze shape's walls and paths, and the Backrooms' look. */
    public MazeStyle mazeStyle = MazeStyle.HEDGE;
    public MazeLayout mazeLayout = MazeLayout.CLASSIC;
    public BackroomsLevel backroomsLevel = BackroomsLevel.LEVEL_0;
    /** Which design the next island gets; a new one after each island is queued (or with [N]). */
    public long islandSeed = new java.util.Random().nextLong();
    public int height = 3;
    /** Circle with Hollow off: how wide a hole is cut from the centre (0: none, 1: just the centre block). */
    public int circleHole;
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
    /** Also dig out the blueprint's empty spaces (rooms, doorways) when pasting into terrain. */
    public boolean pasteClearsAir = true;
    /** Paste the blueprint mirrored (left-right). */
    public boolean pasteMirror;
    /** Copying also mines the original (move instead of copy). */
    public boolean cutOnCopy;

    public TerrainMode terrainMode = TerrainMode.FLATTEN;
    /** How far above the level Flatten clears. */
    public int terrainClearHeight = 16;
    /** Plant trees: blocks between saplings. */
    public int treeSpacing = 5;
    /** The newer Terrain modes' size: road/river/bridge width, hill height, lake depth, terrace step... */
    public int terrainSize = 4;
    /** Blend brush: painted columns (see {@link TerrainBlend#key}) to the height the brush touched. */
    public final java.util.LinkedHashMap<Long, Integer> blendPaint = new java.util.LinkedHashMap<>();
    /** Bumped on every paint change, so previews know to plan again. */
    public int blendVersion;
    /** When the paint last changed (ms): previews wait until a stroke pauses instead of planning every mouse move. */
    public long blendChangedAt;
    /** Blend brush radius in blocks. */
    public int blendRadius = 3;
    /** Blend smoothing strength (1-10). */
    public int blendStrength = 3;
    /** Level brush: the ground height to level to; set where a new painting starts. */
    public int levelY = 64;
    /** Level brush: how wide the sloped edge into the surrounding land is (0-10). */
    public int levelRamp = 3;
    /** Blend puts grass (or whatever the surface was) back on top of the columns it changes. */
    public boolean blendKeepTop = true;
    /** Paste the blueprint as a grid of copies: count along x and z, and the gap between copies. */
    public int arrayX = 1;
    public int arrayZ = 1;
    public int arrayGap = 0;
    /** Pattern brush: blocks and their weights; shapes use a random mix when this isn't empty. */
    public final java.util.LinkedHashMap<net.minecraft.item.Item, Integer> mix = new java.util.LinkedHashMap<>();
    /** How the mix blocks are laid out (random, clusters, gradient, layers...). */
    public MixPattern pattern = MixPattern.RANDOM;
    /** The next click picks a room to put a roof on. */
    public boolean roofPick;
    public RoofPlanner.Style roofStyle = RoofPlanner.Style.GABLE;
    public RoofPlanner.Look roofLook = RoofPlanner.Look.BRUSH;
    /** While moving a building: the source box (min, max) that is cleared once the copy is placed. */
    public BlockPos moveFrom;
    public BlockPos moveTo;
    /** The blueprint picked up by Move; moving only happens when this is what gets placed. */
    public Blueprint moveBlueprint;

    public boolean moving() {
        return this.moveFrom != null && this.moveBlueprint != null && this.moveBlueprint == this.clipboard;
    }
    /** Air placement: the cursor targets a flat layer at {@link #airY}, so blocks can go in mid-air. */
    public boolean airPlace;
    public int airY = 64;

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
        if (!this.blendPaint.isEmpty()) {
            this.blendPaint.clear();
            this.blendVersion++;
            this.blendChangedAt = System.currentTimeMillis();
        }
    }

    /** Paint (or erase) the Blend brush around {@code center}. */
    public void paintBlend(BlockPos center, boolean erase) {
        if (this.blendPaint.isEmpty() && !erase) {
            this.levelY = center.getY();
        }
        if (TerrainBlend.paint(this.blendPaint, center, this.blendRadius, erase)) {
            this.blendVersion++;
            this.blendChangedAt = System.currentTimeMillis();
        }
    }

    public boolean hasPendingOrder() {
        return this.anchorA != null && this.anchorB != null;
    }

    /** The state the brush places, with facing from the camera and any properties the player picked. */
    @Nullable
    /** The pattern mix as states (each repeated by its weight), oriented like the brush. */
    public java.util.List<BlockState> mixStates(float cameraYaw) {
        java.util.List<BlockState> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<net.minecraft.item.Item, Integer> e : this.mix.entrySet()) {
            if (e.getKey() instanceof BlockItem bi) {
                BlockState state = this.orientation.stateFor(bi.getBlock(), cameraYaw);
                for (int i = 0; i < e.getValue(); i++) {
                    out.add(state);
                }
            }
        }
        return out;
    }

    /** "60% Stone, 40% Andesite" (and the pattern when it isn't random). */
    public String mixDescription() {
        String pattern = this.pattern == MixPattern.RANDOM ? "" : this.pattern.label() + ": ";
        return pattern + mixShares();
    }

    private String mixShares() {
        int total = 0;
        for (int w : this.mix.values()) {
            total += w;
        }
        StringBuilder sb = new StringBuilder();
        for (java.util.Map.Entry<net.minecraft.item.Item, Integer> e : this.mix.entrySet()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(Math.round(100.0F * e.getValue() / total)).append("% ").append(e.getKey().getName().getString());
        }
        return sb.toString();
    }

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

    /** {@code state} with property {@code name} set to {@code value}, if it has that property and value. */
    public static BlockState withProperty(BlockState state, String name, String value) {
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

    /** The Circle has no height; with Hollow off, the height controls size a hole in its middle instead. */
    public boolean circleHoleMode() {
        return this.shape == BuildShape.CIRCLE && !this.hollow;
    }

    public void adjustHeight(int delta) {
        if (circleHoleMode()) {
            this.circleHole = Math.max(0, Math.min(64, this.circleHole + delta));
            return;
        }
        int h = this.height + delta;
        if (h == 0) {
            h = delta > 0 ? 1 : -1;
        }
        this.height = Math.max(-64, Math.min(64, h));
    }
}
