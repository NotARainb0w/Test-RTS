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
    /**
     * Times one block may be mined before it counts as a failure: a block the server keeps putting
     * back (spawn protection, a claim) would otherwise be mined forever. Gravel falling into a
     * cleared spot legitimately needs a few.
     */
    private static final int MAX_BREAK_ATTEMPTS = 8;
    /** Ticks the pillar may go without gaining height before we give up on it. */
    private static final int PILLAR_STALL_TICKS = 80;
    /** Times we try to step out of the way of a block our own body is blocking. */
    private static final int MAX_STEP_ASIDE = 2;
    /** Minimum time between "can't get there" messages, so a big unreachable area doesn't spam. */
    private static final long WARN_INTERVAL_MS = 5000L;
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
    private int pillarBestY;
    private int pillarStall;
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
    private final Map<BlockPos, Integer> breakAttempts = new java.util.HashMap<>();
    /** Spots this order has placed a block in; never mined again even if the block changed afterwards. */
    private final Set<BlockPos> placedHere = new HashSet<>();
    /** Walks toward a target that ended stuck, per target. */
    private final Map<BlockPos, Integer> walkStucks = new java.util.HashMap<>();
    /** Times we stepped aside for a target our body was in the way of. */
    private final Map<BlockPos, Integer> stepAsides = new java.util.HashMap<>();
    private long lastWarn;
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
            this.search = PathFinder.start(world, Walkability.feetNode(world, player), feet -> canWorkFrom(client, feet, target),
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
                    } else if (!stepAside(client, target)) {
                        // In position but still couldn't do it: try other blocks first.
                        unreachable(target);
                    }
                    return;
                }
                this.walk = new PathFollower(path, this::mayBreakInWay, false);
            } else if (s == PathFinder.Search.State.FAILED) {
                this.search = null;
                if (!this.seekingPillarSite && hasScaffoldItem(client)) {
                    // Can't walk into reach: find ground below/near it and pillar up.
                    this.seekingPillarSite = true;
                    this.search = PathFinder.start(world, Walkability.feetNode(world, player), feet -> pillarSite(world, feet, target),
                            Vec3.atCenterOf(target));
                } else {
                    unreachable(target);
                }
            }
            return;
        }
        PathFollower failed = this.walk;
        PathFollower.State s = failed.tick(client);
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
        if (s == PathFollower.State.STUCK) {
            MovementInput.acquire(player).release();
            if (!failed.offPath()) {
                // Next search (next tick, from where we are now) goes around this step.
                PathFinder.reportStuck(failed.failedFrom(), failed.failedTo());
            }
            if (this.walkStucks.merge(target, 1, Integer::sum) >= MAX_WALK_FAILS) {
                this.walkStucks.remove(target);
                warn("Can't get to " + target.toShortString() + " (stuck), doing other blocks first");
                unreachable(target);
            }
        }
    }

    /** Report a problem, at most every few seconds. */
    private void warn(String message) {
        long now = System.currentTimeMillis();
        if (now - this.lastWarn >= WARN_INTERVAL_MS) {
            this.lastWarn = now;
            com.testrts.rtsclient.util.Messages.warn(message);
        }
    }

    /**
     * What the follower may mine to get unstuck: anything soft (it checks that) that isn't part of
     * the build, or that the order wants gone anyway. Never our own scaffolding.
     */
    private boolean mayBreakInWay(BlockPos pos) {
        BlockState wanted = this.originalOrder.get(pos);
        if (wanted != null) {
            return wanted.isAir();
        }
        return !this.scaffolds.contains(pos);
    }

    /**
     * Our own body overlaps {@code avoid}, so it can't be placed. Walk to the middle of the cell we're
     * on (or a free neighbour) from where it's still in reach.
     *
     * @return false if there's nowhere to go or we already tried a few times
     */
    private boolean stepAside(Minecraft client, BlockPos avoid) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (!player.getBoundingBox().intersects(new AABB(avoid))
                || this.stepAsides.merge(avoid, 1, Integer::sum) > MAX_STEP_ASIDE) {
            return false;
        }
        BlockPos here = Walkability.feetNode(world, player);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos c = here.offset(dx, 0, dz);
                // A player in the middle of c (0.6 wide, 1.8 tall) only covers c and the block above.
                if (c.equals(avoid) || c.above().equals(avoid) || !Walkability.standable(world, c)
                        || !canWorkFrom(client, c, avoid)) {
                    continue;
                }
                double d = player.position().distanceToSqr(Vec3.atBottomCenterOf(c));
                if (d < bestDist) {
                    bestDist = d;
                    best = c;
                }
            }
        }
        if (best == null) {
            return false;
        }
        this.walkTarget = avoid;
        this.walk = new PathFollower(List.of(best), null, true);
        this.status = "stepping aside";
        return true;
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
        if (this.walk != null && this.search == null && c.equals(this.walkTarget)
                && player.getBoundingBox().intersects(new AABB(c))) {
            // Still stepping out of the scaffold block's way.
            walkStep(client, c, " (scaffolding)");
            return;
        }
        if (!Reach.inReach(client, c)) {
            walkStep(client, c, " (scaffolding)");
            return;
        }
        this.walk = null;
        this.search = null;
        MovementInput.acquire(player).release();
        if (support(world, c) == null) {
            abandonScaffold();
            return;
        }
        if (player.getBoundingBox().intersects(new AABB(c))) {
            // Standing where the scaffold goes: move off it, then try again.
            if (!stepAside(client, c)) {
                abandonScaffold();
            }
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
        this.walkStucks.clear();
        this.stepAsides.clear();
        this.breakAttempts.clear();
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
        this.pillarBestY = this.pillarStep.getY();
        this.pillarStall = 0;
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
        // Blocks that don't stick (rejected by the server, nothing to place against) mean jumping on
        // the spot forever; give up when we haven't gained height for a while.
        int y = player.blockPosition().getY();
        if (y > this.pillarBestY) {
            this.pillarBestY = y;
            this.pillarStall = 0;
        }
        boolean stalled = ++this.pillarStall > PILLAR_STALL_TICKS;
        if (++this.pillarTicks > 20 * 40 || this.pillarPlaced > 30 || stalled
                || !Walkability.passable(world, player.blockPosition().above(2))) {
            // Ceiling in the way, the blocks don't stay, or it's taking far too long.
            input.release();
            this.pillarTarget = null;
            if (stalled) {
                warn("Can't climb up to " + target.toShortString() + ", doing other blocks first");
            }
            unreachable(target);
            return;
        }
        Item item = scaffoldItem(client);
        if (item == null || !Inventories.hold(client, item)) {
            input.release();
            this.pillarTarget = null;
            pauseForScaffolding();
            return;
        }
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
                    this.walkStucks.clear();
                    this.stepAsides.clear();
                    this.breakAttempts.clear();
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
        return com.testrts.rtsclient.task.MaterialPlanner.matches(world.getBlockState(pos), wanted);
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
        // Eye height over the real floor (lower on slabs and carpets).
        Vec3 eye = new Vec3(feet.getX() + 0.5, Walkability.standY(client.level, feet) + 1.62, feet.getZ() + 0.5);
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
                if (tooManyBreaks(p)) {
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
                if (this.placedHere.contains(p)) {
                    // We put a block here and it turned into something else by itself (grass to dirt,
                    // a torch that became a wall torch...). Leave it rather than mine and re-place forever.
                    this.todo.remove(p);
                    continue;
                }
                // Something else is in the way: mine it first.
                if (current.getDestroySpeed(world, p) < 0) {
                    impossible(p);
                    continue;
                }
                if (tooManyBreaks(p)) {
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
                this.placedHere.add(p.immutable());
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
        return hasSupport(world, pos) || com.testrts.rtsclient.config.RtsConfig.get().airPlace;
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
        BlockHitResult hit;
        Vec3 hitVec;
        float[] rotation;
        if (toSupport != null) {
            BlockPos supportPos = pos.relative(toSupport);
            Direction face = toSupport.getOpposite();
            hitVec = Vec3.atCenterOf(pos).add(toSupport.getStepX() * 0.5, toSupport.getStepY() * 0.5, toSupport.getStepZ() * 0.5);
            hit = new BlockHitResult(hitVec, face, supportPos, false);
            // Vanilla decides the placed state from where the player looks. Try the four directions
            // (and up/down) until the block would come out the way the order wants it.
            rotation = rotationFor(player, wanted, hit);
        } else if (com.testrts.rtsclient.config.RtsConfig.get().airPlace) {
            // Nothing to attach to: click the empty cell itself. The server places a block in a
            // replaceable cell that is clicked directly, so no neighbour (or scaffolding) is needed.
            // Try each face of the cell, since the clicked face decides slab/stair halves.
            hit = null;
            hitVec = null;
            rotation = null;
            Vec3 eye = player.getEyePosition();
            Direction toward = Direction.getNearest(eye.x - (pos.getX() + 0.5), eye.y - (pos.getY() + 0.5), eye.z - (pos.getZ() + 0.5));
            Direction[] faces = {toward, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
            for (Direction f : faces) {
                Vec3 v = Vec3.atCenterOf(pos).add(f.getStepX() * 0.5, f.getStepY() * 0.5, f.getStepZ() * 0.5);
                BlockHitResult h = new BlockHitResult(v, f, pos, false);
                float[] r = rotationFor(player, wanted, h);
                if (hit == null || r != null) {
                    hit = h;
                    hitVec = v;
                    rotation = r;
                }
                if (r != null) {
                    break;
                }
            }
        } else {
            return false;
        }
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

    /** Count a mining attempt; a block that keeps coming back is set aside for later. */
    private boolean tooManyBreaks(BlockPos p) {
        // Build orders give up on a spot quickly: something keeps putting a block back there.
        int cap = this.clearing ? MAX_BREAK_ATTEMPTS : 2;
        if (this.breakAttempts.merge(p, 1, Integer::sum) > cap) {
            this.breakAttempts.remove(p);
            fail(p);
            return true;
        }
        return false;
    }

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
