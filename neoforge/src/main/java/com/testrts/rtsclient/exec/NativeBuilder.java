package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.task.Reach;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Built-in replacement for Baritone's builder. Works one Y layer at a time (bottom-up when
 * building, top-down when clearing), so every block has something under it to attach to and
 * nothing falls on the player. Places and mines within reach, and walks to the next block when
 * nothing is in reach. All per-tick work is bounded so large orders don't stutter.
 */
public final class NativeBuilder {
    private static final int PLACE_DELAY_TICKS = 2;
    private static final int BREAK_TIMEOUT_TICKS = 20 * 20;
    private static final int MAX_WALK_FAILS = 3;
    private static final int RESCAN_TICKS = 20;
    /** A block is set aside after this many failed attempts in one pass. */
    private static final int MAX_FAILURES = 3;
    /** Extra passes over set-aside blocks once every layer is done. */
    private static final int MAX_SWEEPS = 2;
    /** Placements in a row that didn't show up before we try a different block. */
    private static final int MAX_PLACE_ATTEMPTS = 4;
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    private static final float[] YAWS = {0F, 90F, 180F, 270F};
    private static final float[] PITCHES = {0F, 75F, -75F};

    private Map<BlockPos, BlockState> order;
    /** The order as given (the cleanup phase swaps {@link #order} for the scaffolding). */
    private final Map<BlockPos, BlockState> originalOrder;
    private boolean clearing;
    private final boolean strict;
    private final boolean layered;
    /** Order positions grouped by Y, in the order layers are worked on. */
    private final TreeMap<Integer, List<BlockPos>> layers = new TreeMap<>();
    /** Block items the order itself uses; scaffolding prefers something else. */
    private final Set<Item> orderItems = new HashSet<>();

    // Scaffolding: temporary blocks that give an unsupported block something to attach to.
    private final java.util.ArrayDeque<BlockPos> scaffoldPlan = new java.util.ArrayDeque<>();
    @Nullable
    private BlockPos scaffoldFor;
    /** Every scaffold block we placed, removed again when the order is done. */
    private final Set<BlockPos> scaffolds = new LinkedHashSet<>();
    private int cleanupRounds;
    private boolean cleaning;

    // Pillaring: jump and place a block underneath, to reach blocks high above the ground.
    @Nullable
    private BlockPos pillarTarget;
    @Nullable
    private BlockPos pillarStep;
    private int pillarPlaced;
    private int pillarTicks;
    /** The current route search is for a spot to pillar up from (the target itself was unreachable). */
    private boolean seekingPillarSite;
    private final List<Integer> layerOrder = new ArrayList<>();
    private int layerIndex;
    /** Not-yet-done positions of the current layer (all positions when not layered). */
    private final Set<BlockPos> todo = new LinkedHashSet<>();
    /** Given up on for this pass (failed {@link #MAX_FAILURES} times); retried in the next sweep. */
    private final Set<BlockPos> skipped = new HashSet<>();
    /** Can never be done (unbreakable, no item for it). */
    private final Set<BlockPos> impossible = new HashSet<>();
    private final Map<BlockPos, Integer> failures = new java.util.HashMap<>();
    private final Map<BlockPos, Integer> placeAttempts = new java.util.HashMap<>();
    private int sweeps;
    private int rescanIn;

    private boolean paused;
    @Nullable
    private Item missingItem;
    private int cooldown;

    @Nullable
    private BlockPos breaking;
    private Direction breakingSide = Direction.UP;
    private int breakTicks;

    @Nullable
    private PathFinder.Search search;
    @Nullable
    private PathFollower walk;
    @Nullable
    private BlockPos walkTarget;
    private int walkFails;
    private String status = "starting";

    /** Scaffolding we placed that is still in the world (handed on if this builder is replaced). */
    public Set<BlockPos> scaffolds() {
        return this.scaffolds;
    }

    public NativeBuilder(Map<BlockPos, BlockState> order, boolean strict, boolean layered,
                         java.util.Collection<BlockPos> inheritedScaffolds) {
        this.order = new LinkedHashMap<>(order);
        this.clearing = !order.isEmpty() && order.values().iterator().next().isAir();
        this.strict = strict;
        this.layered = layered;
        this.originalOrder = this.order;
        this.scaffolds.addAll(inheritedScaffolds);
        for (BlockState st : this.order.values()) {
            if (!st.isAir()) {
                this.orderItems.add(st.getBlock().asItem());
            }
        }
        buildLayers();
    }

