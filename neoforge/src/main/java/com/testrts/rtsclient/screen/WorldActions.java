package com.testrts.rtsclient.screen;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.build.Blueprint;
import com.testrts.rtsclient.build.BuildMode;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.build.BuildShape;
import com.testrts.rtsclient.build.OrderPlanner;
import com.testrts.rtsclient.build.TerrainPlanner;
import com.testrts.rtsclient.build.ToolMode;
import com.testrts.rtsclient.build.UndoHistory;
import com.testrts.rtsclient.camera.RtsCamera;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.render.WorldOverlay;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.BuildTask;
import com.testrts.rtsclient.task.FetchTask;
import com.testrts.rtsclient.task.IndexStorageTask;
import com.testrts.rtsclient.task.InteractTask;
import com.testrts.rtsclient.task.MoveTask;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.TaskQueue;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What clicks in the world do, for every tool. Kept apart from the screen so the GUI only deals
 * with layout and input, and this logic reads as plain game actions.
 */
public final class WorldActions {
    private final Minecraft client;
    private final RtsController controller;
    private final BuildSession session;

    public WorldActions(Minecraft client, RtsController controller) {
        this.client = client;
        this.controller = controller;
        this.session = controller.session();
    }

    private ClientLevel world() {
        return this.client.level;
    }

    public static void queue(RtsTask task) {
        TaskQueue.get().add(task);
        if (TaskQueue.get().size() > 1) {
            Messages.info(task.label() + " queued (" + (TaskQueue.get().size() - 1) + " ahead)");
        }
    }

    // ------------------------------------------------------------------ clicks

    /** Left click on a block with the current tool. */
    public void primary(BlockHitResult hit) {
        ClientLevel world = world();
        if (world == null) {
            return;
        }
        BuildSession s = this.session;
        switch (s.tool) {
            case BUILD -> {
                if (!(s.brush.getItem() instanceof BlockItem)) {
                    Messages.warn("Pick a block from the bar below first (or middle-click a block in the world)");
                    return;
                }
                boolean onExisting = s.shape == BuildShape.VEIN || s.mode.targetsExisting();
                if (s.mode == BuildMode.REPLACE_TYPE && s.anchorA == null) {
                    s.replaceTarget = world.getBlockState(hit.getBlockPos()).getBlock();
                    Messages.info("Replacing " + s.replaceTarget.getName().getString());
                }
                shapeClick(onExisting ? hit.getBlockPos() : WorldOverlay.targetFor(s, hit));
            }
            case DESTROY -> shapeClick(hit.getBlockPos());
            case INTERACT -> {
                BlockState state = world.getBlockState(hit.getBlockPos());
                queue(new InteractTask(hit, state.getBlock().getName().getString()));
            }
            case LINK -> {
                if (!StorageManager.isContainer(world, hit.getBlockPos())) {
                    Messages.warn("That's not a container");
                    return;
                }
                boolean linked = StorageManager.get().toggle(world, hit.getBlockPos());
                Messages.info((linked ? "Linked " : "Unlinked ") + hit.getBlockPos().toShortString());
            }
            case BLUEPRINT -> blueprintClick(hit);
            case TERRAIN -> regionClick(hit.getBlockPos(), this::confirmTerrain);
            case MEASURE -> measureClick(hit.getBlockPos());
        }
    }

    /** Two-click region tools: first click A, second B, then act. */
    private void regionClick(BlockPos p, Runnable onComplete) {
        BuildSession s = this.session;
        if (s.anchorA == null) {
            s.anchorA = p;
            Messages.info("First corner set - click the opposite corner");
        } else if (s.anchorB == null) {
            s.anchorB = p;
            onComplete.run();
        }
    }

    private void shapeClick(BlockPos target) {
        BuildSession s = this.session;
        if (s.shape.singleClick()) {
            s.anchorA = target;
            s.anchorB = target;
            confirmOrder();
            return;
        }
        if (s.anchorA == null) {
            s.anchorA = target;
            Messages.info("First point set - click the second point");
        } else if (s.anchorB == null) {
            s.anchorB = target;
            Messages.info("Click again or press Enter to confirm, X to cancel, arrows to nudge");
        } else {
            confirmOrder();
        }
    }

    /** Enter / second click: turn the pending shape (or paste) into orders. */
    public void confirm() {
        switch (this.session.tool) {
            case BLUEPRINT -> confirmPaste();
            case TERRAIN -> {
                if (this.session.anchorA != null && this.session.anchorB != null) {
                    confirmTerrain();
                }
            }
            default -> confirmOrder();
        }
    }

