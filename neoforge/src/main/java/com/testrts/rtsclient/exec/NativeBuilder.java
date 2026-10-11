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
    /**
     * Passes over the scaffolding. Reaching high scaffolding can take a little new scaffolding, which
     * the next pass takes down, so a few passes may be needed.
     */
    private static final int MAX_CLEANUP_ROUNDS = 8;
    /** With mid-air placing: how many layers above the current one may be placed while in reach. */
    private static final int LOOKAHEAD_LAYERS = 2;
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
    /** Already paused once to fetch scaffolding for a block out of reach. */
    private boolean askedForScaffolding;
    // Creative flight: where we're flying to, and for which block.
    @Nullable
    private Vec3 flyTo;
    @Nullable
    private BlockPos flyFor;
    private int flyTicks;
    /** The route being flown (feet cells), the waypoint we're on, and progress for the watchdog. */
    @Nullable
    private List<BlockPos> flyPath;
    private int flyIndex;
    private double flyBest = Double.MAX_VALUE;
    private int flyReplans;
    /** End spots that didn't work out for the current block. */
    private final Set<BlockPos> flyRejected = new HashSet<>();
    /** Ticks spent turning toward the block being placed. */
    private int turnedTicks;
    /** The block we're turning toward to place. */
    @Nullable
    private BlockPos aimTarget;

    // Getting out when the finished build closed around us (a dome, a hollow sphere).
    /** 0 = no, 1 = opening a doorway, 2 = closing it again from outside. */
    private int escapeStage;
    private int escapes;
    /** The air we were shut in with; while closing the doorway we never stand in it. */
    @Nullable
    private Set<BlockPos> shutIn;
    /** Order blocks taken out as the doorway, with the states to put back. */
    private Map<BlockPos, BlockState> doorway = Map.of();
    @Nullable
    private int[] bounds;
    /** Mining of {@link #breaking} began (we turn toward it first). */
    private boolean breakStarted;

    @Nullable
    private PathFinder.Search search;
    @Nullable
    private PathFollower walk;
    @Nullable
    private BlockPos walkTarget;
    private String status = "starting";

    /** What routes may build: temporary blocks anywhere that isn't part of the order. */
    private final PathFinder.Building building = new PathFinder.Building() {
        @Override
        @SuppressWarnings("deprecation")
        public boolean canPlace(BlockPos pos) {
            ClientLevel world = Minecraft.getInstance().level;
            if (world == null || !world.hasChunkAt(pos) || NativeBuilder.this.originalOrder.containsKey(pos)) {
                return false;
            }
            BlockState s = world.getBlockState(pos);
            return (s.isAir() || s.canBeReplaced()) && !s.getFluidState().is(net.minecraft.tags.FluidTags.LAVA);
        }

        @Override
        public boolean canDig(BlockPos pos) {
            return NativeBuilder.this.scaffolds.contains(pos);
        }

        @Override
        public int budget() {
            Minecraft client = Minecraft.getInstance();
            Item item = scaffoldItem(client);
            if (item == null) {
                return 0;
            }
            return Creative.supplies(client) ? 64 : Math.min(64, Inventories.count(client, item));
        }
    };

    /** Places the scaffolding blocks of a route for the path follower. */
    private final PathFollower.Scaffolder scaffolder = new PathFollower.Scaffolder() {
        @Override
        public boolean holdScaffold() {
            Minecraft client = Minecraft.getInstance();
            Item item = scaffoldItem(client);
            return item != null && Inventories.hold(client, item);
        }

        @Override
        public Result place(BlockPos at) {
            Minecraft client = Minecraft.getInstance();
            if (!holdScaffold()) {
                return Result.FAILED;
            }
            Item item = client.player.getMainHandItem().getItem();
            return switch (NativeBuilder.this.place(client, at, Block.byItem(item).defaultBlockState())) {
                case DONE -> {
                    NativeBuilder.this.scaffolds.add(at.immutable());
                    yield Result.DONE;
                }
                case TURNING -> Result.BUSY;
                case FAILED -> Result.FAILED;
            };
        }

        @Override
        public void placed(BlockPos at) {
            NativeBuilder.this.scaffolds.add(at.immutable());
        }
    };

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
            if (startEscape(client)) {
                return true;
            }
            MovementInput.restore(client);
            int left = skippedCount();
            this.status = left == 0 ? "done" : "done, " + left + " skipped";
            if (!this.scaffolds.isEmpty() && com.testrts.rtsclient.config.RtsConfig.get().removeScaffolding) {
                com.testrts.rtsclient.util.Messages.warn(this.scaffolds.size() + " scaffolding block(s) couldn't be taken down");
            }
            return false;
        }
        String layerInfo = (this.cleaning ? " scaffolding" : "") + (this.layered && this.layerOrder.size() > 1
                ? " (layer " + (this.layerIndex + 1) + "/" + this.layerOrder.size() + ", " + this.todo.size() + " left)"
                : " (" + this.todo.size() + " left)");

        // 1) Work on something within reach (this may also plan scaffolding). Not halfway through
        //    a pillar jump or a bridge block, though: finish that step first.
        BlockPos feetNow = Walkability.feetNode(world, player);
        boolean shutInNow = this.escapeStage == 2 && (this.shutIn.contains(feetNow) || inDoorway(feetNow));
        if ((this.walk == null || !this.walk.buildingStep()) && !shutInNow && actInReach(client)) {
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
            if (canFly(player)) {
                flyStep(client, target, layerInfo);
            } else {
                walkStep(client, target, layerInfo);
            }
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
            this.search = PathFinder.start(world, Walkability.feetNode(world, player),
                    feet -> canWorkFrom(client, feet, target) && mayStandAt(world, feet, target),
                    Vec3.atCenterOf(target), hasScaffoldItem(client) ? this.building : null);
        }
        if (this.search != null) {
            this.status = (this.seekingPillarSite ? "finding a spot to climb" : "planning") + info;
            PathFinder.Search.State s = this.search.step(PathFinder.NODES_PER_TICK);
            if (s == PathFinder.Search.State.FOUND) {
                List<BlockPos> path = this.search.result();
                List<PathFinder.Action> actions = this.search.actions();
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
                this.walk = new PathFollower(path, this::mayBreakInWay, false).building(actions, this.scaffolder);
            } else if (s == PathFinder.Search.State.FAILED) {
                this.search = null;
                if (!this.seekingPillarSite && hasScaffoldItem(client)) {
                    // Can't walk into reach: find ground below/near it and pillar up.
                    this.seekingPillarSite = true;
                    this.search = PathFinder.start(world, Walkability.feetNode(world, player), feet -> pillarSite(world, feet, target),
                            Vec3.atCenterOf(target));
                } else if (!this.seekingPillarSite && !this.askedForScaffolding && !hasScaffoldItem(client)
                        && scaffoldInStorage()) {
                    // Out of reach without building a way up, and nothing to build it with: fetch some
                    // scaffolding (the build task gets it from storage) and try again.
                    this.askedForScaffolding = true;
                    pauseForScaffolding();
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
        Placed placed = place(client, c, Block.byItem(item).defaultBlockState());
        if (placed == Placed.DONE) {
            this.scaffolds.add(c.immutable());
            this.scaffoldPlan.pollFirst();
            this.cooldown = PLACE_DELAY_TICKS;
        } else if (placed == Placed.FAILED) {
            abandonScaffold();
        }
    }

    @Nullable
    private Item scaffoldItem(Minecraft client) {
        Item fallback = null;
        boolean creative = Creative.supplies(client);
        for (String id : com.testrts.rtsclient.config.RtsConfig.get().scaffoldBlocks) {
            net.minecraft.resources.ResourceLocation ident = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (ident == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(ident)) {
                continue;
            }
            Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ident);
            if (creative && !this.orderItems.contains(item)) {
                return item;
            }
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

    /** Some scaffolding block is in linked storage. */
    private static boolean scaffoldInStorage() {
        for (String id : com.testrts.rtsclient.config.RtsConfig.get().scaffoldBlocks) {
            net.minecraft.resources.ResourceLocation ident = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (ident != null && net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(ident)
                    && com.testrts.rtsclient.storage.StorageManager.get().total(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(ident)) > 0) {
                return true;
            }
        }
        return false;
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
                || this.cleanupRounds >= MAX_CLEANUP_ROUNDS) {
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

    /**
     * We placed a block here and it turned into another solid block by itself (a torch on a wall, a
     * door's other half...): that spot is done, or we'd keep coming back to it.
     */
    private boolean changedByItself(ClientLevel world, BlockPos p) {
        if (this.clearing || !this.placedHere.contains(p)) {
            return false;
        }
        BlockState now = world.getBlockState(p);
        return !now.isAir() && !now.canBeReplaced();
    }

    // ------------------------------------------------------------------ creative flight

    /** Fly to blocks instead of walking: the setting is on and the player may fly (creative...). */
    private static boolean canFly(LocalPlayer player) {
        return com.testrts.rtsclient.config.RtsConfig.get().creativeFlight && player.getAbilities().mayfly;
    }

    private static final int FLY_SEARCH_NODES = 6000;
    private static final int FLY_STALL_TICKS = 40;
    private static final int FLY_MAX_REPLANS = 3;

    /**
     * One step of flying to work on {@code target}: plan a route through free air to a spot within reach
     * of it (around walls, roofs and the build itself), follow it with braking so momentum doesn't
     * overshoot, stop as soon as the block is in reach, and plan again from here when no progress is made.
     */
    private void flyStep(Minecraft client, BlockPos target, String info) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        this.walk = null;
        this.search = null;
        MovementInput input = MovementInput.acquire(player);
        input.release();
        if (!player.getAbilities().flying) {
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
        }
        // Already close enough: just stop and let the next tick place or mine it.
        if (Reach.inReach(client, target) && !player.getBoundingBox().intersects(new AABB(target))) {
            brake(player, 0.2);
            this.flyTo = null;
            this.flyPath = null;
            return;
        }
        if (this.flyPath == null || !target.equals(this.flyFor)) {
            if (!target.equals(this.flyFor)) {
                this.flyReplans = 0;
                this.flyRejected.clear();
            }
            this.flyFor = target.immutable();
            if (!planFlight(world, player, target)) {
                this.flyPath = null;
                this.flyTo = null;
                warn("No way to fly to " + target.toShortString() + ", doing other blocks first");
                unreachable(target);
                return;
            }
        }
        Vec3 pos = player.position();
        // Watchdog: no progress toward the end of the route for 2 s means something blocks it.
        double toEnd = pos.distanceToSqr(this.flyTo);
        if (toEnd < this.flyBest - 0.09) {
            this.flyBest = toEnd;
            this.flyTicks = 0;
        } else if (++this.flyTicks > FLY_STALL_TICKS) {
            this.flyRejected.add(BlockPos.containing(this.flyTo));
            this.flyPath = null;
            if (++this.flyReplans > FLY_MAX_REPLANS) {
                this.flyTo = null;
                warn("Stuck flying to " + target.toShortString() + ", doing other blocks first");
                unreachable(target);
            }
            return;
        }
        // Aim at the furthest waypoint we can fly to in a straight line.
        int aim = this.flyIndex;
        for (int k = Math.min(this.flyPath.size() - 1, this.flyIndex + 8); k > this.flyIndex; k--) {
            if (clearFlight(world, pos, waypoint(this.flyPath.get(k)))) {
                aim = k;
                break;
            }
        }
        this.flyIndex = aim;
        Vec3 goal = waypoint(this.flyPath.get(aim));
        boolean last = aim == this.flyPath.size() - 1;
        double dx = goal.x - pos.x;
        double dy = goal.y - pos.y;
        double dz = goal.z - pos.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        if (flat < 0.35 && Math.abs(dy) < 0.4) {
            if (last) {
                brake(player, 0.0);
                this.flyPath = null; // arrived; the next tick works on what's in reach
                return;
            }
            this.flyIndex = Math.min(this.flyPath.size() - 1, aim + 1);
            return;
        }
        // Creative flight keeps its speed: slow down near the end so we don't overshoot and circle.
        double dist = Math.sqrt(flat * flat + dy * dy);
        if (last && dist < 1.5) {
            brake(player, 0.55);
        }
        if (flat > 1.0) {
            float yaw = (float) (net.minecraft.util.Mth.atan2(dz, dx) * net.minecraft.util.Mth.RAD_TO_DEG) - 90.0F;
            Look.toward(player, yaw, player.getXRot());
        }
        // Move toward the waypoint whichever way we face (strafing while the head turns).
        float rad = player.getYRot() * net.minecraft.util.Mth.DEG_TO_RAD;
        double forward = -dx * net.minecraft.util.Mth.sin(rad) + dz * net.minecraft.util.Mth.cos(rad);
        double left = dx * net.minecraft.util.Mth.cos(rad) + dz * net.minecraft.util.Mth.sin(rad);
        double dead = last && dist < 1.2 ? 0.25 : 0.12;
        input.forward = forward > dead;
        input.back = forward < -dead;
        input.left = left > dead;
        input.right = left < -dead;
        input.jump = dy > 0.2;
        input.sneak = dy < -0.2;
        this.status = "flying" + info;
    }

    /** Scale the player's speed down (0 = stop dead). Our own movement, so the client decides it. */
    private static void brake(LocalPlayer player, double keep) {
        Vec3 v = player.getDeltaMovement();
        player.setDeltaMovement(v.x * keep, v.y * keep, v.z * keep);
    }

    private static Vec3 waypoint(BlockPos feet) {
        return new Vec3(feet.getX() + 0.5, feet.getY() + 0.05, feet.getZ() + 0.5);
    }

    /** Whether the player's body fits all along the straight line from {@code from} to {@code to}. */
    private boolean clearFlight(ClientLevel world, Vec3 from, Vec3 to) {
        double len = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(len / 0.3));
        for (int i = 1; i <= steps; i++) {
            Vec3 p = from.lerp(to, (double) i / steps);
            for (double ox : new double[]{-0.3, 0.3}) {
                for (double oz : new double[]{-0.3, 0.3}) {
                    BlockPos feet = BlockPos.containing(p.x + ox, p.y + 0.1, p.z + oz);
                    if (!freeToHover(world, feet) || !freeToHover(world, feet.above())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * Breadth-first search through free air (2 blocks of headroom) from the player to the nearest spot
     * within reach of {@code target}. Sets {@link #flyPath} and {@link #flyTo}.
     */
    private boolean planFlight(ClientLevel world, LocalPlayer player, BlockPos target) {
        double reach = Reach.reach(Minecraft.getInstance()) - 0.6;
        BlockPos start = BlockPos.containing(player.getX(), player.getY() + 0.2, player.getZ());
        if (!freeToHover(world, start) || !freeToHover(world, start.above())) {
            start = start.above();
        }
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new java.util.HashMap<>();
        queue.add(start);
        parent.put(start, start);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0},
                {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}};
        Vec3 aim = Vec3.atCenterOf(target);
        BlockPos found = null;
        while (!queue.isEmpty() && parent.size() < FLY_SEARCH_NODES) {
            BlockPos c = queue.poll();
            Vec3 eye = new Vec3(c.getX() + 0.5, c.getY() + player.getEyeHeight(), c.getZ() + 0.5);
            boolean inside = c.equals(target) || c.above().equals(target);
            if (!inside && eye.distanceToSqr(aim) <= reach * reach && !this.flyRejected.contains(c)) {
                found = c;
                break;
            }
            for (int[] d : dirs) {
                BlockPos n = c.offset(d[0], d[1], d[2]);
                if (parent.containsKey(n) || !world.hasChunkAt(n)
                        || !freeToHover(world, n) || !freeToHover(world, n.above())) {
                    continue;
                }
                // Diagonals only when both side cells are open too, so we don't clip a corner.
                if (d[0] != 0 && d[2] != 0) {
                    BlockPos a = c.offset(d[0], 0, 0);
                    BlockPos b = c.offset(0, 0, d[2]);
                    if (!freeToHover(world, a) || !freeToHover(world, a.above()) || !freeToHover(world, b) || !freeToHover(world, b.above())) {
                        continue;
                    }
                }
                parent.put(n, c);
                queue.add(n);
            }
        }
        if (found == null) {
            return false;
        }
        List<BlockPos> path = new ArrayList<>();
        for (BlockPos c = found; ; c = parent.get(c)) {
            path.add(0, c);
            if (c.equals(start)) {
                break;
            }
        }
        this.flyPath = path;
        this.flyIndex = 0;
        this.flyTo = waypoint(found);
        this.flyBest = Double.MAX_VALUE;
        this.flyTicks = 0;
        return true;
    }

    private boolean freeToHover(ClientLevel world, BlockPos cell) {
        if (!Walkability.passable(world, cell)) {
            return false;
        }
        BlockState wanted = this.order.get(cell);
        return wanted == null || wanted.isAir() || done(world, cell, wanted);
    }

    // ------------------------------------------------------------------ getting out

    /**
     * The finished build closed around us (a dome, a hollow sphere): open a two-block doorway at the
     * lowest point, walk out, and close it again from outside. Runs after the scaffolding is gone.
     *
     * @return true while there is escape work to do
     */
    private boolean startEscape(Minecraft client) {
        if (this.escapeStage == 1) {
            // The doorway is open: put it back, working from anywhere but inside.
            this.escapeStage = 2;
            beginPass(new LinkedHashMap<>(this.doorway), false);
            return true;
        }
        this.escapeStage = 0;
        this.shutIn = null;
        if (this.escapes >= 2 || this.originalOrder.isEmpty() || this.originalOrder.values().iterator().next().isAir()) {
            return false;
        }
        ClientLevel world = client.level;
        LocalPlayer player = client.player;
        Set<BlockPos> inside = enclosedSpace(world, Walkability.feetNode(world, player));
        if (inside == null) {
            return false;
        }
        this.escapes++;
        Map<BlockPos, BlockState> door = pickDoorway(world, player, inside);
        if (door.isEmpty()) {
            warn("The build closed around you and there's no wall to open - mine your way out");
            return false;
        }
        this.shutIn = inside;
        this.doorway = door;
        this.escapeStage = 1;
        Map<BlockPos, BlockState> open = new LinkedHashMap<>();
        BlockState air = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
        for (BlockPos d : door.keySet()) {
            open.put(d, air);
        }
        beginPass(open, true);
        com.testrts.rtsclient.util.Messages.info("The build closed around you - opening a doorway to get out (it's closed again from outside)");
        return true;
    }

    /** Work on a new set of blocks with fresh failure counts. */
    private void beginPass(Map<BlockPos, BlockState> blocks, boolean clear) {
        this.order = blocks;
        this.clearing = clear;
        this.cleaning = false;
        this.skipped.clear();
        this.failures.clear();
        this.walkStucks.clear();
        this.stepAsides.clear();
        this.breakAttempts.clear();
        this.placeAttempts.clear();
        this.sweeps = 0;
        this.walk = null;
        this.search = null;
        buildLayers();
    }

    /**
     * The air around our feet, if it's closed off: flood through passable cells. Getting outside the
     * order's bounds (or very far) means we're not shut in.
     */
    @Nullable
    private Set<BlockPos> enclosedSpace(ClientLevel world, BlockPos feet) {
        int[] b = orderBounds();
        if (!within(b, feet) || !Walkability.passable(world, feet)) {
            return null;
        }
        Set<BlockPos> seen = new HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        seen.add(feet.immutable());
        queue.add(feet.immutable());
        while (!queue.isEmpty()) {
            BlockPos c = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (seen.contains(n) || !Walkability.passable(world, n)) {
                    continue;
                }
                if (!within(b, n) || seen.size() > 50_000) {
                    return null;
                }
                seen.add(n);
                queue.add(n);
            }
        }
        return seen;
    }

    /** Thickest wall a doorway is dug through. */
    private static final int MAX_DOOR_DEPTH = 3;

    /**
     * A doorway two blocks high, straight out through the wall (up to {@link #MAX_DOOR_DEPTH} thick)
     * from standable inside air to open air. Lowest first, then shallowest, so we walk out at ground
     * level taking out as little as possible.
     */
    private Map<BlockPos, BlockState> pickDoorway(ClientLevel world, LocalPlayer player, Set<BlockPos> inside) {
        List<BlockPos> best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : inside) {
            if (!Walkability.standable(world, c)) {
                continue;
            }
            for (Direction d : Direction.Plane.HORIZONTAL) {
                List<BlockPos> cells = new ArrayList<>();
                BlockPos out = null;
                for (int k = 1; k <= MAX_DOOR_DEPTH + 1; k++) {
                    BlockPos w = c.relative(d, k);
                    BlockPos head = w.above();
                    if (k > 1 && !inside.contains(w) && Walkability.passable(world, w) && Walkability.passable(world, head)) {
                        out = w;
                        break;
                    }
                    if (k > MAX_DOOR_DEPTH || !doorCell(world, w, true) || !doorCell(world, head, false)) {
                        break;
                    }
                    if (!world.getBlockState(w).isAir()) {
                        cells.add(w.immutable());
                    }
                    if (!world.getBlockState(head).isAir() && this.originalOrder.containsKey(head)) {
                        cells.add(head.immutable());
                    }
                }
                if (out == null || cells.isEmpty()) {
                    continue;
                }
                // Going out through a door of the build is best: it's only taken out and put back.
                boolean viaDoor = world.getBlockState(cells.get(0)).getBlock() instanceof DoorBlock;
                double score = c.getY() * 100.0 + cells.size() * 5.0 + (Walkability.standable(world, out) ? 0.0 : 50.0)
                        - (viaDoor ? 150.0 : 0.0)
                        + c.distSqr(player.blockPosition()) * 0.01;
                if (score < bestScore) {
                    bestScore = score;
                    best = cells;
                }
            }
        }
        Map<BlockPos, BlockState> door = new LinkedHashMap<>();
        if (best != null) {
            for (BlockPos q : best) {
                door.put(q, this.originalOrder.get(q));
            }
        }
        return door;
    }

    /**
     * A cell a doorway may go through: open already, or a block of the order (never terrain or anything
     * else that isn't ours to take out). The feet cell must be part of the wall or open.
     */
    private boolean doorCell(ClientLevel world, BlockPos cell, boolean feet) {
        if (Walkability.passable(world, cell)) {
            return true;
        }
        return this.originalOrder.containsKey(cell) && !world.getBlockState(cell).isAir();
    }

    /** Our feet or head would be in a doorway cell. */
    private boolean inDoorway(BlockPos feet) {
        return this.doorway.containsKey(feet) || this.doorway.containsKey(feet.above());
    }

    /** minX, minY, minZ, maxX, maxY, maxZ of the order. */
    private int[] orderBounds() {
        if (this.bounds == null) {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (BlockPos q : this.originalOrder.keySet()) {
                b[0] = Math.min(b[0], q.getX());
                b[1] = Math.min(b[1], q.getY());
                b[2] = Math.min(b[2], q.getZ());
                b[3] = Math.max(b[3], q.getX());
                b[4] = Math.max(b[4], q.getY());
                b[5] = Math.max(b[5], q.getZ());
            }
            this.bounds = b;
        }
        return this.bounds;
    }

    private static boolean within(int[] b, BlockPos q) {
        return q.getX() >= b[0] && q.getX() <= b[3] && q.getY() >= b[1] && q.getY() <= b[4] && q.getZ() >= b[2] && q.getZ() <= b[5];
    }

    /** {@code pos} is one of the (up to four) blocks right under the player's feet. */
    private static boolean holdsUp(LocalPlayer player, BlockPos pos) {
        var box = player.getBoundingBox();
        int y = (int) Math.floor(box.minY - 0.01);
        return pos.getY() == y
                && pos.getX() >= (int) Math.floor(box.minX) && pos.getX() <= (int) Math.floor(box.maxX - 1.0E-6)
                && pos.getZ() >= (int) Math.floor(box.minZ) && pos.getZ() <= (int) Math.floor(box.maxZ - 1.0E-6);
    }

    /** Empty cells straight below {@code pos} before something to land on (up to 32). */
    private static int dropUnder(ClientLevel world, BlockPos pos) {
        int n = 0;
        BlockPos c = pos.below();
        while (n < 32 && Walkability.passable(world, c)) {
            n++;
            c = c.below();
        }
        return n;
    }

    /**
     * Where a route to {@code target} may end. Not inside while closing a doorway (that's how we got
     * shut in), and not on top of a block we're about to mine over a long drop.
     */
    private boolean mayStandAt(ClientLevel world, BlockPos feet, BlockPos target) {
        if (this.escapeStage == 2 && (this.shutIn.contains(feet) || inDoorway(feet))) {
            return false;
        }
        return !(this.clearing && feet.below().equals(target) && dropUnder(world, target) >= 3);
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
            // Look down at our feet first (turning, not snapping), then jump.
            if (!Look.toward(player, player.getYRot(), 90.0F)) {
                input.jump = false;
                return;
            }
            this.pillarStep = player.blockPosition();
            input.jump = true;
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
                    if (!this.skipped.contains(p) && !this.impossible.contains(p) && !done(world, p, this.order.get(p))
                            && !changedByItself(world, p)) {
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
        if (this.breaking != null && this.breakStarted && client.gameMode != null) {
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
        // With mid-air placing nothing needs a neighbour, so blocks of the next layers that are already
        // in reach go too, instead of walking the whole ring once per layer.
        if (!this.clearing && this.layered && com.testrts.rtsclient.config.RtsConfig.get().airPlace) {
            for (int k = 1; k <= LOOKAHEAD_LAYERS && this.layerIndex + k < this.layerOrder.size(); k++) {
                for (BlockPos p : this.layers.get(this.layerOrder.get(this.layerIndex + k))) {
                    if (!this.skipped.contains(p) && !this.impossible.contains(p)
                            && Vec3.atCenterOf(p).distanceToSqr(eye) <= r2 && !done(world, p, this.order.get(p))) {
                        inReach.add(p);
                    }
                }
            }
        }
        if (inReach.isEmpty()) {
            return false;
        }
        // Nearest first, but rather what's close to where we already look: less turning between blocks.
        Vec3 look = player.getLookAngle();
        inReach.sort(Comparator.comparingDouble(p -> {
            Vec3 to = Vec3.atCenterOf(p).subtract(eye);
            double turn = Math.acos(Math.max(-1.0, Math.min(1.0, to.normalize().dot(look))));
            return to.lengthSqr() + turn * 6.0;
        }));
        // Stay with the block we're already turning toward: re-picking every tick could switch back and
        // forth between two blocks as the view moves and never finish turning to either.
        if (this.aimTarget != null && inReach.remove(this.aimTarget)) {
            inReach.add(0, this.aimTarget);
        }
        if (this.clearing) {
            BlockPos below = player.blockPosition().below();
            for (BlockPos p : inReach) {
                // Leave the block we're standing on for last.
                if (p.equals(below) && inReach.size() > 1) {
                    continue;
                }
                // ...and never pull out anything we stand on (on an edge that can be a neighbour of
                // that block) over a drop that hurts: it gets mined from somewhere else.
                if (holdsUp(player, p) && dropUnder(world, p) >= 3) {
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
            if (this.placeAttempts.getOrDefault(p, 0) >= MAX_PLACE_ATTEMPTS) {
                // The server keeps rejecting it (entity in the way, protection...): come back later.
                this.placeAttempts.remove(p);
                fail(p);
                continue;
            }
            Placed placed = place(client, p, wanted);
            if (placed == Placed.TURNING) {
                this.turnedTicks++;
                this.aimTarget = p.immutable();
                return true;
            }
            this.aimTarget = null;
            if (placed == Placed.DONE) {
                this.placeAttempts.merge(p, 1, Integer::sum);
                this.placedHere.add(p.immutable());
                // Time spent turning already spaced the clicks out.
                this.cooldown = Math.max(0, PLACE_DELAY_TICKS - this.turnedTicks);
                this.turnedTicks = 0;
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

    private enum Placed { DONE, TURNING, FAILED }

    /** Turn toward the spot and, once facing it, click to place {@code wanted} there. */
    private Placed place(Minecraft client, BlockPos pos, BlockState wanted) {
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
            return Placed.FAILED;
        }
        if (rotation == null) {
            if (this.strict && !wanted.getBlock().defaultBlockState().equals(wanted)) {
                impossible(pos);
                return Placed.FAILED;
            }
            rotation = Look.anglesTo(player, hitVec);
        }
        if (!Look.toward(player, rotation[0], rotation[1])) {
            return Placed.TURNING;
        }
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        player.swing(InteractionHand.MAIN_HAND);
        return Placed.DONE;
    }

    @Nullable
    private static float[] rotationFor(LocalPlayer player, BlockState wanted, BlockHitResult hit) {
        ItemStack stack = player.getMainHandItem();
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            // Simply looking at the spot is what a player would do; use it whenever it gives the
            // right state (always, for blocks without a facing). Only oriented blocks need the
            // fixed directions below.
            float[] natural = Look.anglesTo(player, hit.getLocation());
            player.setYRot(natural[0]);
            player.setXRot(natural[1]);
            if (wanted.equals(wanted.getBlock().getStateForPlacement(new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit)))) {
                return natural;
            }
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
        // Mining starts in continueBreaking once we've turned toward the block.
        this.breaking = pos.immutable();
        this.breakTicks = 0;
        this.breakStarted = false;
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
            if (this.breakStarted) {
                client.gameMode.stopDestroyBlock();
            }
            this.breaking = null;
            fail(pos);
            return;
        }
        Vec3 face = Vec3.atCenterOf(pos).add(this.breakingSide.getStepX() * 0.5, this.breakingSide.getStepY() * 0.5,
                this.breakingSide.getStepZ() * 0.5);
        if (!Look.at(client.player, face) && !this.breakStarted) {
            return; // still turning toward it
        }
        if (!this.breakStarted) {
            this.breakStarted = true;
            client.gameMode.startDestroyBlock(pos, this.breakingSide);
            client.player.swing(InteractionHand.MAIN_HAND);
        } else if (client.gameMode.continueDestroyBlock(pos, this.breakingSide)) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
    }
}