    private void buildLayers() {
        this.layers.clear();
        this.layerOrder.clear();
        this.layerIndex = 0;
        this.todo.clear();
        for (BlockPos p : this.order.keySet()) {
            this.layers.computeIfAbsent(this.layered ? p.getY() : 0, y -> new ArrayList<>()).add(p);
        }
        this.layerOrder.addAll(this.clearing ? this.layers.descendingKeySet() : this.layers.keySet());
    }

    public boolean isPaused() {
        return this.paused;
    }

    public void resume() {
        this.paused = false;
        this.missingItem = null;
    }

    public String status() {
        return this.status;
    }

    @Nullable
    public Item missingItem() {
        return this.missingItem;
    }

    public int skippedCount() {
        return this.skipped.size() + this.impossible.size();
    }

    /** Couldn't do it right now (no route, no support, rejected): try again later. */
    private void fail(BlockPos p) {
        this.todo.remove(p);
        if (this.failures.merge(p, 1, Integer::sum) >= MAX_FAILURES) {
            this.skipped.add(p);
        }
    }

    /** Can never be done. */
    private void impossible(BlockPos p) {
        this.todo.remove(p);
        this.impossible.add(p);
    }

    /** @return false once there is nothing left it can do. */
    public boolean tick(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (player == null || world == null || client.gameMode == null) {
            return false;
        }
        if (this.paused) {
            MovementInput.acquire(player).release();
            return true;
        }
        if (this.breaking != null) {
            continueBreaking(client);
            return true;
        }
        if (this.cooldown > 0) {
            this.cooldown--;
            return true;
        }
        if (this.pillarTarget != null) {
            tickPillar(client);
            return true;
        }
        if (!this.scaffoldPlan.isEmpty()) {
            tickScaffold(client);
            return true;
        }

        if (!advanceLayer(world)) {
            if (startCleanup(world)) {
                return true;
            }
            MovementInput.restore(client);
            int left = skippedCount();
            this.status = left == 0 ? "done" : "done, " + left + " skipped";
            return false;
        }
        String layerInfo = (this.cleaning ? " scaffolding" : "") + (this.layered && this.layerOrder.size() > 1
                ? " (layer " + (this.layerIndex + 1) + "/" + this.layerOrder.size() + ", " + this.todo.size() + " left)"
                : " (" + this.todo.size() + " left)");

        // 1) Work on something within reach (this may also plan scaffolding).
        if (actInReach(client)) {
            this.walk = null;
            this.search = null;
            this.seekingPillarSite = false;
            MovementInput.acquire(player).release();
            this.status = (this.cleaning ? "removing scaffolding" : this.clearing ? "mining" : "placing") + layerInfo;
            return true;
        }

        // 2) Keep heading for the current target, or pick the next one.
        BlockPos target = this.walkTarget != null && this.todo.contains(this.walkTarget) && (this.walk != null || this.search != null)
                ? this.walkTarget
                : pickWalkTarget(world, player);
        if (target != null) {
            walkStep(client, target, layerInfo);
        }
        return true;
    }