    public void confirmOrder() {
        BuildSession s = this.session;
        ClientLevel world = world();
        if (!s.hasPendingOrder() || world == null || !s.tool.usesShapes()) {
            return;
        }
        OrderPlanner.Request request = OrderPlanner.request(s, s.anchorA, s.anchorB, this.controller.camera().yaw(),
                RtsConfig.get().maxShapeBlocks);
        OrderPlanner.Plan plan = OrderPlanner.plan(world, request);
        if (plan.truncated()) {
            Messages.error("Shape is larger than " + RtsConfig.get().maxShapeBlocks + " blocks (raise it in Settings)");
            return;
        }
        if (s.shape == BuildShape.HOLE && plan.shapeSize() == 0) {
            Messages.warn("That hole isn't enclosed (or is too big) - nothing to fill");
            s.clearSelection();
            return;
        }
        boolean clearing = s.tool == ToolMode.DESTROY;
        String label = OrderPlanner.label(request, plan.changes().size());
        s.clearSelection();
        if (plan.changes().isEmpty()) {
            Messages.info("Nothing to do there");
            return;
        }
        pushUndo(label, plan.changes());
        queue(new BuildTask(label, plan.changes(), clearing));
    }

    private void pushUndo(String label, Map<BlockPos, BlockState> order) {
        Map<BlockPos, BlockState> before = new LinkedHashMap<>();
        for (BlockPos p : order.keySet()) {
            before.put(p, world().getBlockState(p));
        }
        UndoHistory.push(label, before);
    }

    // ------------------------------------------------------------------ blueprints

    private void blueprintClick(BlockHitResult hit) {
        BuildSession s = this.session;
        if (s.clipboard != null) {
            if (s.pasteAnchor == null) {
                // First click pins it; arrows nudge it; click again or Enter builds it.
                s.pasteAnchor = s.clipboard.originFor(WorldOverlay.targetFor(s, hit), s.pasteTurns);
                Messages.info("Placed - arrows to nudge, R to rotate, click or Enter to build, X to move it again");
            } else {
                confirmPaste();
            }
            return;
        }
        BlockPos p = hit.getBlockPos();
        if (s.anchorA == null) {
            s.anchorA = p;
            Messages.info((s.cutOnCopy ? "Cut" : "Copy") + ": first corner set - click the opposite corner");
            return;
        }
        BlockPos a = s.anchorA;
        s.clearSelection();
        copyRegion(a, p);
    }

