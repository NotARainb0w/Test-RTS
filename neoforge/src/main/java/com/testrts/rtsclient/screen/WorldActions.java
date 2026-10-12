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
import com.testrts.rtsclient.task.MaterialPlanner;
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
        if (!TaskQueue.get().isStarted()) {
            Messages.info(task.label() + " queued - press Start to run " + (TaskQueue.get().size() == 1 ? "it" : "the queue"));
        } else if (TaskQueue.get().size() > 1) {
            Messages.info(task.label() + " queued (" + (TaskQueue.get().size() - 1) + " ahead)");
        }
    }

    // ------------------------------------------------------------------ clicks

    /** Left click on a block with the current tool. */
    /** The item Interact uses: the selected item unless it's a block (blocks go to Build). Null: empty hand. */
    @Nullable
    public static Item interactItem(BuildSession s) {
        return s.brush.isEmpty() || s.brush.getItem() instanceof BlockItem ? null : s.brush.getItem();
    }

    /** Interact clicked a mob: walk over and use the selected item on it, or attack it. */
    public void interactEntity(net.minecraft.world.entity.Entity entity) {
        queue(new com.testrts.rtsclient.task.EntityTask(entity, interactItem(this.session)));
    }

    public void primary(BlockHitResult hit) {
        ClientLevel world = world();
        if (world == null) {
            return;
        }
        BuildSession s = this.session;
        if (s.roofPick) {
            s.roofPick = false;
            makeRoof(hit);
            return;
        }
        switch (s.tool) {
            case BUILD -> {
                boolean styledBridge = s.shape == com.testrts.rtsclient.build.BuildShape.BRIDGE && s.bridgeStyle != com.testrts.rtsclient.build.BridgeStyle.CLASSIC;
                if (!(s.brush.getItem() instanceof BlockItem) && s.mix.isEmpty() && !styledBridge) {
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
                queue(new InteractTask(hit, state.getBlock().getName().getString(), interactItem(s)));
            }
            case LINK -> {
                var partHost = world.getBlockEntity(hit.getBlockPos());
                for (com.testrts.rtsclient.storage.MeNetwork network : com.testrts.rtsclient.storage.MeNetwork.all()) {
                    if (!network.enabled()) {
                        continue;
                    }
                    var meSide = network.mod().terminalSideAt(partHost, hit.getLocation(), hit.getDirection());
                    if (meSide != null) {
                        // An AE2 ME terminal or a Refined Storage grid: materials can be fetched from its network.
                        boolean linked = network.toggle(hit.getBlockPos(), meSide);
                        Messages.info((linked ? "Linked " : "Unlinked ") + network.mod().terminalName() + " " + hit.getBlockPos().toShortString());
                        return;
                    }
                }
                if (com.testrts.rtsclient.storage.Ae2.isPartHost(partHost)) {
                    Messages.warn("No ME terminal there - click the terminal itself");
                    return;
                }
                if (!StorageManager.isContainer(world, hit.getBlockPos())) {
                    Messages.warn("That's not a storage block");
                    return;
                }
                boolean linked = StorageManager.get().toggle(world, hit.getBlockPos());
                Messages.info((linked ? "Linked " : "Unlinked ") + hit.getBlockPos().toShortString());
            }
            case BLUEPRINT -> blueprintClick(hit);
            case TERRAIN -> regionClick(hit.getBlockPos(), this::confirmTerrain);
            case MEASURE -> measureClick(hit.getBlockPos());
            case PROTECT -> {
                var area = com.testrts.rtsclient.build.Protection.get().areaAt(hit.getBlockPos());
                if (area != null && this.session.anchorA == null) {
                    com.testrts.rtsclient.build.Protection.get().remove(area);
                    Messages.info("Protected area " + area.width() + "x" + area.depth() + " removed");
                } else {
                    regionClick(hit.getBlockPos(), this::confirmProtect);
                }
            }
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
        int unqueued = 0;
        if (clearing) {
            // Destroy over the hologram of queued builds: those blocks come out of the orders (a doorway in
            // a queued box). Real blocks in the shape are still cleared below.
            var shape = com.testrts.rtsclient.build.ShapeGenerator.generate(request.shape(), request.a(), request.b(), request.height(),
                    request.hollow(), request.limit() + 1, request.inverted(), request.bridgeStyle());
            unqueued = removeFromQueuedBuilds(shape.positions());
            if (unqueued > 0) {
                Messages.info("Removed " + unqueued + " block(s) from the queued orders");
            }
        }
        if (plan.changes().isEmpty()) {
            if (unqueued == 0) {
                Messages.info("Nothing to do there");
            }
            return;
        }
        pushUndo(label, plan.changes());
        queue(new BuildTask(label, plan.changes(), clearing));
    }

    /** Take {@code positions} out of every queued build order; orders left empty come off the queue. */
    private int removeFromQueuedBuilds(java.util.Collection<BlockPos> positions) {
        int removed = 0;
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.clearing()) {
                removed += build.removePositions(positions);
                if (build.blocks().isEmpty()) {
                    TaskQueue.get().remove(this.client, build);
                }
            }
        }
        return removed;
    }

    /** A queued build wants a block at {@code p} that isn't there yet (a ghost block of the hologram). */
    public static boolean isQueuedGhost(ClientLevel world, BlockPos p) {
        for (RtsTask task : TaskQueue.get().snapshot()) {
            if (task instanceof BuildTask build && !build.clearing()) {
                BlockState wanted = build.blocks().get(p);
                if (wanted != null && !com.testrts.rtsclient.task.MaterialPlanner.matches(world.getBlockState(p), wanted)) {
                    return true;
                }
            }
        }
        return false;
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
            Messages.info((s.cutOnCopy ? "Move" : "Copy") + ": first corner set - click the opposite corner");
            return;
        }
        BlockPos a = s.anchorA;
        s.clearSelection();
        copyRegion(a, p);
    }

    private void copyRegion(BlockPos a, BlockPos b) {
        BuildSession s = this.session;
        ClientLevel world = world();
        boolean move = s.cutOnCopy;
        String name = move ? "Moved building" : "Copy " + (Blueprint.loadAll().size() + 1);
        Blueprint bp = Blueprint.capture(world, a, b, name, RtsConfig.get().maxShapeBlocks);
        if (bp.blockCount() == 0) {
            Messages.warn("Nothing to copy there");
            return;
        }
        s.clipboard = bp;
        s.pasteTurns = 0;
        s.pasteMirror = false;
        s.pasteAnchor = null;
        s.arrayX = 1;
        s.arrayZ = 1;
        if (move) {
            // The original is only taken down once the copy has a place to go.
            s.moveFrom = BlockPos.containing(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            s.moveTo = BlockPos.containing(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
            s.moveBlueprint = bp;
            Messages.success("Picked up " + bp.blockCount() + " blocks (" + bp.size()
                    + ") - move the cursor to the new spot, R to rotate, click to place, Enter to move it");
        } else {
            s.moveFrom = null;
            s.moveTo = null;
            Messages.success("Copied " + bp.blockCount() + " blocks (" + bp.size() + ") - click to place it, Save to keep it");
        }
    }


    public void confirmPaste() {
        BuildSession s = this.session;
        ClientLevel world = world();
        if (s.clipboard == null || s.pasteAnchor == null || world == null) {
            return;
        }
        Blueprint bp = s.clipboard;
        Map<BlockPos, BlockState> placed = pasteBlocks(s, bp, s.pasteAnchor);
        Map<BlockPos, BlockState> build = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : placed.entrySet()) {
            if (!MaterialPlanner.matches(world.getBlockState(e.getKey()), e.getValue())) {
                build.put(e.getKey(), e.getValue());
            }
        }
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        if (s.pasteClearsAir) {
            if (bp.volume() > RtsConfig.get().maxShapeBlocks) {
                Messages.warn("Too big to clear the air inside; pasting blocks only");
            } else {
                for (BlockPos p : pasteAir(s, bp, s.pasteAnchor)) {
                    BlockState now = world.getBlockState(p);
                    if (!now.isAir() && !(now.getBlock() instanceof LiquidBlock) && now.getDestroySpeed(world, p) >= 0) {
                        clear.put(p, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
        s.pasteAnchor = null;
        if (s.moving()) {
            // Moving: take the original down first (its drops feed the new build), except where the
            // new copy overlaps it with the same blocks.
            for (BlockPos p : BlockPos.betweenClosed(s.moveFrom, s.moveTo)) {
                BlockPos q = p.immutable();
                BlockState now = world.getBlockState(q);
                BlockState wanted = placed.get(q);
                if (now.isAir() || now.getBlock() instanceof LiquidBlock || now.getDestroySpeed(world, q) < 0
                        || wanted != null && wanted.getBlock() == now.getBlock()) {
                    continue;
                }
                clear.put(q, Blocks.AIR.defaultBlockState());
            }
            s.moveFrom = null;
            s.moveTo = null;
            s.moveBlueprint = null;
            s.clipboard = null;
            Messages.info("Moving " + bp.name() + ": clearing the old spot, then building the new one");
        }
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

    /** Protect tool: the rectangle between the two corners, at every height. */
    private void confirmProtect() {
        BuildSession s = this.session;
        if (s.anchorA == null || s.anchorB == null) {
            return;
        }
        var area = com.testrts.rtsclient.build.Protection.Area.of(s.anchorA, s.anchorB);
        s.clearSelection();
        com.testrts.rtsclient.build.Protection.get().add(area);
        Messages.success("Protected " + area.width() + "x" + area.depth() + " (all heights): the builder won't break blocks there");
    }

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
            case QUARRY -> {
                int top = Math.max(a.getY(), b.getY());
                int bottom = Math.max(world().getMinBuildHeight(), Math.min(a.getY(), b.getY()) - (s.terrainClearHeight - 1));
                queue(new com.testrts.rtsclient.task.QuarryTask(new BlockPos(a.getX(), top, a.getZ()), new BlockPos(b.getX(), bottom, b.getZ())));
            }
            case FELL_TREES -> {
                int y = Math.min(a.getY(), b.getY());
                queue(new com.testrts.rtsclient.task.TreeFellTask(new BlockPos(a.getX(), y - 3, a.getZ()),
                        new BlockPos(b.getX(), Math.max(a.getY(), b.getY()) + 32, b.getZ()), true));
            }
            case PLANT_TREES -> plantTrees(a, b);
            case HARVEST -> {
                int y0 = Math.min(a.getY(), b.getY()) - 2;
                int y1 = Math.max(a.getY(), b.getY()) + 3;
                queue(new com.testrts.rtsclient.task.HarvestTask(new BlockPos(a.getX(), y0, a.getZ()), new BlockPos(b.getX(), y1, b.getZ())));
            }
        }
    }

    /** Flatten fills with the brush block, or dirt if the brush isn't a block. */
    /** The blueprint placed at {@code origin}, repeated as the session's grid of copies. */
    public static Map<BlockPos, BlockState> pasteBlocks(BuildSession s, Blueprint bp, BlockPos origin) {
        int[] f = bp.footprint(s.pasteTurns);
        Map<BlockPos, BlockState> out = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(1, s.arrayX); i++) {
            for (int j = 0; j < Math.max(1, s.arrayZ); j++) {
                BlockPos o = origin.offset(i * (f[0] + s.arrayGap), 0, j * (f[1] + s.arrayGap));
                out.putAll(bp.placed(o, s.pasteTurns, s.pasteMirror));
            }
        }
        return out;
    }

    public static java.util.List<BlockPos> pasteAir(BuildSession s, Blueprint bp, BlockPos origin) {
        int[] f = bp.footprint(s.pasteTurns);
        java.util.List<BlockPos> out = new java.util.ArrayList<>();
        for (int i = 0; i < Math.max(1, s.arrayX); i++) {
            for (int j = 0; j < Math.max(1, s.arrayZ); j++) {
                BlockPos o = origin.offset(i * (f[0] + s.arrayGap), 0, j * (f[1] + s.arrayGap));
                out.addAll(bp.placedAir(o, s.pasteTurns, s.pasteMirror));
            }
        }
        return out;
    }

    /** Smart roof: find the room under the clicked wall top and pin a roof blueprint on it. */
    private void makeRoof(BlockHitResult hit) {
        BuildSession s = this.session;
        ClientLevel world = world();
        BlockState brush = s.brushState(0F);
        if (world == null) {
            return;
        }
        String[] error = new String[1];
        com.testrts.rtsclient.build.RoofPlanner.Roof roof = com.testrts.rtsclient.build.RoofPlanner.plan(
                world, hit.getBlockPos(), brush, s.roofStyle, s.roofLook, error);
        if (roof == null) {
            Messages.warn(error[0]);
            return;
        }
        s.tool = ToolMode.BLUEPRINT;
        s.clipboard = roof.blueprint();
        s.pasteTurns = 0;
        s.pasteMirror = false;
        s.arrayX = 1;
        s.arrayZ = 1;
        s.moveFrom = null;
        s.moveTo = null;
        s.clearSelection();
        s.pasteAnchor = roof.origin();
        Messages.success(roof.blueprint().name() + " for a " + roof.width() + " x " + roof.depth()
                + " room - Enter or click to build, arrows to nudge, X to cancel");
    }

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

    private static final Block[] SAPLINGS = {Blocks.OAK_SAPLING, Blocks.BIRCH_SAPLING, Blocks.SPRUCE_SAPLING,
            Blocks.JUNGLE_SAPLING, Blocks.ACACIA_SAPLING, Blocks.DARK_OAK_SAPLING, Blocks.CHERRY_SAPLING, Blocks.MANGROVE_PROPAGULE};

    /** The sapling Plant trees uses: the brush if it is one, else whichever sapling you have most of (oak if none). */
    public static Block treeSapling(BuildSession s) {
        if (s.brush.getItem() instanceof BlockItem bi && bi.getBlock() instanceof net.minecraft.world.level.block.SaplingBlock) {
            return bi.getBlock();
        }
        Minecraft mc = Minecraft.getInstance();
        Block best = Blocks.OAK_SAPLING;
        int bestCount = 0;
        for (Block sapling : SAPLINGS) {
            var item = sapling.asItem();
            int n = (mc.player == null ? 0 : mc.player.getInventory().countItem(item)) + StorageManager.get().total(item);
            if (n > bestCount) {
                best = sapling;
                bestCount = n;
            }
        }
        return best;
    }

    private void plantTrees(BlockPos a, BlockPos b) {
        Block sapling = treeSapling(this.session);
        Map<BlockPos, BlockState> order = TerrainPlanner.plantTrees(world(), a, b, sapling, this.session.treeSpacing);
        if (order.isEmpty()) {
            Messages.info("Nowhere to plant there: needs grass or dirt with room above, away from other trees");
            return;
        }
        pushUndo("Plant trees", order);
        queue(new BuildTask("Plant " + order.size() + " " + sapling.getName().getString(), order, false));
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
    /** Mine a single block (Shift+click with the Build tool). */
    public void destroyAt(BlockPos pos) {
        ClientLevel world = world();
        if (world == null) {
            return;
        }
        BlockState now = world.getBlockState(pos);
        if (now.isAir() || now.getBlock() instanceof LiquidBlock || now.getDestroySpeed(world, pos) < 0) {
            Messages.warn("Nothing to mine there");
            return;
        }
        Map<BlockPos, BlockState> clear = new LinkedHashMap<>();
        clear.put(pos.immutable(), Blocks.AIR.defaultBlockState());
        pushUndo("Mine " + now.getBlock().getName().getString(), clear);
        queue(new BuildTask("Mine " + now.getBlock().getName().getString(), clear, true));
    }

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
        for (com.testrts.rtsclient.storage.MeNetwork n : com.testrts.rtsclient.storage.MeNetwork.all()) {
            n.pruneMissing(world());
        }
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
        boolean networks = false;
        boolean unreadNetworks = false;
        for (com.testrts.rtsclient.storage.MeNetwork n : com.testrts.rtsclient.storage.MeNetwork.all()) {
            if (n.reachable(this.client)) {
                networks = true;
                unreadNetworks |= !n.indexed();
            }
        }
        if (StorageManager.get().linked().isEmpty() && !networks) {
            Messages.warn("No linked storage. Use Link storage mode or Scan nearby first (or carry a wireless terminal)");
            return;
        }
        if (onlyNew && StorageManager.get().unindexed().isEmpty() && !unreadNetworks) {
            Messages.info("Every linked container and storage network is already indexed");
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