    /**
     * One step of getting within reach of {@code target}: plan a route (a little each tick), then
     * follow it. If there's no route, look for a spot nearby to pillar up from instead.
     */
    private void walkStep(Minecraft client, BlockPos target, String info) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (!target.equals(this.walkTarget) || (this.walk == null && this.search == null)) {
            this.walkTarget = target;
            this.walk = null;
            this.seekingPillarSite = false;
            this.search = PathFinder.start(world, player.blockPosition(), feet -> canWorkFrom(client, feet, target),
                    Vec3.atCenterOf(target));
        }
        if (this.search != null) {
            this.status = (this.seekingPillarSite ? "finding a spot to climb" : "planning") + info;
            PathFinder.Search.State s = this.search.step(PathFinder.NODES_PER_TICK);
            if (s == PathFinder.Search.State.FOUND) {
                List<BlockPos> path = this.search.result();
                this.search = null;
                if (path.isEmpty()) {
                    // Already standing at the best spot.
                    if (this.seekingPillarSite) {
                        this.seekingPillarSite = false;
                        startPillar(client, target);
                    } else {
                        // In position but still couldn't do it: try other blocks first.
                        unreachable(target);
                    }
                    return;
                }
                this.walk = new PathFollower(path);
            } else if (s == PathFinder.Search.State.FAILED) {
                this.search = null;
                if (!this.seekingPillarSite && hasScaffoldItem(client)) {
                    // Can't walk into reach: find ground below/near it and pillar up.
                    this.seekingPillarSite = true;
                    this.search = PathFinder.start(world, player.blockPosition(), feet -> pillarSite(world, feet, target),
                            Vec3.atCenterOf(target));
                } else {
                    unreachable(target);
                }
            }
            return;
        }
        PathFollower.State s = this.walk.tick(client);
        this.status = (this.seekingPillarSite ? "going to climb" : "walking") + info;
        if (s == PathFollower.State.WALKING) {
            return;
        }
        this.walk = null;
        if (s == PathFollower.State.ARRIVED && this.seekingPillarSite) {
            this.seekingPillarSite = false;
            startPillar(client, target);
            return;
        }
        if (s == PathFollower.State.STUCK && ++this.walkFails >= MAX_WALK_FAILS) {
            this.walkFails = 0;
            unreachable(target);
        }
    }

    private void unreachable(BlockPos target) {
        this.seekingPillarSite = false;
        if (this.scaffoldPlan.contains(target)) {
            abandonScaffold();
        } else {
            fail(target);
        }
    }

    // ------------------------------------------------------------------ scaffolding

    private boolean canScaffold(ClientLevel world, LocalPlayer player, BlockPos c) {
        if (!world.hasChunkAt(c) || this.order.containsKey(c) || player.getBoundingBox().intersects(new AABB(c))) {
            return false;
        }
        BlockState s = world.getBlockState(c);
        return (s.isAir() || s.canBeReplaced()) && !s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
    }

    /**
     * Find the fewest temporary blocks that connect {@code target} to something solid, so it can
     * be placed. Tries a short search first (bridges corner/edge gaps), then a column down.
     */
    private boolean planScaffold(ClientLevel world, LocalPlayer player, BlockPos target) {
        Direction[] dirs = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new java.util.HashMap<>();
        Map<BlockPos, Integer> depth = new java.util.HashMap<>();
        for (Direction d : dirs) {
            BlockPos c = target.relative(d);
            if (canScaffold(world, player, c)) {
                parent.put(c, target);
                depth.put(c, 1);
                queue.add(c);
            }
        }
        while (!queue.isEmpty() && parent.size() < 600) {
            BlockPos c = queue.poll();
            if (support(world, c) != null) {
                List<BlockPos> chain = new ArrayList<>();
                for (BlockPos x = c; !x.equals(target); x = parent.get(x)) {
                    chain.add(x);
                }
                // Place from the solid end toward the target, each one resting on the previous.
                this.scaffoldPlan.clear();
                this.scaffoldPlan.addAll(chain);
                this.scaffoldFor = target;
                return true;
            }
            int dd = depth.get(c);
            if (dd >= 6) {
                continue;
            }
            for (Direction d : dirs) {
                BlockPos n = c.relative(d);
                if (!parent.containsKey(n) && canScaffold(world, player, n)) {
                    parent.put(n, c);
                    depth.put(n, dd + 1);
                    queue.add(n);
                }
            }
        }
        // Fallback: a column straight down to the ground.
        List<BlockPos> column = new ArrayList<>();
        BlockPos c = target.below();
        while (column.size() < 24 && canScaffold(world, player, c)) {
            column.add(c);
            if (support(world, c) != null && support(world, c) == Direction.DOWN) {
                break;
            }
            c = c.below();
        }
        if (!column.isEmpty() && support(world, column.get(column.size() - 1)) != null) {
            this.scaffoldPlan.clear();
            for (int i = column.size() - 1; i >= 0; i--) {
                this.scaffoldPlan.add(column.get(i));
            }
            this.scaffoldFor = target;
            return true;
        }
        return false;
    }

    private void abandonScaffold() {
        if (this.scaffoldFor != null) {
            fail(this.scaffoldFor);
        }
        this.scaffoldPlan.clear();
        this.scaffoldFor = null;
    }

    private void tickScaffold(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        BlockPos c = this.scaffoldPlan.peekFirst();
        BlockState now = world.getBlockState(c);
        if (!now.isAir() && !now.canBeReplaced()) {
            this.scaffoldPlan.pollFirst();
            return;
        }
        if (!Reach.inReach(client, c)) {
            walkStep(client, c, " (scaffolding)");
            return;
        }
        this.walk = null;
        this.search = null;
        MovementInput.acquire(player).release();
        if (player.getBoundingBox().intersects(new AABB(c)) || support(world, c) == null) {
            abandonScaffold();
            return;
        }
        Item item = scaffoldItem(client);
        if (item == null || !Inventories.hold(client, item)) {
            pauseForScaffolding();
            return;
        }
        this.status = "placing scaffolding";
        if (place(client, c, Block.byItem(item).defaultBlockState())) {
            this.scaffolds.add(c.immutable());
            this.scaffoldPlan.pollFirst();
            this.cooldown = PLACE_DELAY_TICKS;
        } else {
            abandonScaffold();
        }
    }

    @Nullable
    private Item scaffoldItem(Minecraft client) {
        Item fallback = null;
        for (String id : com.testrts.rtsclient.config.RtsConfig.get().scaffoldBlocks) {
            net.minecraft.resources.ResourceLocation ident = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (ident == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(ident)) {
                continue;
            }
            Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ident);
            if (Inventories.count(client, item) > 0) {
                if (!this.orderItems.contains(item)) {
                    return item;
                }
                if (fallback == null) {
                    fallback = item;
                }
            }
        }
        return fallback;
    }

    private boolean hasScaffoldItem(Minecraft client) {
        return scaffoldItem(client) != null;
    }

    /** Out of scaffolding blocks: pause and ask for some (the build task fetches it from storage). */
    private void pauseForScaffolding() {
        Item want = net.minecraft.world.item.Items.DIRT;
        for (String id : com.testrts.rtsclient.config.RtsConfig.get().scaffoldBlocks) {
            net.minecraft.resources.ResourceLocation ident = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (ident != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(ident)) {
                Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ident);
                if (com.testrts.rtsclient.storage.StorageManager.get().total(item) > 0) {
                    want = item;
                    break;
                }
            }
        }
        this.paused = true;
        this.missingItem = want;
        this.status = "need scaffolding blocks (" + want.getDescription().getString() + ")";
    }

    /** When everything is built, mine the scaffolding back out. */
    private boolean startCleanup(ClientLevel world) {
        if (this.scaffolds.isEmpty() || !com.testrts.rtsclient.config.RtsConfig.get().removeScaffolding
                || this.cleanupRounds >= 3) {
            return false;
        }
        Map<BlockPos, BlockState> cleanup = new LinkedHashMap<>();
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (BlockPos p : this.scaffolds) {
            BlockState s = world.getBlockState(p);
            // Leave planned positions alone (a pillar block there was replaced by the real block),
            // and don't pull out a block that sand or gravel is resting on.
            if (!s.isAir() && !this.originalOrder.containsKey(p)
                    && !(world.getBlockState(p.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock)) {
                cleanup.put(p, air);
            }
        }
        this.scaffolds.clear();
        if (cleanup.isEmpty()) {
            return false;
        }
        this.cleanupRounds++;
        this.cleaning = true;
        this.order = cleanup;
        this.clearing = true;
        this.skipped.clear();
        this.failures.clear();
        this.sweeps = MAX_SWEEPS;
        this.walk = null;
        this.search = null;
        buildLayers();
        return true;
    }

    // ------------------------------------------------------------------ pillaring

    /** Ground near (below or beside) the target with a clear column above it to climb. */
    private boolean pillarSite(ClientLevel world, BlockPos feet, BlockPos target) {
        double dx = feet.getX() - target.getX();
        double dz = feet.getZ() - target.getZ();
        // From up to ~3.4 blocks away (horizontally) the target is still in reach once we're level with it.
        if (dx * dx + dz * dz > 3.4 * 3.4 || feet.getY() > target.getY()) {
            return false;
        }
        int climb = target.getY() - 1 - feet.getY();
        if (climb > 24) {
            return false;
        }
        for (int i = 0; i <= climb + 1; i++) {
            BlockPos c = feet.above(i);
            // Head room all the way up. Passing through not-yet-built planned spots is fine: the
            // pillar block there gets swapped for the real one when that layer is built.
            if (i > 0 && !Walkability.passable(world, c.above(1))) {
                return false;
            }
        }
        return true;
    }

    private void startPillar(Minecraft client, BlockPos target) {
        this.pillarTarget = target;
        this.pillarStep = client.player.blockPosition();
        this.pillarPlaced = 0;
        this.pillarTicks = 0;
        this.status = "climbing";
    }

    private void tickPillar(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        MovementInput input = MovementInput.acquire(player);
        BlockPos target = this.pillarTarget;
        if (player.onGround() && Reach.inReach(client, target)) {
            input.release();
            this.pillarTarget = null;
            return;
        }
        if (++this.pillarTicks > 20 * 40 || this.pillarPlaced > 30 || !Walkability.passable(world, player.blockPosition().above(2))) {
            // Ceiling in the way or it's taking far too long.
            input.release();
            this.pillarTarget = null;
            unreachable(target);
            return;
        }
        Item item = scaffoldItem(client);
        if (item == null) {
            input.release();
            this.pillarTarget = null;
            pauseForScaffolding();
            return;
        }
        Inventories.hold(client, item);
        input.forward = false;
        if (player.onGround()) {
            this.pillarStep = player.blockPosition();
            input.jump = true;
            Reach.face(player, player.getYRot(), 90.0F);
            return;
        }
        input.jump = false;
        BlockPos step = this.pillarStep;
        // Near the top of the jump: put a block where our feet just were.
        if (player.getY() >= step.getY() + 1.0 && world.getBlockState(step).canBeReplaced()) {
            BlockPos below = step.below();
            BlockHitResult hit = new BlockHitResult(new Vec3(step.getX() + 0.5, step.getY(), step.getZ() + 0.5), Direction.UP, below, false);
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
            player.swing(InteractionHand.MAIN_HAND);
            this.scaffolds.add(step.immutable());
            this.pillarPlaced++;
            this.status = "climbing (" + this.pillarPlaced + ")";
        }
    }

    /**
     * Make sure {@link #todo} holds the current layer's unfinished blocks, moving to the next layer
     * when this one is finished. Rescans only every {@link #RESCAN_TICKS} ticks.
     *
     * @return false when every layer is finished
     */
    private boolean advanceLayer(ClientLevel world) {
        if (--this.rescanIn <= 0 || this.todo.isEmpty()) {
            this.rescanIn = RESCAN_TICKS;
            while (this.layerIndex < this.layerOrder.size()) {
                this.todo.clear();
                for (BlockPos p : this.layers.get(this.layerOrder.get(this.layerIndex))) {
                    if (!this.skipped.contains(p) && !this.impossible.contains(p) && !done(world, p, this.order.get(p))) {
                        this.todo.add(p);
                    }
                }
                if (!this.todo.isEmpty()) {
                    return true;
                }
                this.layerIndex++;
                this.walk = null;
                this.search = null;
                if (this.layerIndex >= this.layerOrder.size() && !this.skipped.isEmpty() && this.sweeps < MAX_SWEEPS) {
                    // Everything else is done; blocks that failed earlier may work now (new supports, routes).
                    this.sweeps++;
                    this.skipped.clear();
                    this.failures.clear();
                    this.layerIndex = 0;
                }
            }
            return false;
        }
        return true;
    }

    public void stop(Minecraft client) {
        if (this.breaking != null && client.gameMode != null) {
            client.gameMode.stopDestroyBlock();
        }
        this.breaking = null;
        this.walk = null;
        this.search = null;
        this.pillarTarget = null;
        this.scaffoldPlan.clear();
        MovementInput.restore(client);
    }

    // ------------------------------------------------------------------ decisions

    private static boolean done(ClientLevel world, BlockPos pos, BlockState wanted) {
        if (!world.hasChunkAt(pos)) {
            return false;
        }
        BlockState current = world.getBlockState(pos);
        if (wanted.isAir()) {
            return current.isAir() || current.getBlock() instanceof LiquidBlock;
        }
        return current.getBlock() == wanted.getBlock();
    }

    @Nullable
    private BlockPos pickWalkTarget(ClientLevel world, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos p : this.todo) {
            double d = Vec3.atCenterOf(p).distanceToSqr(eye);
            // Prefer blocks that already have something to attach to.
            if (!this.clearing && !hasSupport(world, p)) {
                d += 10_000;
            }
            if (d < bestScore) {
                bestScore = d;
                best = p;
            }
        }
        return best;
    }

    /** A spot to stand where the target is in reach and we're not standing inside it. */
    private static boolean canWorkFrom(Minecraft client, BlockPos feet, BlockPos target) {
        if (feet.equals(target) || feet.above().equals(target)) {
            return false;
        }
        double reach = Reach.reach(client) - 0.4;
        Vec3 eye = new Vec3(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
        return eye.distanceToSqr(Vec3.atCenterOf(target)) <= reach * reach;
    }

    private boolean actInReach(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        Vec3 eye = player.getEyePosition();
        double r = Reach.reach(client);
        double r2 = r * r;
        List<BlockPos> inReach = new ArrayList<>();
        for (BlockPos p : this.todo) {
            if (Vec3.atCenterOf(p).distanceToSqr(eye) <= r2) {
                inReach.add(p);
            }
        }
        if (inReach.isEmpty()) {
            return false;
        }
        inReach.sort(Comparator.comparingDouble(p -> Vec3.atCenterOf(p).distanceToSqr(eye)));
        if (this.clearing) {
            BlockPos below = player.blockPosition().below();
            for (BlockPos p : inReach) {
                // Leave the block we're standing on for last.
                if (p.equals(below) && inReach.size() > 1) {
                    continue;
                }
                BlockState state = world.getBlockState(p);
                if (done(world, p, this.order.get(p))) {
                    this.todo.remove(p);
                    continue;
                }
                if (state.getDestroySpeed(world, p) < 0) {
                    impossible(p);
                    continue;
                }
                startBreaking(client, p);
                return true;
            }
            return false;
        }
        BlockPos unsupported = null;
        boolean supportedInReach = false;
        for (BlockPos p : inReach) {
            BlockState wanted = this.order.get(p);
            if (done(world, p, wanted)) {
                this.todo.remove(p);
                continue;
            }
            if (!placeable(world, player, p)) {
                if (unsupported == null && !player.getBoundingBox().intersects(new AABB(p)) && !hasSupport(world, p)) {
                    unsupported = p;
                }
                continue;
            }
            supportedInReach = true;
            BlockState current = world.getBlockState(p);
            if (!current.isAir() && !current.canBeReplaced()) {
                // Something else is in the way: mine it first.
                if (current.getDestroySpeed(world, p) < 0) {
                    impossible(p);
                    continue;
                }
                startBreaking(client, p);
                return true;
            }
            Item item = wanted.getBlock().asItem();
            if (item == Items.AIR) {
                impossible(p);
                continue;
            }
            if (!Inventories.hold(client, item)) {
                this.paused = true;
                this.missingItem = item;
                this.status = "missing " + item.getDescription().getString();
                return true;
            }
            if (this.placeAttempts.merge(p, 1, Integer::sum) > MAX_PLACE_ATTEMPTS) {
                // The server keeps rejecting it (entity in the way, protection...): come back later.
                this.placeAttempts.remove(p);
                fail(p);
                continue;
            }
            if (place(client, p, wanted)) {
                this.cooldown = PLACE_DELAY_TICKS;
                return true;
            }
        }
        // Nothing here can be attached to anything. Scaffold when the whole layer is stuck, or right
        // away when nothing in reach can be placed and no planned neighbour will give it support
        // (e.g. a block floating over water), instead of walking off and coming back.
        if (unsupported != null && (!anySupported(world) || !supportedInReach && !neighbourPending(unsupported))) {
            if (planScaffold(world, player, unsupported)) {
                this.status = "planning scaffolding";
                return true;
            }
            fail(unsupported);
        }
        return false;
    }

    /** Whether a block of this order still to be placed shares a face with {@code pos}. */
    private boolean neighbourPending(BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (this.todo.contains(pos.relative(d))) {
                return true;
            }
        }
        return false;
    }

    private boolean anySupported(ClientLevel world) {
        for (BlockPos p : this.todo) {
            if (hasSupport(world, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSupport(ClientLevel world, BlockPos pos) {
        return support(world, pos) != null;
    }

    private static boolean placeable(ClientLevel world, LocalPlayer player, BlockPos pos) {
        // Don't place a block inside ourselves.
        if (player.getBoundingBox().intersects(new AABB(pos))) {
            return false;
        }
        return hasSupport(world, pos);
    }

    /** The side of the target to click: a neighbour that is solid and won't open a GUI. */
    @Nullable
    private static Direction support(ClientLevel world, BlockPos pos) {
        for (Direction d : SUPPORT_ORDER) {
            BlockPos n = pos.relative(d);
            if (!world.hasChunkAt(n)) {
                continue;
            }
            BlockState s = world.getBlockState(n);
            if (s.isAir() || s.canBeReplaced() || s.getCollisionShape(world, n).isEmpty() || interactive(s)) {
                continue;
            }
            return d;
        }
        return null;
    }

    private static boolean interactive(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock
                || b instanceof BedBlock || b instanceof AnvilBlock || b instanceof NoteBlock
                || b instanceof DiodeBlock || b instanceof StonecutterBlock || b instanceof GrindstoneBlock
                || b instanceof LoomBlock || b instanceof CakeBlock || b instanceof ComposterBlock
                || b instanceof FlowerPotBlock || b instanceof AbstractCauldronBlock;
    }

    // ------------------------------------------------------------------ placing

    private boolean place(Minecraft client, BlockPos pos, BlockState wanted) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        Direction toSupport = support(world, pos);
        if (toSupport == null) {
            return false;
        }
        BlockPos supportPos = pos.relative(toSupport);
        Direction face = toSupport.getOpposite();
        Vec3 hitVec = Vec3.atCenterOf(pos).add(toSupport.getStepX() * 0.5, toSupport.getStepY() * 0.5, toSupport.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, supportPos, false);

        // Vanilla decides the placed state from where the player looks. Try the four directions
        // (and up/down) until the block would come out the way the order wants it.
        float[] rotation = rotationFor(player, wanted, hit);
        if (rotation == null) {
            if (this.strict && !wanted.getBlock().defaultBlockState().equals(wanted)) {
                impossible(pos);
                return false;
            }
            Reach.lookAt(player, hitVec);
        } else {
            Reach.face(player, rotation[0], rotation[1]);
        }
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    @Nullable
    private static float[] rotationFor(LocalPlayer player, BlockState wanted, BlockHitResult hit) {
        ItemStack stack = player.getMainHandItem();
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            for (float pitch : PITCHES) {
                for (float yaw : YAWS) {
                    player.setYRot(yaw);
                    player.setXRot(pitch);
                    BlockState result = wanted.getBlock().getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit));
                    if (wanted.equals(result)) {
                        return new float[]{yaw, pitch};
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Some modded blocks don't like being asked hypothetically.
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
        return null;
    }

    // ------------------------------------------------------------------ mining

    private void startBreaking(Minecraft client, BlockPos pos) {
        LocalPlayer player = client.player;
        BlockState state = client.level.getBlockState(pos);
        Inventories.holdBestTool(client, state);
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        this.breakingSide = Direction.getNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        Reach.lookAt(player, center.add(this.breakingSide.getStepX() * 0.5, this.breakingSide.getStepY() * 0.5,
                this.breakingSide.getStepZ() * 0.5));
        this.breaking = pos.immutable();
        this.breakTicks = 0;
        client.gameMode.startDestroyBlock(pos, this.breakingSide);
        player.swing(InteractionHand.MAIN_HAND);
    }

    private void continueBreaking(Minecraft client) {
        BlockPos pos = this.breaking;
        BlockState state = client.level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock) {
            this.breaking = null;
            this.todo.remove(pos);
            return;
        }
        if (++this.breakTicks > BREAK_TIMEOUT_TICKS || !Reach.inReach(client, pos)) {
            client.gameMode.stopDestroyBlock();
            this.breaking = null;
            fail(pos);
            return;
        }
        if (client.gameMode.continueDestroyBlock(pos, this.breakingSide)) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
    }
}