    private void copyRegion(BlockPos a, BlockPos b) {
        BuildSession s = this.session;
        ClientLevel world = world();
        String name = "Blueprint " + (Blueprint.loadAll().size() + 1);
        Blueprint bp = Blueprint.capture(world, a, b, name, RtsConfig.get().maxShapeBlocks);
        if (bp.blockCount() == 0) {
            Messages.warn("Nothing to copy there");
            return;
        }
        bp.save();
        s.clipboard = bp;
        s.pasteTurns = 0;
        s.pasteAnchor = null;
        if (s.cutOnCopy) {
            // Move: mine the original once it's safely saved.
            Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
            BlockPos min = BlockPos.containing(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            BlockPos max = BlockPos.containing(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
            for (BlockPos p : BlockPos.betweenClosed(min, max)) {
                BlockState now = world.getBlockState(p);
                if (!now.isAir() && !(now.getBlock() instanceof LiquidBlock) && now.getDestroySpeed(world, p) >= 0) {
                    clear.put(p.immutable(), Blocks.AIR.defaultBlockState());
                }
            }
            if (!clear.isEmpty()) {
                pushUndo("Cut " + name, clear);
                queue(new BuildTask("Cut " + name + " (" + clear.size() + ")", clear, true));
            }
        }
        Messages.success((s.cutOnCopy ? "Cut " : "Copied ") + bp.blockCount() + " blocks (" + bp.size() + ") as \"" + name
                + "\" - click to place it, R to rotate");
    }

    public void confirmPaste() {
        BuildSession s = this.session;
        ClientLevel world = world();
        if (s.clipboard == null || s.pasteAnchor == null || world == null) {
            return;
        }
        Blueprint bp = s.clipboard;
        Map<BlockPos, BlockState> build = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : bp.placed(s.pasteAnchor, s.pasteTurns, s.pasteMirror).entrySet()) {
            if (world.getBlockState(e.getKey()).getBlock() != e.getValue().getBlock()) {
                build.put(e.getKey(), e.getValue());
            }
        }
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        if (s.pasteClearsAir) {
            if (bp.volume() > RtsConfig.get().maxShapeBlocks) {
                Messages.warn("Too big to clear the air inside; pasting blocks only");
            } else {
                for (BlockPos p : bp.placedAir(s.pasteAnchor, s.pasteTurns, s.pasteMirror)) {
                    BlockState now = world.getBlockState(p);
                    if (!now.isAir() && !(now.getBlock() instanceof LiquidBlock) && now.getDestroySpeed(world, p) >= 0) {
                        clear.put(p, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        s.pasteAnchor = null;
        if (build.isEmpty() && clear.isEmpty()) {
            Messages.info("Already built there");
            return;
        }
        Map<BlockPos, BlockState> all = new LinkedHashMap<>(clear);
        all.putAll(build);
        pushUndo("Paste " + bp.name(), all);
        if (!clear.isEmpty()) {
            queue(new BuildTask("Clear for " + bp.name() + " (" + clear.size() + ")", clear, true));
        }
        if (!build.isEmpty()) {
            queue(new BuildTask("Build " + bp.name() + " (" + build.size() + ")", build, false));
        }
    }

    // ------------------------------------------------------------------ terrain

    private void confirmTerrain() {
        BuildSession s = this.session;
        if (s.anchorA == null || s.anchorB == null) {
            return;
        }
        BlockPos a = s.anchorA;
        BlockPos b = s.anchorB;
        s.clearSelection();
        switch (s.terrainMode) {
            case FLATTEN -> flatten(a, b);
            case LIGHT_UP -> lightUp(a, b);
        }
    }

    /** Flatten fills with the brush block, or dirt if the brush isn't a block. */
    public static BlockState flattenFill(BuildSession s) {
        return s.brush.getItem() instanceof BlockItem bi ? bi.getBlock().defaultBlockState() : Blocks.DIRT.defaultBlockState();
    }

    private void flatten(BlockPos a, BlockPos b) {
        TerrainPlanner.Flatten plan = TerrainPlanner.flatten(world(), a, b, flattenFill(this.session),
                this.session.terrainClearHeight, RtsConfig.get().maxShapeBlocks);
        if (plan.truncated()) {
            Messages.error("Area too big to flatten (over " + RtsConfig.get().maxShapeBlocks + " blocks)");
            return;
        }
        if (plan.clear().isEmpty() && plan.fill().isEmpty()) {
            Messages.info("Already flat");
            return;
        }
        Map<BlockPos, BlockState> all = new LinkedHashMap<>(plan.clear());
        all.putAll(plan.fill());
        pushUndo("Flatten", all);
        if (!plan.clear().isEmpty()) {
            queue(new BuildTask("Flatten: clear " + plan.clear().size(), plan.clear(), true));
        }
        if (!plan.fill().isEmpty()) {
            queue(new BuildTask("Flatten: fill " + plan.fill().size(), plan.fill(), false));
        }
    }

    private void lightUp(BlockPos a, BlockPos b) {
        List<BlockPos> spots = TerrainPlanner.lightUp(world(), a, b);
        if (spots.isEmpty()) {
            Messages.info("That area is already lit");
            return;
        }
        Map<BlockPos, BlockState> order = new LinkedHashMap<>();
        for (BlockPos p : spots) {
            order.put(p, Blocks.TORCH.defaultBlockState());
        }
        pushUndo("Light up", order);
        queue(new BuildTask("Light up (" + order.size() + " torches)", order, false));
    }

    // ------------------------------------------------------------------ measure

    private void measureClick(BlockPos p) {
        BuildSession s = this.session;
        if (s.anchorA == null || s.anchorB != null) {
            s.anchorA = p;
            s.anchorB = null;
            Messages.info("Measuring from " + p.toShortString() + " - click the second point");
            return;
        }
        s.anchorB = p;
        Messages.success("Measured " + describeMeasure(s.anchorA, p));
    }

    public static String describeMeasure(BlockPos a, BlockPos b) {
        int dx = Math.abs(a.getX() - b.getX()) + 1;
        int dy = Math.abs(a.getY() - b.getY()) + 1;
        int dz = Math.abs(a.getZ() - b.getZ()) + 1;
        double dist = Math.sqrt(a.distSqr(b));
        return dx + " x " + dz + " x " + dy + " (W x L x H), distance " + String.format(Locale.ROOT, "%.1f", dist)
                + ", " + String.format(Locale.ROOT, "%,d", (long) dx * dy * dz) + " blocks";
    }

    // ------------------------------------------------------------------ other actions

    /** Right click: send the character there. */
    public void moveTo(BlockHitResult hit) {
        BlockPos feet = hit.getDirection() == Direction.UP || hit.getDirection() == Direction.DOWN
                ? hit.getBlockPos().above()
                : hit.getBlockPos().relative(hit.getDirection());
        queue(new MoveTask(feet));
    }

    /** Middle click: use the block under the cursor as the brush. */
    @Nullable
    public Item pickBlock(BlockHitResult hit) {
        Item item = world().getBlockState(hit.getBlockPos()).getBlock().asItem();
        return item instanceof BlockItem ? item : null;
    }

    /** Ctrl+click on an item: fetch a stack of it from linked storage. */
    public void fetch(Item item) {
        int stored = StorageManager.get().total(item);
        if (stored <= 0) {
            Messages.warn("No " + item.getDescription().getString() + " in linked storage");
            return;
        }
        int count = Math.min(stored, item.getDefaultMaxStackSize());
        queue(new FetchTask(Map.of(item, count)));
    }

    public void scanNearby() {
        if (world() == null || this.client.player == null) {
            return;
        }
        StorageManager.get().pruneMissing(world());
        int added = StorageManager.get().scanNearby(world(), this.client.player.blockPosition(), RtsConfig.get().scanRadius);
        int unread = StorageManager.get().unindexed().size();
        if (unread == 0) {
            Messages.info(added == 0 ? "No new containers nearby" : "Linked " + added + " container(s)");
            return;
        }
        Messages.info("Linked " + added + " container(s), reading " + unread + "...");
        boolean alreadyQueued = TaskQueue.get().snapshot().stream().anyMatch(t -> t instanceof IndexStorageTask);
        if (!alreadyQueued) {
            queue(new IndexStorageTask(this.client.player.blockPosition(), true));
        }
    }

    public void indexStorage(boolean onlyNew) {
        if (this.client.player == null) {
            return;
        }
        if (StorageManager.get().linked().isEmpty()) {
            Messages.warn("No linked storage. Use Link storage mode or Scan nearby first");
            return;
        }
        if (onlyNew && StorageManager.get().unindexed().isEmpty()) {
            Messages.info("Every linked container is already indexed");
            return;
        }
        queue(new IndexStorageTask(this.client.player.blockPosition(), onlyNew));
    }

    /** V: hide everything above the block under the cursor (or above the player), or turn it off. */
    public void toggleCut(@Nullable BlockHitResult hover) {
        if (this.controller.cutY() != null) {
            this.controller.setCut(this.client, null);
            Messages.info("Cut view off");
            return;
        }
        int y = hover != null ? hover.getBlockPos().getY() : (this.client.player != null ? this.client.player.getBlockY() + 2 : 64);
        this.controller.setCut(this.client, y);
        Messages.info("Cut view at Y=" + y + " - PageUp/PageDown to move, V to turn off");
    }

    public void moveCut(int delta) {
        Integer cut = this.controller.cutY();
        int base = cut != null ? cut : (this.client.player != null ? this.client.player.getBlockY() + 2 : 64);
        this.controller.setCut(this.client, base + delta);
    }

    /** Move the pending shape/paste one block (direction already camera-relative). */
    public void nudge(Direction d) {
        BuildSession s = this.session;
        if (s.pasteAnchor != null) {
            s.pasteAnchor = s.pasteAnchor.relative(d);
        }
        if (s.anchorA != null) {
            s.anchorA = s.anchorA.relative(d);
        }
        if (s.anchorB != null) {
            s.anchorB = s.anchorB.relative(d);
        }
    }

    public void saveBookmark(int slot) {
        this.session.bookmarks.put(slot, this.controller.camera().pose());
        Messages.info("Camera saved to " + slot + " - Shift+" + slot + " to come back");
    }

    public void gotoBookmark(int slot) {
        RtsCamera.Pose pose = this.session.bookmarks.get(slot);
        if (pose == null) {
            Messages.warn("No camera saved on " + slot + " (Ctrl+" + slot + " saves one)");
            return;
        }
        this.controller.camera().setPose(this.client, pose);
    }

    /** Block placed by the brush, for the cursor label; null when there's no block brush. */
    @Nullable
    public Block brushBlock() {
        return this.session.brush.getItem() instanceof BlockItem bi ? bi.getBlock() : null;
    }
}
