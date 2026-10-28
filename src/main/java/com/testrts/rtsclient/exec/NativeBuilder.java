package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.task.Reach;
import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.block.AbstractRedstoneGateBlock;
import net.minecraft.block.AnvilBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CakeBlock;
import net.minecraft.block.ComposterBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.GrindstoneBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.LoomBlock;
import net.minecraft.block.NoteBlock;
import net.minecraft.block.StonecutterBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
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
    /** More sweeps after those, as long as each one leaves fewer blocks missing than the one before. */
    private static final int MAX_SWEEPS_WITH_PROGRESS = 6;
    /**
     * Passes over a layer's set-aside blocks before going up: with the rest of the layer down they often
     * have something to attach to or a way to them now, and later layers would wall them in (a floating
     * island's underside can't be reached once the wider layers above it are built).
     */
    private static final int MAX_LAYER_RETRIES = 1;
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
    /**
     * Going for one block without getting anything done (no block placed or mined, no scaffolding
     * put down) this long, or planning a route to it this many times, means we're going round in
     * circles: set it aside and do other blocks first.
     */
    private static final int MAX_CHASE_TICKS = 20 * 40;
    private static final int MAX_CHASE_PLANS = 12;
    /** Minimum time between "can't get there" messages, so a big unreachable area doesn't spam. */
    private static final long WARN_INTERVAL_MS = 5000L;
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    private static final float[] YAWS = {0F, 90F, 180F, 270F};
    private static final float[] PITCHES = {0F, 75F, -75F};
    /** Live tests: log each decision about getting to blocks, to trace where time goes. */
    private static final boolean TRACE = System.getProperty("rtsclient.autotest") != null;

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
    /** Climbing up a wall on ladders or vines we put there (else: jumping and placing blocks under us). */
    @Nullable
    private Direction climbWall;
    @Nullable
    private Item climbItem;
    /** Up a ladder or vine at the height we climbed to: hold on (sneak) while working from there. */
    private boolean holdingOn;
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
    /** The block we're making our way to, with the ticks spent and routes planned since anything got done. */
    @Nullable
    private BlockPos chasing;
    private int chaseTicks;
    private int chasePlans;
    private int sweeps;
    /** Blocks still missing when the last sweep began. */
    private int sweepMissing = Integer.MAX_VALUE;
    /** Passes over the current layer's set-aside blocks so far. */
    private int layerRetries;
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
    private Vec3d flyTo;
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
    /** The flight route is a landing (see {@link #landStep}), not one to a block. */
    private boolean flyLanding;
    /** The flight route goes up over a new layer (see {@link #riseAboveLayer}). */
    private boolean flyRising;
    /** The layer (Y) we've already got up over, or given up getting up over. */
    @Nullable
    private Integer risenFor;
    /** Extra height (under a block) of the route's last waypoint: the fraction of the Hover height. */
    private double flyLift;
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
    /** Partial routes walked toward the current walk target (see {@link PathFinder.Search#partial()}). */
    private int partialLegs;
    private static final int MAX_PARTIAL_LEGS = 8;
    @Nullable
    private PathFollower walk;
    @Nullable
    private BlockPos walkTarget;
    private String status = "starting";

    /** What routes may build: temporary blocks anywhere that isn't part of the order. */
    private final PathFinder.Building building = new PathFinder.Building() {
        @Override
        public boolean canPlace(BlockPos pos) {
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world == null || !world.isChunkLoaded(pos) || NativeBuilder.this.originalOrder.containsKey(pos)) {
                return false;
            }
            BlockState s = world.getBlockState(pos);
            return (s.isAir() || s.isReplaceable()) && !s.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA);
        }

        @Override
        public boolean canDig(BlockPos pos) {
            return NativeBuilder.this.scaffolds.contains(pos);
        }

        /**
         * Plain terrain we can dig through and put back: not part of the order (a finished block we'd
         * have to redo), not protected, nothing with contents (chests...), nothing slow to mine, and not
         * next to water or lava (it would pour into the tunnel).
         */
        @Override
        public boolean canTunnel(BlockPos pos) {
            ClientWorld world = MinecraftClient.getInstance().world;
            if (world == null || !world.isChunkLoaded(pos) || NativeBuilder.this.originalOrder.containsKey(pos)
                    || NativeBuilder.this.refilling || com.testrts.rtsclient.build.Protection.get().isProtected(pos)) {
                return false;
            }
            BlockState s = world.getBlockState(pos);
            float hardness = s.getHardness(world, pos);
            if (hardness < 0 || hardness > 3.0F || s.hasBlockEntity() || !s.getFluidState().isEmpty()) {
                return false;
            }
            for (BlockPos n : new BlockPos[]{pos.up(), pos.north(), pos.south(), pos.east(), pos.west()}) {
                if (!world.getBlockState(n).getFluidState().isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean canPillar() {
            // With scaffolding along, climbing goes up a scaffolding column instead (see tickScaffoldingColumn).
            MinecraftClient client0 = MinecraftClient.getInstance();
            return scaffoldingBlock(client0) == null && scaffoldItem(client0) != null;
        }

        @Override
        public int budget() {
            MinecraftClient client0 = MinecraftClient.getInstance();
            if (scaffoldingBlock(client0) != null) {
                // Walkways out from a scaffolding tower (bridge steps).
                return Math.min(64, Inventories.count(client0, net.minecraft.item.Items.SCAFFOLDING)
                        + (scaffoldItem(client0) == null ? 0 : Inventories.count(client0, scaffoldItem(client0))));
            }
            MinecraftClient client = MinecraftClient.getInstance();
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
            MinecraftClient client = MinecraftClient.getInstance();
            Item item = scaffoldItem(client);
            return item != null && Inventories.hold(client, item);
        }

        @Override
        public Result place(BlockPos at) {
            MinecraftClient client = MinecraftClient.getInstance();
            // Scaffolding when carried: a walkway piece sneak-clicked onto the side of the piece we stand on goes
            // straight out that way (as Minecraft does it), up to 6 from the column holding it up.
            Item scaffolding = scaffoldingBlock(client);
            boolean useScaffolding = scaffolding != null && Walkability.scaffoldDistance(client.world, at) < 7;
            if (useScaffolding ? !Inventories.hold(client, scaffolding) : !holdScaffold()) {
                return Result.FAILED;
            }
            Item item = client.player.getMainHandStack().getItem();
            return switch (NativeBuilder.this.place(client, at, Block.getBlockFromItem(item).getDefaultState())) {
                case DONE -> {
                    NativeBuilder.this.scaffolds.add(at.toImmutable());
                    NativeBuilder.this.progress();
                    yield Result.DONE;
                }
                case TURNING -> Result.BUSY;
                case FAILED -> Result.FAILED;
            };
        }

        @Override
        public void placed(BlockPos at) {
            NativeBuilder.this.scaffolds.add(at.toImmutable());
            NativeBuilder.this.progress();
        }

        @Override
        public void dug(BlockPos at, BlockState was) {
            // Scaffolding comes out anyway, and order blocks are the order's business.
            if (!NativeBuilder.this.scaffolds.contains(at) && !NativeBuilder.this.originalOrder.containsKey(at)) {
                NativeBuilder.this.tunnelled.putIfAbsent(at.toImmutable(), was);
            }
        }
    };

    /** Terrain mined to tunnel through, with what was there: put back once the order is done. */
    private final Map<BlockPos, BlockState> tunnelled = new LinkedHashMap<>();
    /** Putting tunnelled blocks back (no new tunnels then: they'd only need filling in again). */
    private boolean refilling;
    private int refillRounds;

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

    /** What the builder is doing right now, for the live tests' stall reports. */
    public String debugState(MinecraftClient client) {
        StringBuilder b = new StringBuilder();
        b.append("paused=").append(this.paused).append(" cooldown=").append(this.cooldown)
                .append(" breaking=").append(this.breaking).append(" pillar=").append(this.pillarTarget)
                .append(" scaffoldPlan=").append(this.scaffoldPlan.size()).append(" walk=").append(this.walk != null)
                .append(" search=").append(this.search != null).append(" walkTarget=").append(this.walkTarget)
                .append(" aim=").append(this.aimTarget).append(" flyFor=").append(this.flyFor)
                .append(" flyPath=").append(this.flyPath == null ? "-" : this.flyIndex + "/" + this.flyPath.size())
                .append(" flyTo=").append(this.flyTo).append(" landing=").append(this.flyLanding)
                .append(" escape=").append(this.escapeStage).append(" pos=").append(client.player.getPos())
                .append(" vel=").append(client.player.getVelocity()).append(" ground=").append(client.player.isOnGround())
                .append(" flyTicks=").append(this.flyTicks).append(" replans=").append(this.flyReplans)
                .append(" chase=").append(this.chaseTicks).append(" skipped=").append(this.skipped.size());
        if (client.player != null) {
            double r = Reach.reach(client);
            this.todo.stream().sorted(java.util.Comparator.comparingDouble(q -> Vec3d.ofCenter(q).squaredDistanceTo(client.player.getEyePos()))).limit(4)
                    .forEach(q -> b.append(" | ").append(q.toShortString())
                            .append(" d=").append(String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(Vec3d.ofCenter(q).squaredDistanceTo(client.player.getEyePos()))))
                            .append(" r=").append(String.format(java.util.Locale.ROOT, "%.2f", r))
                            .append(" inBody=").append(client.player.getBoundingBox().intersects(new Box(q)))
                            .append(" support=").append(hasSupport(client.world, q))
                            .append(" tries=").append(this.placeAttempts.getOrDefault(q, 0))
                            .append(" fails=").append(this.failures.getOrDefault(q, 0)));
        }
        return b.toString();
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
    public boolean tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) {
            return false;
        }
        checkPutBack(world);
        if (this.paused) {
            MovementInput.acquire(player).release();
            return true;
        }
        // On a ladder or vine and not walking a route: hold on (sneak), or we slide down while placing or mining.
        // Never in scaffolding, where sneaking drops us through it.
        boolean onLadder = player.isClimbing() && !world.getBlockState(player.getBlockPos()).isOf(net.minecraft.block.Blocks.SCAFFOLDING);
        if (onLadder && this.walk == null && this.search == null && this.pillarTarget == null) {
            this.holdingOn = true;
            MovementInput.acquire(player).sneak = true;
        } else if (this.holdingOn) {
            this.holdingOn = false;
            MovementInput.acquire(player).sneak = false;
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
            if (startRefill(client)) {
                return true;
            }
            if (startEscape(client)) {
                return true;
            }
            MovementInput.restore(client);
            trace("finished; skipped %s, impossible %s, scaffolding left %s",
                    this.skipped.stream().map(BlockPos::toShortString).toList(),
                    this.impossible.stream().map(BlockPos::toShortString).toList(),
                    this.scaffolds.stream().map(BlockPos::toShortString).toList());
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
        if (riseAboveLayer(client, layerInfo)) {
            return true;
        }
        BlockPos feetNow = Walkability.feetNode(world, player);
        boolean shutInNow = this.escapeStage == 2 && (this.shutIn.contains(feetNow) || inDoorway(feetNow));
        if ((this.walk == null || !this.walk.buildingStep()) && !shutInNow && actInReach(client)) {
            if (this.walk != null || this.search != null) {
                trace("  stop walking to %s: work in reach (%s)", this.walkTarget == null ? "-" : this.walkTarget.toShortString(),
                        this.breaking != null ? "mining" : this.aimTarget != null ? "turning to " + this.aimTarget.toShortString()
                                : this.paused ? "paused" : !this.scaffoldPlan.isEmpty() ? "scaffolding" : "placed");
            }
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
            if (!target.equals(this.chasing)) {
                this.chasing = target;
                this.chaseTicks = 0;
                this.chasePlans = 0;
            }
            if (++this.chaseTicks > MAX_CHASE_TICKS || this.chasePlans > MAX_CHASE_PLANS) {
                trace("  going round in circles for %s (%d ticks, %d routes)", target.toShortString(), this.chaseTicks, this.chasePlans);
                warn("Can't get to " + target.toShortString() + ", doing other blocks first");
                this.walk = null;
                this.search = null;
                this.chasing = null;
                MovementInput.acquire(player).release();
                unreachable(target);
                return true;
            }
            if (canFly(player)) {
                flyStep(client, target, layerInfo);
            } else if (player.getAbilities().flying && !player.isOnGround()) {
                landStep(client, target, layerInfo);
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
    private void walkStep(MinecraftClient client, BlockPos target, String info) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (!target.equals(this.walkTarget) || (this.walk == null && this.search == null)) {
            trace("target %s from %s%s", target.toShortString(), player.getBlockPos().toShortString(), this.cleaning ? " (scaffolding)" : "");
            this.chasePlans++;
            if (!target.equals(this.walkTarget)) {
                this.partialLegs = 0;
            }
            this.walkTarget = target;
            this.walk = null;
            this.seekingPillarSite = false;
            this.search = PathFinder.start(world, Walkability.feetNode(world, player),
                    feet -> canWorkFrom(client, feet, target) && mayStandAt(world, feet, target),
                    Vec3d.ofCenter(target), scaffoldingAllowed() ? this.building : null);
        }
        if (this.search != null) {
            this.status = (this.seekingPillarSite ? "finding a spot to climb" : "planning") + info;
            PathFinder.Search.State s = this.search.step(PathFinder.NODES_PER_TICK);
            if (s == PathFinder.Search.State.FOUND) {
                List<BlockPos> path = this.search.result();
                List<PathFinder.Action> actions = this.search.actions();
                trace("  route %s: %d steps%s", this.seekingPillarSite ? "to climb spot" : "found", path.size(),
                        actions.stream().anyMatch(a -> a != PathFinder.Action.WALK) ? " " + actions : "");
                this.search = null;
                if (path.isEmpty()) {
                    // Already standing at the best spot.
                    if (this.seekingPillarSite) {
                        this.seekingPillarSite = false;
                        startPillar(client, target);
                    } else if (!stepAside(client, target) && !centreOnNode(client, target)) {
                        // In position but still couldn't do it: try other blocks first.
                        unreachable(target);
                    }
                    return;
                }
                this.walk = new PathFollower(path, this::mayBreakInWay, false).building(actions, this.scaffolder);
            } else if (s == PathFinder.Search.State.FAILED) {
                List<BlockPos> partial = this.seekingPillarSite ? null : this.search.partial();
                List<PathFinder.Action> partialActions = this.search.partialActions();
                this.search = null;
                if (partial != null && ++this.partialLegs <= MAX_PARTIAL_LEGS) {
                    // Too far to plan in one go: walk to the closest spot found; the next plan starts from there.
                    trace("  partial route: %d steps", partial.size());
                    this.walk = new PathFollower(partial, this::mayBreakInWay, false).building(partialActions, this.scaffolder);
                    return;
                }
                trace("  no route%s", this.seekingPillarSite ? " to a climb spot" : "");
                boolean pillar = scaffoldingAllowed() && (hasScaffoldItem(client) || scaffoldingBlock(client) != null);
                boolean wallClimb = scaffoldingAllowed() && climbable(client) != null;
                if (!this.seekingPillarSite && (pillar || wallClimb)) {
                    // Can't walk into reach: find ground below/near it and climb up (with only ladders or vines:
                    // somewhere with a wall to put them on).
                    this.seekingPillarSite = true;
                    this.search = PathFinder.start(world, Walkability.feetNode(world, player),
                            feet -> pillarSite(world, feet, target) && (pillar || wallFor(world, feet, target) != null),
                            Vec3d.ofCenter(target));
                } else if (!this.seekingPillarSite && !this.askedForScaffolding && !pillar && !wallClimb
                        && scaffoldingAllowed() && scaffoldInStorage()) {
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
        trace("  walk %s at %s", s, player.getBlockPos().toShortString());
        if (s == PathFollower.State.ARRIVED && this.seekingPillarSite) {
            this.seekingPillarSite = false;
            startPillar(client, target);
            return;
        }
        if (s == PathFollower.State.STUCK) {
            trace("  stuck at %s (step %s -> %s, off path %s)", player.getBlockPos().toShortString(),
                    failed.failedFrom() == null ? "-" : failed.failedFrom().toShortString(),
                    failed.failedTo() == null ? "-" : failed.failedTo().toShortString(), failed.offPath());
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

    private static void trace(String fmt, Object... args) {
        if (TRACE) {
            com.testrts.rtsclient.RtsClientMod.LOGGER.info("[BUILD] " + String.format(java.util.Locale.ROOT, fmt, args));
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
    /** A block the order would break lies in a protected area: leave it, say so once in a while. */
    private void protectedSkip(BlockPos p) {
        impossible(p);
        warn("Not breaking " + p.toShortString() + ": it's in a protected area");
    }

    private boolean mayBreakInWay(BlockPos pos) {
        if (com.testrts.rtsclient.build.Protection.get().isProtected(pos)) {
            return false;
        }
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
    private boolean stepAside(MinecraftClient client, BlockPos avoid) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        // In the way of it, or (mining) standing on it over a drop, which we won't pull out from under us.
        boolean inTheWay = player.getBoundingBox().intersects(new Box(avoid))
                || this.clearing && holdsUp(player, avoid) && dropUnder(world, avoid) >= 3;
        if (!inTheWay || this.stepAsides.merge(avoid, 1, Integer::sum) > MAX_STEP_ASIDE) {
            return false;
        }
        BlockPos here = Walkability.feetNode(world, player);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos c = here.add(dx, 0, dz);
                // A player in the middle of c (0.6 wide, 1.8 tall) only covers c and the block above.
                if (c.equals(avoid) || c.up().equals(avoid) || c.down().equals(avoid) || !Walkability.standable(world, c)
                        || !canWorkFrom(client, c, avoid)) {
                    continue;
                }
                double d = player.getPos().squaredDistanceTo(Vec3d.ofBottomCenter(c));
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

    /** Something got done: whatever we're heading for, we're not going round in circles. */
    private void progress() {
        this.chaseTicks = 0;
        this.chasePlans = 0;
    }

    /**
     * Routes count from the middle of the cell we stand on, but we may be standing near its edge, a
     * little too far from a block at the edge of reach. Walk to the middle (once or twice) instead of
     * giving up on a block that is really in reach.
     *
     * @return false if it's already in reach from here (something else is wrong) or we tried already
     */
    private boolean centreOnNode(MinecraftClient client, BlockPos target) {
        ClientPlayerEntity player = client.player;
        BlockState there = client.world.getBlockState(target);
        boolean mining = !there.isAir() && !there.isReplaceable() && !(there.getBlock() instanceof FluidBlock);
        double reach = mining ? mineReach(client, target) : Reach.reach(client);
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) <= reach * reach
                || this.stepAsides.merge(target, 1, Integer::sum) > MAX_STEP_ASIDE) {
            return false;
        }
        BlockPos here = Walkability.feetNode(client.world, player);
        trace("  to the middle of %s, %s is just out of reach", here.toShortString(), target.toShortString());
        this.walkTarget = target;
        this.walk = new PathFollower(List.of(here), null, true);
        this.status = "moving closer";
        return true;
    }

    private void unreachable(BlockPos target) {
        trace("  give up on %s for now (failures %d)", target.toShortString(), this.failures.getOrDefault(target, 0) + 1);
        this.seekingPillarSite = false;
        if (this.scaffoldPlan.contains(target)) {
            abandonScaffold();
        } else {
            fail(target);
        }
    }

    // ------------------------------------------------------------------ scaffolding

    /** The Scaffolding setting: whether temporary blocks may be put up at all. */
    private static boolean scaffoldingAllowed() {
        return com.testrts.rtsclient.config.RtsConfig.get().useScaffolding;
    }

    private boolean canScaffold(ClientWorld world, ClientPlayerEntity player, BlockPos c) {
        if (!world.isChunkLoaded(c) || this.order.containsKey(c) || player.getBoundingBox().intersects(new Box(c))) {
            return false;
        }
        BlockState s = world.getBlockState(c);
        return (s.isAir() || s.isReplaceable()) && !s.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA);
    }

    /**
     * Find the fewest temporary blocks that connect {@code target} to something solid, so it can
     * be placed. Tries a short search first (bridges corner/edge gaps), then a column down.
     */
    private boolean planScaffold(ClientWorld world, ClientPlayerEntity player, BlockPos target) {
        Direction[] dirs = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new java.util.HashMap<>();
        Map<BlockPos, Integer> depth = new java.util.HashMap<>();
        for (Direction d : dirs) {
            BlockPos c = target.offset(d);
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
                BlockPos n = c.offset(d);
                if (!parent.containsKey(n) && canScaffold(world, player, n)) {
                    parent.put(n, c);
                    depth.put(n, dd + 1);
                    queue.add(n);
                }
            }
        }
        // Fallback: a column straight down to the ground.
        List<BlockPos> column = new ArrayList<>();
        BlockPos c = target.down();
        while (column.size() < 24 && canScaffold(world, player, c)) {
            column.add(c);
            if (support(world, c) != null && support(world, c) == Direction.DOWN) {
                break;
            }
            c = c.down();
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

    private void tickScaffold(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        BlockPos c = this.scaffoldPlan.peekFirst();
        BlockState now = world.getBlockState(c);
        if (!now.isAir() && !now.isReplaceable()) {
            this.scaffoldPlan.pollFirst();
            return;
        }
        if (this.walk != null && this.search == null && c.equals(this.walkTarget)
                && player.getBoundingBox().intersects(new Box(c))) {
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
        if (player.getBoundingBox().intersects(new Box(c))) {
            // Standing where the scaffold goes: move off it, then try again.
            if (!stepAside(client, c)) {
                abandonScaffold();
            }
            return;
        }
        // Scaffolding when carried and it would hold there (6 out from a column at most), else the usual blocks.
        Item scaffolding = this.cleaning ? null : scaffoldingBlock(client);
        Item item = scaffolding != null && Walkability.scaffoldDistance(client.world, c) < 7 ? scaffolding : scaffoldItem(client);
        if (item == null || !Inventories.hold(client, item)) {
            pauseForScaffolding();
            return;
        }
        this.status = "placing scaffolding";
        Placed placed = place(client, c, net.minecraft.block.Block.getBlockFromItem(item).getDefaultState());
        if (placed == Placed.DONE) {
            this.scaffolds.add(c.toImmutable());
            this.scaffoldPlan.pollFirst();
            progress();
            this.cooldown = PLACE_DELAY_TICKS;
        } else if (placed == Placed.FAILED) {
            abandonScaffold();
        }
    }

    @Nullable
    private Item scaffoldItem(MinecraftClient client) {
        Item fallback = null;
        boolean creative = Creative.supplies(client);
        for (String id : com.testrts.rtsclient.config.RtsConfig.get().scaffoldBlocks) {
            net.minecraft.util.Identifier ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident == null || !net.minecraft.registry.Registries.ITEM.containsId(ident)) {
                continue;
            }
            Item item = net.minecraft.registry.Registries.ITEM.get(ident);
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

    private boolean hasScaffoldItem(MinecraftClient client) {
        return scaffoldItem(client) != null;
    }

    /** Some scaffolding block is in linked storage. */
    private static boolean scaffoldInStorage() {
        for (String id : com.testrts.rtsclient.config.RtsConfig.get().scaffoldBlocks) {
            net.minecraft.util.Identifier ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident != null && net.minecraft.registry.Registries.ITEM.containsId(ident)
                    && (com.testrts.rtsclient.storage.StorageManager.get().total(net.minecraft.registry.Registries.ITEM.get(ident)) > 0
                    || com.testrts.rtsclient.storage.MeNetwork.anyMightHave(MinecraftClient.getInstance(), net.minecraft.registry.Registries.ITEM.get(ident)))) {
                return true;
            }
        }
        return false;
    }

    /** Out of scaffolding blocks: pause and ask for some (the build task fetches it from storage). */
    private void pauseForScaffolding() {
        Item want = net.minecraft.item.Items.DIRT;
        for (String id : com.testrts.rtsclient.config.RtsConfig.get().scaffoldBlocks) {
            net.minecraft.util.Identifier ident = net.minecraft.util.Identifier.tryParse(id);
            if (ident != null && net.minecraft.registry.Registries.ITEM.containsId(ident)) {
                Item item = net.minecraft.registry.Registries.ITEM.get(ident);
                if (com.testrts.rtsclient.storage.StorageManager.get().total(item) > 0) {
                    want = item;
                    break;
                }
            }
        }
        this.paused = true;
        this.missingItem = want;
        this.status = "need scaffolding blocks (" + want.getName().getString() + ")";
    }

    /**
     * Put back the terrain we tunnelled through. Mined stone drops cobblestone and grass drops dirt, so
     * a block we don't have goes back as what it dropped (or cobblestone, or dirt): a filled hole is
     * better than an order stuck waiting for a block that never comes.
     *
     * @return true while there is refilling to do
     */
    private boolean startRefill(MinecraftClient client) {
        this.refilling = false;
        if (this.tunnelled.isEmpty() || this.refillRounds >= 2) {
            return false;
        }
        ClientWorld world = client.world;
        boolean creative = Creative.supplies(client);
        Map<Item, Integer> have = new java.util.HashMap<>();
        Map<BlockPos, BlockState> refill = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockState> e : this.tunnelled.entrySet()) {
            BlockPos p = e.getKey();
            BlockState now = world.getBlockState(p);
            if (!(now.isAir() || now.isReplaceable()) || this.originalOrder.containsKey(p)) {
                continue; // filled already (sand fell in), or the order wants it
            }
            BlockState put = creative ? e.getValue() : affordable(client, e.getValue(), have);
            if (put != null) {
                refill.put(p, put);
            }
        }
        this.tunnelled.clear();
        if (refill.isEmpty()) {
            return false;
        }
        this.refillRounds++;
        trace("refill round %d: %d tunnelled blocks", this.refillRounds, refill.size());
        beginPass(refill, false);
        this.refilling = true;
        return true;
    }

    /** {@code was}, or the nearest thing to it we have (see {@link #startRefill}); null if nothing fits. */
    @Nullable
    private static BlockState affordable(MinecraftClient client, BlockState was, Map<Item, Integer> have) {
        net.minecraft.block.Block b = was.getBlock();
        net.minecraft.block.Block drop = b == net.minecraft.block.Blocks.STONE ? net.minecraft.block.Blocks.COBBLESTONE
                : b == net.minecraft.block.Blocks.DEEPSLATE ? net.minecraft.block.Blocks.COBBLED_DEEPSLATE
                : b == net.minecraft.block.Blocks.GRASS_BLOCK || b == net.minecraft.block.Blocks.PODZOL
                || b == net.minecraft.block.Blocks.MYCELIUM || b == net.minecraft.block.Blocks.DIRT_PATH
                || b == net.minecraft.block.Blocks.FARMLAND ? net.minecraft.block.Blocks.DIRT : b;
        for (net.minecraft.block.Block c : new net.minecraft.block.Block[]{b, drop, net.minecraft.block.Blocks.COBBLESTONE, net.minecraft.block.Blocks.DIRT}) {
            Item item = c.asItem();
            if (item == net.minecraft.item.Items.AIR) {
                continue;
            }
            int left = have.computeIfAbsent(item, i -> Inventories.count(client, i)
                    + com.testrts.rtsclient.storage.StorageManager.get().total(i));
            if (left > 0) {
                have.put(item, left - 1);
                return c == b ? was : c.getDefaultState();
            }
        }
        return null;
    }

    /** When everything is built, mine the scaffolding back out. */
    private boolean startCleanup(ClientWorld world) {
        // Scaffolding the last round set aside (couldn't reach it, stood on it) gets another go too.
        Set<BlockPos> candidates = new LinkedHashSet<>(this.scaffolds);
        if (this.cleaning) {
            candidates.addAll(this.order.keySet());
        }
        if (candidates.isEmpty() || !com.testrts.rtsclient.config.RtsConfig.get().removeScaffolding
                || this.cleanupRounds >= MAX_CLEANUP_ROUNDS) {
            return false;
        }
        Map<BlockPos, BlockState> cleanup = new LinkedHashMap<>();
        java.util.Set<BlockPos> keepScaffolding = new java.util.HashSet<>();
        BlockState air = net.minecraft.block.Blocks.AIR.getDefaultState();
        for (BlockPos p : candidates) {
            BlockState s = world.getBlockState(p);
            // Leave planned positions alone (a pillar block there was replaced by the real block),
            // and don't pull out a block that sand or gravel is resting on.
            if (s.isOf(net.minecraft.block.Blocks.SCAFFOLDING)) {
                BlockState under = world.getBlockState(p.down());
                if (under.isOf(net.minecraft.block.Blocks.SCAFFOLDING) || under.isAir() || under.isReplaceable()) {
                    // Comes down with the piece it hangs from; whatever is still up gets the next round.
                    keepScaffolding.add(p);
                    continue;
                }
            }
            if (!s.isAir() && !this.originalOrder.containsKey(p)
                    && !(world.getBlockState(p.up()).getBlock() instanceof net.minecraft.block.FallingBlock)) {
                cleanup.put(p, air);
            }
        }
        this.scaffolds.clear();
        if (cleanup.isEmpty() && !keepScaffolding.isEmpty()) {
            // Only pieces held up by something else are left: take them down one by one.
            keepScaffolding.forEach(p -> cleanup.put(p, air));
            keepScaffolding.clear();
        }
        this.scaffolds.addAll(keepScaffolding);
        if (cleanup.isEmpty()) {
            return false;
        }
        this.cleanupRounds++;
        trace("cleanup round %d: %d scaffolding blocks %s", this.cleanupRounds, cleanup.size(),
                cleanup.keySet().stream().map(BlockPos::toShortString).toList());
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
    private boolean changedByItself(ClientWorld world, BlockPos p) {
        if (this.clearing || !this.placedHere.contains(p)) {
            return false;
        }
        BlockState now = world.getBlockState(p);
        return !now.isAir() && !now.isReplaceable();
    }

    // ------------------------------------------------------------------ creative flight

    /** Fly to blocks instead of walking: the setting is on and the player may fly (creative or a flight item). */
    private static boolean canFly(ClientPlayerEntity player) {
        var cfg = com.testrts.rtsclient.config.RtsConfig.get();
        // Flight items (Angel Ring...) count as creative flight.
        return cfg.creativeFlight && player.getAbilities().allowFlying;
    }

    private static final int FLY_SEARCH_NODES = 20000;
    private static final int FLY_STALL_TICKS = 40;
    private static final int FLY_MAX_REPLANS = 3;
    /** Landing looks further: over the void the nearest floor can be a fair way off. */
    private static final int LAND_SEARCH_NODES = 40000;

    /**
     * One step of flying to work on {@code target}: plan a route through free air to a spot within reach
     * of it (around walls, roofs and the build itself), follow it with braking so momentum doesn't
     * overshoot, stop as soon as the block is in reach, and plan again from here when no progress is made.
     */
    private void flyStep(MinecraftClient client, BlockPos target, String info) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        this.walk = null;
        this.search = null;
        MovementInput input = MovementInput.acquire(player);
        input.release();
        // Blocks to mine need us closer than blocks to place (see withinMineReach): stopping at plain reach left
        // us hovering just too far to start mining, with nothing ever happening.
        boolean mine = mustMine(world, target);
        // Already close enough: just stop and let the next tick place or mine it.
        if ((mine ? withinMineReach(client, target) : Reach.inReach(client, target))
                && !player.getBoundingBox().intersects(new Box(target))) {
            brake(player, 0.2);
            this.flyTo = null;
            this.flyPath = null;
            return;
        }
        // Only now: on the ground the game switches flight straight back off, so turning it on while working
        // there would just send an abilities packet every tick.
        if (!player.getAbilities().flying) {
            player.getAbilities().flying = true;
            player.sendAbilitiesUpdate();
        }
        if (this.flyPath == null || this.flyLanding || !target.equals(this.flyFor)) {
            if (this.flyLanding || !target.equals(this.flyFor)) {
                this.flyReplans = 0;
                this.flyRejected.clear();
            }
            this.flyLanding = false;
            this.flyRising = false;
            this.flyFor = target.toImmutable();
            java.util.function.Predicate<BlockPos> goal = reachGoal(player, target, flyReach(client, target, mine));
            java.util.function.ToDoubleFunction<BlockPos> guess = toward(target, flyReach(client, target, mine));
            // Building in layers: hover above the layer (empty: the layers above come later), out of the way of its
            // blocks; with Multi-place, placing as we fly over (dome10: 213 s -> 181 s). Down in the layer we kept
            // catching on the blocks we'd placed (islands got stuck for minutes). Nowhere up there: anywhere.
            var flyCfg = com.testrts.rtsclient.config.RtsConfig.get();
            boolean above = this.layered && flyCfg.flyAboveLayer;
            // Feet Hover height over the tops of the layer's blocks: that many whole cells up, the rest as a lift of the
            // last waypoint (with the head room for it checked). Hovering right on them, we kept catching on them.
            double lift = hoverLift();
            int feetY = hoverFeetY(target.getY());
            java.util.function.Predicate<BlockPos> aboveGoal = reachGoal(player, target, flyReach(client, target, mine), lift)
                    .and(c -> c.getY() == feetY && (lift <= 0 || freeToHover(world, c.up(2))));
            this.flyLift = 0;
            if (above && planFlight(world, player, aboveGoal, FLY_SEARCH_NODES, guess)) {
                this.flyLift = lift;
                this.flyTo = this.flyTo.add(0, lift, 0);
            } else if (!planFlight(world, player, goal, FLY_SEARCH_NODES, guess)) {
                this.flyPath = null;
                this.flyTo = null;
                warn("No way to fly to " + target.toShortString() + ", doing other blocks first");
                unreachable(target);
                return;
            }
        }
        switch (followFlight(world, player, input)) {
            case STALLED -> {
                if (++this.flyReplans > FLY_MAX_REPLANS) {
                    this.flyTo = null;
                    warn("Stuck flying to " + target.toShortString() + ", doing other blocks first");
                    unreachable(target);
                }
            }
            case ARRIVED -> {
                // The next tick works on what's in reach.
            }
            default -> this.status = "flying" + info;
        }
    }

    /** Feet height (whole cells) of hovering over a layer at {@code layerY}: Hover height over its blocks' tops. */
    private static int hoverFeetY(int layerY) {
        return layerY + 1 + (int) Math.floor(Math.max(0.0, com.testrts.rtsclient.config.RtsConfig.get().hoverGap));
    }

    /** The fraction of Hover height left over after {@link #hoverFeetY}'s whole cells. */
    private static double hoverLift() {
        double gap = Math.max(0.0, com.testrts.rtsclient.config.RtsConfig.get().hoverGap);
        return gap - Math.floor(gap);
    }

    /**
     * Flying and building in layers: before starting a layer, get up over it (to Hover height) first. Starting it from
     * the last layer's height, our body was down in the new layer and the blocks going down around us boxed us in
     * (stuck between layers). @return true while still on the way up
     */
    private boolean riseAboveLayer(MinecraftClient client, String info) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (!this.layered || this.clearing || this.layerIndex >= this.layerOrder.size() || !canFly(player)
                || !com.testrts.rtsclient.config.RtsConfig.get().flyAboveLayer
                || this.walk != null && this.walk.buildingStep()) {
            return false;
        }
        int layerY = this.layerOrder.get(this.layerIndex);
        if (Integer.valueOf(layerY).equals(this.risenFor)) {
            return false;
        }
        int feetY = hoverFeetY(layerY);
        double lift = hoverLift();
        if (player.getY() >= feetY - 0.05) {
            this.risenFor = layerY;
            return false;
        }
        this.walk = null;
        this.search = null;
        MovementInput input = MovementInput.acquire(player);
        input.release();
        if (!player.getAbilities().flying) {
            player.getAbilities().flying = true;
            player.sendAbilitiesUpdate();
        }
        if (this.flyPath == null || !this.flyRising) {
            if (!this.flyRising) {
                this.flyReplans = 0;
                this.flyRejected.clear();
            }
            this.flyRising = true;
            this.flyLanding = false;
            this.flyFor = null;
            // Nearest spot at that height, straight up when the way is clear.
            if (!planFlight(world, player, c -> c.getY() == feetY && !this.flyRejected.contains(c)
                            && (lift <= 0 || freeToHover(world, c.up(2))), FLY_SEARCH_NODES,
                    c -> Math.abs(feetY - c.getY()))) {
                trace("  no way up over layer %d from %s", layerY, player.getBlockPos().toShortString());
                this.flyRising = false;
                this.risenFor = layerY;
                return false;
            }
            this.flyLift = lift;
            this.flyTo = this.flyTo.add(0, lift, 0);
            trace("  rising over layer %d to feet y %d", layerY, feetY);
        }
        switch (followFlight(world, player, input)) {
            case ARRIVED -> {
                this.flyRising = false;
                this.risenFor = layerY;
            }
            case STALLED -> {
                if (++this.flyReplans > FLY_MAX_REPLANS) {
                    this.flyRising = false;
                    this.flyTo = null;
                    this.risenFor = layerY;
                }
            }
            default -> this.status = "flying up" + info;
        }
        return true;
    }

    /** {@link #followFlight}: on the way, at the end of the route, or no progress for a while (route dropped). */
    private enum Flight { FLYING, ARRIVED, STALLED }

    /** One tick along {@link #flyPath}, braking so momentum doesn't overshoot. */
    private Flight followFlight(ClientWorld world, ClientPlayerEntity player, MovementInput input) {
        Vec3d pos = player.getPos();
        // Watchdog: no progress toward the end of the route for 2 s means something blocks it.
        double toEnd = pos.squaredDistanceTo(this.flyTo);
        if (toEnd < this.flyBest - 0.09) {
            this.flyBest = toEnd;
            this.flyTicks = 0;
        } else if (++this.flyTicks > FLY_STALL_TICKS) {
            this.flyRejected.add(BlockPos.ofFloored(this.flyTo));
            this.flyPath = null;
            return Flight.STALLED;
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
        Vec3d goal = waypoint(this.flyPath.get(aim));
        if (aim == this.flyPath.size() - 1 && this.flyLift > 0) {
            goal = goal.add(0, this.flyLift, 0);
        }
        boolean last = aim == this.flyPath.size() - 1;
        double dx = goal.x - pos.x;
        double dy = goal.y - pos.y;
        double dz = goal.z - pos.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        // On the way, a little above a waypoint counts (see the sneaking below). The end needs us nearly centred:
        // 0.3 off, our body (0.6 wide) still poked into the next cell, the block to place could be there, and we
        // "arrived" over and over without ever moving.
        if (flat < (last ? 0.18 : 0.35) && Math.abs(dy) < (last ? 0.4 : 0.9)) {
            if (last) {
                brake(player, 0.0);
                this.flyPath = null;
                return Flight.ARRIVED;
            }
            this.flyIndex = Math.min(this.flyPath.size() - 1, aim + 1);
            return Flight.FLYING;
        }
        // Creative flight keeps its speed: slow down near the end so we don't overshoot and circle.
        double dist = Math.sqrt(flat * flat + dy * dy);
        if (last && dist < 1.5) {
            brake(player, 0.55);
        }
        if (flat > 1.0) {
            float yaw = (float) (net.minecraft.util.math.MathHelper.atan2(dz, dx) * net.minecraft.util.math.MathHelper.DEGREES_PER_RADIAN) - 90.0F;
            Look.toward(player, yaw, player.getPitch());
        }
        // Move toward the waypoint whichever way we face (strafing while the head turns).
        float rad = player.getYaw() * net.minecraft.util.math.MathHelper.RADIANS_PER_DEGREE;
        double forward = -dx * net.minecraft.util.math.MathHelper.sin(rad) + dz * net.minecraft.util.math.MathHelper.cos(rad);
        double left = dx * net.minecraft.util.math.MathHelper.cos(rad) + dz * net.minecraft.util.math.MathHelper.sin(rad);
        double dead = last && dist < 1.2 ? 0.08 : 0.12;
        input.forward = forward > dead;
        input.back = forward < -dead;
        input.left = left > dead;
        input.right = left < -dead;
        // Take off when standing: the game ends flight on the ground, so we'd only walk (and stall at the
        // first step up).
        input.jump = dy > 0.2 || player.isOnGround() && (flat > 0.35 || dy > -0.4);
        // Hovering a little above the route is fine on the way; only the end needs the exact height. Sinking
        // to every waypoint near the floor would touch down, which ends the flight.
        input.sneak = !input.jump && dy < (last && dist < 1.5 ? -0.2 : -0.8);
        if (player.horizontalCollision && !last) {
            // Scraping along a block (the route clips a corner): go up over it instead of creeping along it.
            input.jump = true;
            input.sneak = false;
        }
        return Flight.FLYING;
    }

    /**
     * Flying, but not to work (Creative flight off): fly to the
     * nearest spot with a floor and touch down there, then walk. Just sinking where we are would never land
     * over the void or deep water.
     */
    private void landStep(MinecraftClient client, BlockPos target, String info) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        this.walk = null;
        this.search = null;
        MovementInput input = MovementInput.acquire(player);
        input.release();
        this.status = "landing" + info;
        if (this.flyPath == null || !this.flyLanding) {
            if (!this.flyLanding) {
                this.flyReplans = 0;
                this.flyRejected.clear();
            }
            this.flyLanding = true;
            this.flyRising = false;
            this.flyFor = null;
            this.flyLift = 0;
            if (!planFlight(world, player, c -> Walkability.standable(world, c) && !this.flyRejected.contains(c), LAND_SEARCH_NODES, c -> 0.0)) {
                this.flyLanding = false;
                warn("Nowhere to land nearby - fly lower, or turn on \"Creative flight\"");
                unreachable(target);
                return;
            }
        }
        switch (followFlight(world, player, input)) {
            // Touch down: the game ends the flight on the ground, and the next tick walks.
            case ARRIVED -> input.sneak = true;
            case STALLED -> {
                if (++this.flyReplans > FLY_MAX_REPLANS) {
                    this.flyLanding = false;
                    warn("Can't find a way down to land, doing other blocks first");
                    unreachable(target);
                }
            }
            default -> {
            }
        }
    }

    /** Scale the player's speed down (0 = stop dead). Our own movement, so the client decides it. */
    private static void brake(ClientPlayerEntity player, double keep) {
        Vec3d v = player.getVelocity();
        player.setVelocity(v.x * keep, v.y * keep, v.z * keep);
    }

    private static Vec3d waypoint(BlockPos feet) {
        return new Vec3d(feet.getX() + 0.5, feet.getY() + 0.05, feet.getZ() + 0.5);
    }

    /** Whether the player's body fits all along the straight line from {@code from} to {@code to}. */
    private boolean clearFlight(ClientWorld world, Vec3d from, Vec3d to) {
        double len = from.distanceTo(to);
        int steps = Math.max(1, (int) Math.ceil(len / 0.3));
        for (int i = 1; i <= steps; i++) {
            Vec3d p = from.lerp(to, (double) i / steps);
            for (double ox : new double[]{-0.3, 0.3}) {
                for (double oz : new double[]{-0.3, 0.3}) {
                    BlockPos feet = BlockPos.ofFloored(p.x + ox, p.y + 0.1, p.z + oz);
                    if (!freeToHover(world, feet) || !freeToHover(world, feet.up())) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * A* search through free air (2 blocks of headroom) from the player to the nearest spot that meets
     * {@code goal}; {@code heuristic} guesses how far a cell still is from one (0: plain nearest-first). Sets
     * {@link #flyPath} and {@link #flyTo}.
     */
    private boolean planFlight(ClientWorld world, ClientPlayerEntity player, java.util.function.Predicate<BlockPos> goal, int maxNodes,
                               java.util.function.ToDoubleFunction<BlockPos> heuristic) {
        BlockPos start = BlockPos.ofFloored(player.getX(), player.getY() + 0.2, player.getZ());
        if (!freeToHover(world, start) || !freeToHover(world, start.up())) {
            start = start.up();
        }
        // Cheapest first plus the distance still to go, so a spot far off (a build up in the sky) is found
        // without flooding all the air around us first (a plain breadth-first search ran out of nodes).
        Map<BlockPos, BlockPos> parent = new java.util.HashMap<>();
        Map<BlockPos, Double> cost = new java.util.HashMap<>();
        java.util.Set<BlockPos> closed = new java.util.HashSet<>();
        java.util.PriorityQueue<Object[]> queue = new java.util.PriorityQueue<>(java.util.Comparator.comparingDouble(e -> (Double) e[1]));
        queue.add(new Object[]{start, 0.0});
        parent.put(start, start);
        cost.put(start, 0.0);
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0},
                {1, 0, 1}, {1, 0, -1}, {-1, 0, 1}, {-1, 0, -1}};
        BlockPos found = null;
        while (!queue.isEmpty() && closed.size() < maxNodes) {
            BlockPos c = (BlockPos) queue.poll()[0];
            if (!closed.add(c)) {
                continue;
            }
            if (goal.test(c)) {
                found = c;
                break;
            }
            for (int[] d : dirs) {
                BlockPos n = c.add(d[0], d[1], d[2]);
                if (closed.contains(n) || !world.isChunkLoaded(n)
                        || !freeToHover(world, n) || !freeToHover(world, n.up())) {
                    continue;
                }
                // Diagonals only when both side cells are open too, so we don't clip a corner.
                if (d[0] != 0 && d[2] != 0) {
                    BlockPos a = c.add(d[0], 0, 0);
                    BlockPos b = c.add(0, 0, d[2]);
                    if (!freeToHover(world, a) || !freeToHover(world, a.up()) || !freeToHover(world, b) || !freeToHover(world, b.up())) {
                        continue;
                    }
                }
                double g = cost.get(c) + (d[0] != 0 && d[2] != 0 ? 1.414 : 1.0);
                if (g >= cost.getOrDefault(n, Double.MAX_VALUE)) {
                    continue;
                }
                cost.put(n, g);
                parent.put(n, c);
                queue.add(new Object[]{n, g + 1.2 * heuristic.applyAsDouble(n)});
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

    /** How close (eyes to the block's middle) flying gets to work on {@code target}. */
    private static double flyReach(MinecraftClient client, BlockPos target, boolean mine) {
        return mine ? mineReach(client, target) - 0.3 : Reach.reach(client) - 0.6;
    }

    /** A* guess for flying to work on {@code target}: straight-line distance to within {@code reach} of it. */
    private static java.util.function.ToDoubleFunction<BlockPos> toward(BlockPos target, double reach) {
        return c -> {
            double dx = c.getX() - target.getX();
            double dy = c.getY() + 1 - target.getY();
            double dz = c.getZ() - target.getZ();
            return Math.max(0.0, Math.sqrt(dx * dx + dy * dy + dz * dz) - reach);
        };
    }

    /** Flight goal: a spot (not inside {@code target}) from which its middle is within {@code reach} of the eyes. */
    private java.util.function.Predicate<BlockPos> reachGoal(ClientPlayerEntity player, BlockPos target, double reach) {
        return reachGoal(player, target, reach, 0.0);
    }

    /** Same, with the feet {@code lift} above the bottom of the cell. */
    private java.util.function.Predicate<BlockPos> reachGoal(ClientPlayerEntity player, BlockPos target, double reach, double lift) {
        Vec3d aim = Vec3d.ofCenter(target);
        return c -> {
            Vec3d eye = new Vec3d(c.getX() + 0.5, c.getY() + lift + player.getStandingEyeHeight(), c.getZ() + 0.5);
            boolean inside = c.equals(target) || c.up().equals(target);
            return !inside && eye.squaredDistanceTo(aim) <= reach * reach && !this.flyRejected.contains(c);
        };
    }

    /** Whether working on {@code target} means mining it (clearing, or something in the way of the block). */
    private static boolean mustMine(ClientWorld world, BlockPos target) {
        BlockState there = world.getBlockState(target);
        return !there.isAir() && !there.isReplaceable() && !(there.getBlock() instanceof net.minecraft.block.FluidBlock);
    }

    private boolean freeToHover(ClientWorld world, BlockPos cell) {
        if (!Walkability.passable(world, cell)) {
            return false;
        }
        BlockState wanted = this.order.get(cell);
        if (wanted == null || wanted.isAir() || done(world, cell, wanted)) {
            return true;
        }
        // A later layer (building bottom-up): still empty, and we're gone by the time it's built. Avoiding those too
        // walled off the middle of a layer that is wider than the one below it (an island's underside).
        return this.layered && !this.clearing && this.layerIndex < this.layerOrder.size()
                && cell.getY() > this.layerOrder.get(this.layerIndex);
    }

    // ------------------------------------------------------------------ getting out

    /**
     * The finished build closed around us (a dome, a hollow sphere): open a two-block doorway at the
     * lowest point, walk out, and close it again from outside. Runs after the scaffolding is gone.
     *
     * @return true while there is escape work to do
     */
    private boolean startEscape(MinecraftClient client) {
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
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
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
        BlockState air = net.minecraft.block.Blocks.AIR.getDefaultState();
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
        this.sweepMissing = Integer.MAX_VALUE;
        this.walk = null;
        this.search = null;
        buildLayers();
    }

    /**
     * The air around our feet, if it's closed off: flood through passable cells. Getting outside the
     * order's bounds (or very far) means we're not shut in.
     */
    @Nullable
    private Set<BlockPos> enclosedSpace(ClientWorld world, BlockPos feet) {
        int[] b = orderBounds();
        if (!within(b, feet) || !Walkability.passable(world, feet)) {
            return null;
        }
        Set<BlockPos> seen = new HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        seen.add(feet.toImmutable());
        queue.add(feet.toImmutable());
        while (!queue.isEmpty()) {
            BlockPos c = queue.poll();
            for (Direction d : Direction.values()) {
                BlockPos n = c.offset(d);
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
    private Map<BlockPos, BlockState> pickDoorway(ClientWorld world, ClientPlayerEntity player, Set<BlockPos> inside) {
        List<BlockPos> best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos c : inside) {
            if (!Walkability.standable(world, c)) {
                continue;
            }
            for (Direction d : Direction.Type.HORIZONTAL) {
                List<BlockPos> cells = new ArrayList<>();
                BlockPos out = null;
                for (int k = 1; k <= MAX_DOOR_DEPTH + 1; k++) {
                    BlockPos w = c.offset(d, k);
                    BlockPos head = w.up();
                    if (k > 1 && !inside.contains(w) && Walkability.passable(world, w) && Walkability.passable(world, head)) {
                        out = w;
                        break;
                    }
                    if (k > MAX_DOOR_DEPTH || !doorCell(world, w, true) || !doorCell(world, head, false)) {
                        break;
                    }
                    if (!world.getBlockState(w).isAir()) {
                        cells.add(w.toImmutable());
                    }
                    if (!world.getBlockState(head).isAir() && this.originalOrder.containsKey(head)) {
                        cells.add(head.toImmutable());
                    }
                }
                if (out == null || cells.isEmpty()) {
                    continue;
                }
                // Going out through a door of the build is best: it's only taken out and put back.
                boolean viaDoor = world.getBlockState(cells.get(0)).getBlock() instanceof DoorBlock;
                double score = c.getY() * 100.0 + cells.size() * 5.0 + (Walkability.standable(world, out) ? 0.0 : 50.0)
                        - (viaDoor ? 150.0 : 0.0)
                        + c.getSquaredDistance(player.getBlockPos()) * 0.01;
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
    private boolean doorCell(ClientWorld world, BlockPos cell, boolean feet) {
        if (Walkability.passable(world, cell)) {
            return true;
        }
        return this.originalOrder.containsKey(cell) && !world.getBlockState(cell).isAir();
    }

    /** Our feet or head would be in a doorway cell. */
    private boolean inDoorway(BlockPos feet) {
        return this.doorway.containsKey(feet) || this.doorway.containsKey(feet.up());
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
    private static boolean holdsUp(ClientPlayerEntity player, BlockPos pos) {
        var box = player.getBoundingBox();
        int y = (int) Math.floor(box.minY - 0.01);
        return pos.getY() == y
                && pos.getX() >= (int) Math.floor(box.minX) && pos.getX() <= (int) Math.floor(box.maxX - 1.0E-6)
                && pos.getZ() >= (int) Math.floor(box.minZ) && pos.getZ() <= (int) Math.floor(box.maxZ - 1.0E-6);
    }

    /** Empty cells straight below {@code pos} before something to land on (up to 32). */
    private static int dropUnder(ClientWorld world, BlockPos pos) {
        int n = 0;
        BlockPos c = pos.down();
        while (n < 32 && Walkability.passable(world, c)) {
            n++;
            c = c.down();
        }
        return n;
    }

    /**
     * Where a route to {@code target} may end. Not inside while closing a doorway (that's how we got
     * shut in), and not on top of a block we're about to mine over a long drop.
     */
    private boolean mayStandAt(ClientWorld world, BlockPos feet, BlockPos target) {
        if (this.escapeStage == 2 && (this.shutIn.contains(feet) || inDoorway(feet))) {
            return false;
        }
        return !(this.clearing && feet.down().equals(target) && dropUnder(world, target) >= 3);
    }

    // ------------------------------------------------------------------ pillaring

    /** Ground near (below or beside) the target with a clear column above it to climb. */
    private boolean pillarSite(ClientWorld world, BlockPos feet, BlockPos target) {
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
            BlockPos c = feet.up(i);
            // Head room all the way up. Passing through not-yet-built planned spots is fine: the
            // pillar block there gets swapped for the real one when that layer is built.
            if (i > 0 && !Walkability.passable(world, c.up(1))) {
                return false;
            }
        }
        return true;
    }

    private void startPillar(MinecraftClient client, BlockPos target) {
        trace("  pillar up at %s for %s", client.player.getBlockPos().toShortString(), target.toShortString());
        this.pillarTarget = target;
        this.pillarStep = client.player.getBlockPos();
        // Climbing aids first: scaffolding (all of it comes down by breaking its bottom piece), else ladders or
        // vines up a wall right beside us.
        this.climbWall = null;
        // Taking scaffolding down: get about on what's already there, put nothing new up.
        this.climbItem = this.cleaning || scaffoldingBlock(client) != null ? null : climbable(client);
        if (this.climbItem != null) {
            this.climbWall = wallFor(client.world, this.pillarStep, target);
        }
        trace("  climb with %s", this.climbWall != null ? this.climbItem + " up the " + this.climbWall + " wall"
                : scaffoldingBlock(client) != null ? "scaffolding" : "blocks");
        this.pillarPlaced = 0;
        this.pillarTicks = 0;
        this.pillarBestY = this.pillarStep.getY();
        this.pillarStall = 0;
        this.status = "climbing";
    }

    private static boolean aids(MinecraftClient client) {
        return com.testrts.rtsclient.config.RtsConfig.get().climbingAids && !Creative.supplies(client);
    }

    /** Scaffolding (the block) to pillar up with, when carried: quick to put up and to take down. */
    @Nullable
    private static Item scaffoldingBlock(MinecraftClient client) {
        return aids(client) && Inventories.count(client, net.minecraft.item.Items.SCAFFOLDING) > 0 ? net.minecraft.item.Items.SCAFFOLDING : null;
    }

    /** Ladders, else vines, to climb a wall with, when carried. */
    @Nullable
    private static Item climbable(MinecraftClient client) {
        if (!aids(client)) {
            return null;
        }
        for (Item item : new Item[]{net.minecraft.item.Items.LADDER, net.minecraft.item.Items.VINE}) {
            if (Inventories.count(client, item) > 0) {
                return item;
            }
        }
        return null;
    }

    /**
     * A side of {@code feet} with a solid wall all the way up to the height we need ({@code target} in reach),
     * and room for ladders in front of it; null if there's none.
     */
    @Nullable
    private static Direction wallFor(ClientWorld world, BlockPos feet, BlockPos target) {
        int top = target.getY() - 1;
        if (top <= feet.getY()) {
            return null;
        }
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            boolean ok = true;
            for (int y = feet.getY(); y <= top && ok; y++) {
                BlockPos cell = new BlockPos(feet.getX(), y, feet.getZ());
                BlockState in = world.getBlockState(cell);
                ok = clickable(world, cell.offset(dir)) && (in.isReplaceable() || in.isOf(net.minecraft.block.Blocks.LADDER) || in.isOf(net.minecraft.block.Blocks.VINE));
            }
            if (ok) {
                return dir;
            }
        }
        return null;
    }

    /** A plain right-click (not sneaking), for scaffolding. */
    private static void use(MinecraftClient client, ClientPlayerEntity player, BlockHitResult hit) {
        if (client.interactionManager != null) {
            client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
            player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Up a wall on ladders (or vines): put one in our cell and the one above as we go, push into the wall to climb. */
    private void tickWallClimb(MinecraftClient client, MovementInput input, BlockPos target) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        Direction wall = this.climbWall;
        BlockPos feet = player.getBlockPos();
        if (player.isClimbing() && feet.getY() >= target.getY() - 1 && Reach.inReach(client, target)) {
            // High enough: hold on (sneaking stops us sliding down) and work from here.
            input.forward = false;
            input.jump = false;
            input.sneak = true;
            this.holdingOn = true;
            this.pillarTarget = null;
            return;
        }
        for (BlockPos cell : new BlockPos[]{feet, feet.up()}) {
            if (cell.getY() > target.getY() - 1) {
                continue;
            }
            BlockState in = world.getBlockState(cell);
            if (in.isOf(net.minecraft.block.Blocks.LADDER) || in.isOf(net.minecraft.block.Blocks.VINE)) {
                continue;
            }
            BlockPos block = cell.offset(wall);
            if (!in.isReplaceable() || !clickable(world, block) || !Inventories.hold(client, this.climbItem)) {
                // The wall ends, something's in the way, or we're out of ladders: climb the usual way from here.
                this.climbWall = null;
                return;
            }
            input.forward = false;
            input.jump = false;
            // Sneaking holds us on the ladder (or keeps us still on the ground) while we turn and place.
            input.sneak = true;
            // Stand a little back from the wall: a ladder fills the 3/16 of the cell against it, and with our body
            // half 0.6 wide it can't go in if we're even a hair closer than the middle.
            var at = player.getPos();
            double cx = cell.getX() + 0.5 - wall.getOffsetX() * 0.1 - at.x;
            double cz = cell.getZ() + 0.5 - wall.getOffsetZ() * 0.1 - at.z;
            if (cx * cx + cz * cz > 0.05 * 0.05) {
                float rad = player.getYaw() * net.minecraft.util.math.MathHelper.RADIANS_PER_DEGREE;
                double fwd = -cx * net.minecraft.util.math.MathHelper.sin(rad) + cz * net.minecraft.util.math.MathHelper.cos(rad);
                double left = cx * net.minecraft.util.math.MathHelper.cos(rad) + cz * net.minecraft.util.math.MathHelper.sin(rad);
                input.forward = fwd > 0.04;
                input.back = fwd < -0.04;
                input.left = left > 0.04;
                input.right = left < -0.04;
                return;
            }
            input.back = false;
            input.left = false;
            input.right = false;
            if (!Look.toward(player, wall.asRotation(), 0.0F)) {
                return;
            }
            Vec3d face = Vec3d.ofCenter(block).add(-wall.getOffsetX() * 0.5, 0, -wall.getOffsetZ() * 0.5);
            trace("  ladder into %s against %s (%s there now, player at %s)", cell.toShortString(), block.toShortString(),
                    in.getBlock(), player.getPos());
            Clicks.place(client, player, new BlockHitResult(face, wall.getOpposite(), block, false));
            this.scaffolds.add(cell.toImmutable());
            this.pillarPlaced++;
            progress();
            this.status = "climbing (" + this.pillarPlaced + ")";
            this.cooldown = PLACE_DELAY_TICKS;
            return;
        }
        // Facing the wall, walking into the ladder climbs it.
        input.sneak = false;
        input.jump = false;
        input.forward = Look.toward(player, wall.asRotation(), 0.0F);
    }

    /**
     * Scaffolding is climbed like a ladder, not stood on piece by piece: stack the whole column from the ground
     * (each click on it puts a piece on its top) up to the height we need, then climb up inside it holding jump
     * and stand on its top.
     */
    private void tickScaffoldingColumn(MinecraftClient client, MovementInput input, BlockPos target) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        BlockPos base = this.pillarStep;
        // Standing on a column we put up before: carry on with that one, from its bottom piece (clicking its top
        // would send the new piece sideways).
        for (int i = 0; i < 64 && world.getBlockState(base.down()).isOf(net.minecraft.block.Blocks.SCAFFOLDING); i++) {
            base = base.down();
        }
        int top = target.getY() - 2; // standing on this piece, the target is level with our eyes
        BlockPos colTop = null;
        for (BlockPos c = base; world.getBlockState(c).isOf(net.minecraft.block.Blocks.SCAFFOLDING); c = c.up()) {
            colTop = c;
            this.scaffolds.add(c.toImmutable());
        }
        input.forward = false;
        if (colTop == null || colTop.getY() < top) {
            // Stack it: the first piece on the ground where we stand, then on the column (it goes to the top).
            input.jump = false;
            BlockHitResult hit = colTop == null
                    ? new BlockHitResult(new Vec3d(base.getX() + 0.5, base.getY(), base.getZ() + 0.5), Direction.UP, base.down(), false)
                    // As Minecraft does it: scaffolding clicked on its top face goes sideways (the way we face); on a
                    // side face it goes up the column into the first free spot above its top. The top piece is the one
                    // to click: always within the server's reach, wherever we are on the column.
                    : new BlockHitResult(new Vec3d(colTop.getX() + 0.5, colTop.getY() + 0.5, colTop.getZ()), Direction.NORTH, colTop, false);
            if (colTop == null && !world.getBlockState(base).isReplaceable()) {
                this.climbWall = null;
                this.pillarTarget = null;
                unreachable(target);
                return;
            }
            use(client, player, hit);
            this.pillarPlaced++;
            progress();
            this.status = "putting up scaffolding (" + this.pillarPlaced + ")";
            this.cooldown = PLACE_DELAY_TICKS;
            return;
        }
        // Up inside it like a ladder; on its top, the usual end of a climb (on the ground, in reach) takes over.
        boolean onTop = player.isOnGround() && player.getY() >= colTop.getY() + 1.0 - 1e-3;
        input.jump = !onTop;
        input.sneak = false;
        this.status = "climbing the scaffolding";
        if (onTop) {
            // Up top and still out of reach: this column won't do.
            trace("  on top of the scaffolding at %s and %s still out of reach", colTop.toShortString(), target.toShortString());
            input.release();
            this.pillarTarget = null;
            unreachable(target);
        }
    }

    private void tickPillar(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        MovementInput input = MovementInput.acquire(player);
        BlockPos target = this.pillarTarget;
        if (player.isOnGround() && Reach.inReach(client, target)) {
            input.release();
            this.pillarTarget = null;
            return;
        }
        // Blocks that don't stick (rejected by the server, nothing to place against) mean jumping on
        // the spot forever; give up when we haven't gained height for a while.
        int y = player.getBlockPos().getY();
        if (y > this.pillarBestY) {
            this.pillarBestY = y;
            this.pillarStall = 0;
        }
        boolean stalled = ++this.pillarStall > PILLAR_STALL_TICKS;
        if (++this.pillarTicks > 20 * 40 || this.pillarPlaced > 30 || stalled
                || !Walkability.passable(world, player.getBlockPos().up(2))) {
            // Ceiling in the way, the blocks don't stay, or it's taking far too long.
            trace("  climb given up: %s", stalled ? "no height gained" : this.pillarPlaced > 30 ? "too many blocks"
                    : this.pillarTicks > 20 * 40 ? "too long" : "ceiling");
            input.release();
            this.pillarTarget = null;
            if (stalled) {
                warn("Can't climb up to " + target.toShortString() + ", doing other blocks first");
            }
            unreachable(target);
            return;
        }
        if (this.climbWall != null) {
            tickWallClimb(client, input, target);
            return;
        }
        Item scaffolding = this.cleaning ? null : scaffoldingBlock(client);
        Item item = scaffolding != null ? scaffolding : scaffoldItem(client);
        if (item == null || !Inventories.hold(client, item)) {
            input.release();
            this.pillarTarget = null;
            pauseForScaffolding();
            return;
        }
        if (scaffolding != null) {
            tickScaffoldingColumn(client, input, target);
            return;
        }
        input.forward = false;
        if (player.isOnGround()) {
            // Look down at our feet first (turning, not snapping), then jump.
            if (!Look.toward(player, player.getYaw(), 90.0F)) {
                input.jump = false;
                return;
            }
            this.pillarStep = player.getBlockPos();
            input.jump = true;
            return;
        }
        input.jump = false;
        BlockPos step = this.pillarStep;
        // Near the top of the jump: put a block where our feet just were.
        if (player.getY() >= step.getY() + 1.0 && world.getBlockState(step).isReplaceable()) {
            BlockPos below = step.down();
            BlockHitResult hit = new BlockHitResult(new Vec3d(step.getX() + 0.5, step.getY(), step.getZ() + 0.5), Direction.UP, below, false);
            if (item == net.minecraft.item.Items.SCAFFOLDING && !interactive(world.getBlockState(below))) {
                // Not sneaking: on scaffolding that would drop us through it. Clicking the column puts the
                // new piece on its top.
                use(client, player, hit);
            } else {
                Clicks.place(client, player, hit);
            }
            this.scaffolds.add(step.toImmutable());
            this.pillarPlaced++;
            progress();
            this.status = "climbing (" + this.pillarPlaced + ")";
        }
    }

    /**
     * Make sure {@link #todo} holds the current layer's unfinished blocks, moving to the next layer
     * when this one is finished. Rescans only every {@link #RESCAN_TICKS} ticks.
     *
     * @return false when every layer is finished
     */
    /** Bring the current layer's set-aside blocks back into {@link #todo}. @return false if there were none */
    private boolean retryLayer(ClientWorld world) {
        for (BlockPos p : this.layers.get(this.layerOrder.get(this.layerIndex))) {
            if (this.skipped.remove(p) && !done(world, p, this.order.get(p))) {
                this.failures.remove(p);
                this.walkStucks.remove(p);
                this.stepAsides.remove(p);
                this.breakAttempts.remove(p);
                this.todo.add(p);
            }
        }
        return !this.todo.isEmpty();
    }

    /**
     * The Skip layer key: set aside what's left of the current layer (it counts as skipped) and go on with
     * the next one. @return the layer's Y, or null when not building in layers
     */
    @Nullable
    public Integer skipLayer(MinecraftClient client) {
        if (!this.layered || this.layerIndex >= this.layerOrder.size() || client.world == null) {
            return null;
        }
        int y = this.layerOrder.get(this.layerIndex);
        for (BlockPos p : this.layers.get(y)) {
            if (!done(client.world, p, this.order.get(p))) {
                this.skipped.remove(p);
                this.impossible.add(p);
            }
        }
        // Drop whatever we were doing for that layer; the next tick picks from the next one.
        stop(client);
        this.aimTarget = null;
        this.walkTarget = null;
        this.chasing = null;
        this.scaffoldFor = null;
        this.flyPath = null;
        this.flyTo = null;
        this.flyLanding = false;
        this.todo.clear();
        this.layerRetries = 0;
        this.rescanIn = 0;
        return y;
    }

    private boolean advanceLayer(ClientWorld world) {
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
                if (this.layered && !this.clearing && this.layerRetries < MAX_LAYER_RETRIES && retryLayer(world)) {
                    this.layerRetries++;
                    return true;
                }
                this.layerRetries = 0;
                this.layerIndex++;
                this.walk = null;
                this.search = null;
                int missing = this.layerIndex >= this.layerOrder.size() && !this.cleaning ? countMissing(world) : 0;
                if (missing > 0 && (this.sweeps < MAX_SWEEPS || missing < this.sweepMissing && this.sweeps < MAX_SWEEPS_WITH_PROGRESS)) {
                    // Check the whole order once more before finishing: blocks that failed earlier may work now (new
                    // supports, routes), and a block of a finished layer can be gone again (a placement the server
                    // undid after we'd moved on, something knocked out) - those were never looked at again.
                    trace("sweep %d: %d block(s) missing", this.sweeps + 1, missing);
                    this.sweeps++;
                    this.sweepMissing = missing;
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

    /** Blocks of the order that aren't there (and could be), in every layer. */
    private int countMissing(ClientWorld world) {
        int n = 0;
        for (Map.Entry<BlockPos, BlockState> e : this.order.entrySet()) {
            BlockPos p = e.getKey();
            if (!this.impossible.contains(p) && !done(world, p, e.getValue()) && !changedByItself(world, p)) {
                n++;
            }
        }
        return n;
    }

    public void stop(MinecraftClient client) {
        this.holdingOn = false;
        if (this.breaking != null && this.breakStarted && client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
        this.breaking = null;
        this.walk = null;
        this.search = null;
        this.pillarTarget = null;
        this.scaffoldPlan.clear();
        MovementInput.restore(client);
    }

    // ------------------------------------------------------------------ decisions

    /** Some of the current layer is set aside: don't build ahead over it, it still has to be reached. */
    private boolean layerHeldUp() {
        if (this.layerIndex >= this.layerOrder.size()) {
            return false;
        }
        int y = this.layerOrder.get(this.layerIndex);
        for (BlockPos p : this.skipped) {
            if (p.getY() == y) {
                return true;
            }
        }
        return false;
    }

    /** {@code p} is {@code k} layers up, right above a block of the current layer that isn't down yet. */
    private boolean overUnfinished(BlockPos p, int k) {
        for (int j = 1; j <= k; j++) {
            if (this.todo.contains(p.down(j))) {
                return true;
            }
        }
        return false;
    }

    private static boolean done(ClientWorld world, BlockPos pos, BlockState wanted) {
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        return com.testrts.rtsclient.task.MaterialPlanner.matches(world.getBlockState(pos), wanted);
    }

    @Nullable
    private BlockPos pickWalkTarget(ClientWorld world, ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos p : this.todo) {
            double d = Vec3d.ofCenter(p).squaredDistanceTo(eye);
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
    private static boolean canWorkFrom(MinecraftClient client, BlockPos feet, BlockPos target) {
        if (feet.equals(target) || feet.up().equals(target)) {
            return false;
        }
        // A solid target gets mined: stand within mining reach of it. Otherwise (placing) the usual reach.
        BlockState there = client.world.getBlockState(target);
        boolean mining = !there.isAir() && !there.isReplaceable() && !(there.getBlock() instanceof FluidBlock);
        double reach = mining ? mineReach(client, target) - 0.2 : Reach.reach(client) - 0.4;
        // Eye height over the real floor (lower on slabs and carpets).
        Vec3d eye = new Vec3d(feet.getX() + 0.5, Walkability.standY(client.world, feet) + 1.62, feet.getZ() + 0.5);
        return eye.squaredDistanceTo(Vec3d.ofCenter(target)) <= reach * reach;
    }

    private boolean actInReach(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        Vec3d eye = player.getEyePos();
        double r = Reach.reach(client);
        double r2 = r * r;
        List<BlockPos> inReach = new ArrayList<>();
        for (BlockPos p : this.todo) {
            if (Vec3d.ofCenter(p).squaredDistanceTo(eye) <= r2) {
                inReach.add(p);
            }
        }
        // Build ahead: with mid-air placing nothing needs a neighbour, so blocks of the next layers that are
        // already in reach go too, instead of walking the whole ring once per layer. Off: strictly by layer.
        var cfg = com.testrts.rtsclient.config.RtsConfig.get();
        if (!this.clearing && this.layered && cfg.buildAhead && cfg.airPlace && !layerHeldUp()) {
            // Flying over the layer: nothing at the height we hover at, or we'd wall ourselves in up there.
            int below = canFly(player) && cfg.flyAboveLayer ? hoverFeetY(this.layerOrder.get(this.layerIndex)) : Integer.MAX_VALUE;
            for (int k = 1; k <= LOOKAHEAD_LAYERS && this.layerIndex + k < this.layerOrder.size(); k++) {
                for (BlockPos p : this.layers.get(this.layerOrder.get(this.layerIndex + k))) {
                    if (p.getY() < below && !this.skipped.contains(p) && !this.impossible.contains(p)
                            && Vec3d.ofCenter(p).squaredDistanceTo(eye) <= r2 && !done(world, p, this.order.get(p))
                            && !overUnfinished(p, k)) {
                        inReach.add(p);
                    }
                }
            }
        }
        if (inReach.isEmpty()) {
            return false;
        }
        // Nearest first, but rather what's close to where we already look: less turning between blocks.
        Vec3d look = player.getRotationVector();
        inReach.sort(Comparator.comparingDouble(p -> {
            Vec3d to = Vec3d.ofCenter(p).subtract(eye);
            double turn = Math.acos(Math.max(-1.0, Math.min(1.0, to.normalize().dotProduct(look))));
            return to.lengthSquared() + turn * 6.0;
        }));
        // Multi-place while flying: put down what's in reach without stopping or turning, and keep flying on
        // (stopping at every block and flying a short hop to the next one took most of the time).
        int multi = com.testrts.rtsclient.config.RtsConfig.get().multiPlace;
        int flyBy;
        if (!this.clearing && multi > 1 && canFly(player) && this.aimTarget == null && (flyBy = placeMore(client, inReach, null, multi)) > 0) {
            trace("  fly-by placed %d of %d in reach", flyBy, inReach.size());
            progress();
            this.cooldown = PLACE_DELAY_TICKS;
            return false;
        }
        // Stay with the block we're already turning toward: re-picking every tick could switch back and
        // forth between two blocks as the view moves and never finish turning to either.
        if (this.aimTarget != null && inReach.remove(this.aimTarget)) {
            inReach.add(0, this.aimTarget);
        }
        if (this.clearing) {
            BlockPos below = player.getBlockPos().down();
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
                if (state.getHardness(world, p) < 0) {
                    impossible(p);
                    continue;
                }
                if (tooManyBreaks(p)) {
                    continue;
                }
                if (com.testrts.rtsclient.build.Protection.get().isProtected(p)) {
                    protectedSkip(p);
                    continue;
                }
                if (!withinMineReach(client, p)) {
                    continue; // in reach, but not comfortably: the walk brings us closer
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
                if (unsupported == null && !player.getBoundingBox().intersects(new Box(p)) && !hasSupport(world, p)) {
                    unsupported = p;
                }
                continue;
            }
            supportedInReach = true;
            BlockState current = world.getBlockState(p);
            if (!current.isAir() && !current.isReplaceable()) {
                if (this.placedHere.contains(p)) {
                    // We put a block here and it turned into something else by itself (grass to dirt,
                    // a torch that became a wall torch...). Leave it rather than mine and re-place forever.
                    this.todo.remove(p);
                    continue;
                }
                // Something else is in the way: mine it first.
                if (current.getHardness(world, p) < 0) {
                    impossible(p);
                    continue;
                }
                if (tooManyBreaks(p)) {
                    continue;
                }
                if (com.testrts.rtsclient.build.Protection.get().isProtected(p)) {
                    protectedSkip(p);
                    continue;
                }
                if (!withinMineReach(client, p)) {
                    continue; // in reach, but not comfortably: the walk brings us closer
                }
                trace("  mine %s (in the way of %s)", current.getBlock().getName().getString(), p.toShortString());
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
                this.status = "missing " + item.getName().getString();
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
                if (!p.equals(this.aimTarget)) {
                    trace("  turn to %s from %s", p.toShortString(), player.getBlockPos().toShortString());
                }
                this.turnedTicks++;
                this.aimTarget = p.toImmutable();
                return true;
            }
            this.aimTarget = null;
            if (placed == Placed.DONE) {
                if (this.placeAttempts.getOrDefault(p, 0) > 0) {
                    trace("  place %s again (attempt %d)", p.toShortString(), this.placeAttempts.get(p) + 1);
                }
                this.placeAttempts.merge(p, 1, Integer::sum);
                this.placedHere.add(p.toImmutable());
                progress();
                placeMore(client, inReach, p, com.testrts.rtsclient.config.RtsConfig.get().multiPlace - 1);
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
            if (scaffoldingAllowed() && planScaffold(world, player, unsupported)) {
                trace("scaffold for %s: %s", unsupported.toShortString(), this.scaffoldPlan.stream().map(BlockPos::toShortString).toList());
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
            if (this.todo.contains(pos.offset(d))) {
                return true;
            }
        }
        return false;
    }

    private boolean anySupported(ClientWorld world) {
        for (BlockPos p : this.todo) {
            if (hasSupport(world, p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasSupport(ClientWorld world, BlockPos pos) {
        return support(world, pos) != null;
    }

    private static boolean placeable(ClientWorld world, ClientPlayerEntity player, BlockPos pos) {
        // Don't place a block inside ourselves.
        if (player.getBoundingBox().intersects(new Box(pos))) {
            return false;
        }
        return hasSupport(world, pos) || com.testrts.rtsclient.config.RtsConfig.get().airPlace;
    }

    /** The side of the target to click: a neighbour that is solid and won't open a GUI. */
    @Nullable
    static Direction support(ClientWorld world, BlockPos pos) {
        for (Direction d : SUPPORT_ORDER) {
            BlockPos n = pos.offset(d);
            if (!world.isChunkLoaded(n)) {
                continue;
            }
            BlockState s = world.getBlockState(n);
            if (s.isAir() || s.isReplaceable() || s.getCollisionShape(world, n).isEmpty() || interactive(s)) {
                continue;
            }
            return d;
        }
        return null;
    }

    private static boolean interactive(BlockState s) {
        Block b = s.getBlock();
        return s.hasBlockEntity() || b instanceof DoorBlock || b instanceof TrapdoorBlock || b instanceof FenceGateBlock
                || b instanceof ButtonBlock || b instanceof LeverBlock || b instanceof CraftingTableBlock
                || b instanceof BedBlock || b instanceof AnvilBlock || b instanceof NoteBlock
                || b instanceof AbstractRedstoneGateBlock || b instanceof StonecutterBlock || b instanceof GrindstoneBlock
                || b instanceof LoomBlock || b instanceof CakeBlock || b instanceof ComposterBlock
                || b instanceof FlowerPotBlock || b instanceof AbstractCauldronBlock;
    }

    // ------------------------------------------------------------------ placing

    private enum Placed { DONE, TURNING, FAILED }

    /** Turn toward the spot and, once facing it, click to place {@code wanted} there. */
    private Placed place(MinecraftClient client, BlockPos pos, BlockState wanted) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        // Every way to click this spot: each solid neighbour's face (and the empty cell itself with mid-air
        // placing), at the middle and the upper / lower half. Where the click lands decides slab and stair
        // halves, the look direction the facing: take the first click + look that gives the wanted block.
        List<BlockHitResult> tries = placementHits(world, player, pos);
        if (tries.isEmpty()) {
            return Placed.FAILED;
        }
        BlockHitResult hit = null;
        Vec3d hitVec = null;
        float[] rotation = null;
        for (BlockHitResult h : tries) {
            float[] r = rotationFor(player, wanted, h);
            if (r != null) {
                hit = h;
                hitVec = h.getPos();
                rotation = r;
                break;
            }
        }
        if (hit == null) {
            hit = tries.get(0);
            hitVec = hit.getPos();
        }
        if (rotation == null) {
            if (this.strict && !wanted.getBlock().getDefaultState().equals(wanted)) {
                impossible(pos);
                return Placed.FAILED;
            }
            rotation = Look.anglesTo(player, hitVec);
        }
        // Blocks with a facing need the exact yaw; for the rest, looking about right is enough.
        if (!(oriented(wanted) ? Look.toward(player, rotation[0], rotation[1]) : Look.towardLoose(player, rotation[0], rotation[1]))) {
            return Placed.TURNING;
        }
        Clicks.place(client, player, hit);
        return Placed.DONE;
    }

    /**
     * Multi-place: after the block we turned to, put down up to {@code more} others in reach in the same tick,
     * nearest to where we look first, each clicked without turning (see {@link #clickWithoutTurning}).
     *
     * @param first the block just placed (skipped), or null
     * @return how many went down
     */
    private int placeMore(MinecraftClient client, List<BlockPos> inReach, BlockPos first, int more) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        int placed = 0;
        for (BlockPos q : inReach) {
            if (more <= 0) {
                return placed;
            }
            BlockState wanted = this.order.get(q);
            if (q.equals(first) || wanted == null || done(world, q, wanted) || !placeable(world, player, q)
                    || this.placeAttempts.getOrDefault(q, 0) >= MAX_PLACE_ATTEMPTS) {
                continue;
            }
            BlockState current = world.getBlockState(q);
            if (!current.isAir() && !current.isReplaceable()) {
                continue; // something to mine first: left to the usual one-at-a-time path
            }
            Item item = wanted.getBlock().asItem();
            if (item == Items.AIR || !Inventories.hold(client, item)) {
                return placed; // out of it: the usual path pauses for it
            }
            if (!clickWithoutTurning(client, q, wanted)) {
                continue;
            }
            this.placeAttempts.merge(q, 1, Integer::sum);
            this.placedHere.add(q.toImmutable());
            more--;
            placed++;
        }
        return placed;
    }

    /**
     * Place {@code wanted} at {@code pos} the way hold-Z does: the look the block needs is sent to the server
     * (and set on the client) for the click only, then put back. False if no click gives the wanted block.
     */
    private boolean clickWithoutTurning(MinecraftClient client, BlockPos pos, BlockState wanted) {
        ClientPlayerEntity player = client.player;
        List<BlockHitResult> tries = placementHits(client.world, player, pos);
        BlockHitResult hit = null;
        float[] rotation = null;
        for (BlockHitResult h : tries) {
            float[] r = rotationFor(player, wanted, h);
            if (r != null) {
                hit = h;
                rotation = r;
                break;
            }
        }
        if (hit == null) {
            if (tries.isEmpty() || oriented(wanted)) {
                return false;
            }
            hit = tries.get(0);
            rotation = Look.anglesTo(player, hit.getPos());
        }
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(rotation[0], rotation[1], player.isOnGround()));
        player.setYaw(rotation[0]);
        player.setPitch(rotation[1]);
        try {
            Clicks.place(client, player, hit);
        } finally {
            player.setYaw(yaw);
            player.setPitch(pitch);
            player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, player.isOnGround()));
        }
        return true;
    }

    /** Clicks that put a block into {@code pos}: see {@link #place}. Supported faces first, the cell itself last. */
    private static List<BlockHitResult> placementHits(ClientWorld world, ClientPlayerEntity player, BlockPos pos) {
        List<BlockHitResult> out = new ArrayList<>();
        for (Direction d : SUPPORT_ORDER) {
            BlockPos n = pos.offset(d);
            if (!clickable(world, n)) {
                continue;
            }
            Direction face = d.getOpposite();
            Vec3d mid = Vec3d.ofCenter(pos).add(d.getOffsetX() * 0.5, d.getOffsetY() * 0.5, d.getOffsetZ() * 0.5);
            out.add(new BlockHitResult(mid, face, n, false));
            if (face.getAxis().isHorizontal()) {
                out.add(new BlockHitResult(mid.add(0, 0.3, 0), face, n, false));
                out.add(new BlockHitResult(mid.add(0, -0.3, 0), face, n, false));
            }
        }
        if (com.testrts.rtsclient.config.RtsConfig.get().airPlace) {
            // Nothing (or nothing that works) to attach to: click the empty cell itself. The server places a
            // block in a replaceable cell that is clicked directly.
            Vec3d eye = player.getEyePos();
            Direction toward = Direction.getFacing(eye.x - (pos.getX() + 0.5), eye.y - (pos.getY() + 0.5), eye.z - (pos.getZ() + 0.5));
            for (Direction f : new Direction[]{toward, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                Vec3d mid = Vec3d.ofCenter(pos).add(f.getOffsetX() * 0.5, f.getOffsetY() * 0.5, f.getOffsetZ() * 0.5);
                out.add(new BlockHitResult(mid, f, pos, false));
                if (f.getAxis().isHorizontal()) {
                    out.add(new BlockHitResult(mid.add(0, 0.3, 0), f, pos, false));
                    out.add(new BlockHitResult(mid.add(0, -0.3, 0), f, pos, false));
                }
            }
        }
        return out;
    }

    /**
     * The block that would be placed is {@code wanted} as far as an order cares: same block, same facing,
     * half, axis... Neighbour-driven properties (stair corner shapes, fence connections) don't count.
     */
    static boolean sameOrientation(BlockState wanted, @Nullable BlockState result) {
        if (result == null || result.getBlock() != wanted.getBlock()) {
            return false;
        }
        for (net.minecraft.state.property.Property<?> p : wanted.getProperties()) {
            if (ORIENTATION.contains(p.getName()) && result.contains(p) && !result.get(p).equals(wanted.get(p))) {
                return false;
            }
        }
        return true;
    }

    /** A neighbour we may click to place against: solid, and not something that opens a screen. */
    static boolean clickable(ClientWorld world, BlockPos n) {
        if (!world.isChunkLoaded(n)) {
            return false;
        }
        BlockState s = world.getBlockState(n);
        if (s.isOf(net.minecraft.block.Blocks.SCAFFOLDING)) {
            return true; // no collision box without a player to measure against, but solid to click
        }
        return !(s.isAir() || s.isReplaceable() || s.getCollisionShape(world, n).isEmpty() || interactive(s));
    }

    /** Properties that say which way a block points; others (fence connections...) follow the neighbours. */
    private static final java.util.Set<String> ORIENTATION = java.util.Set.of(
            "facing", "axis", "half", "type", "face", "rotation", "hinge", "attachment", "orientation", "vertical_direction",
            "hanging", "top");

    /**
     * Whether clicking {@code hit} with the player's real view (no turning) puts {@code wanted} down
     * pointing the right way. Only the block and its orientation count.
     */
    /** {@code s} has a property saying which way it points (see {@link #ORIENTATION}). */
    static boolean oriented(BlockState s) {
        for (var p : s.getProperties()) {
            if (ORIENTATION.contains(p.getName())) {
                return true;
            }
        }
        return false;
    }

    static boolean comesOutAs(ClientPlayerEntity player, BlockState wanted, BlockHitResult hit) {
        BlockState result;
        try {
            result = wanted.getBlock().getPlacementState(new ItemPlacementContext(player, Hand.MAIN_HAND, player.getMainHandStack(), hit));
        } catch (RuntimeException e) {
            return false;
        }
        if (result == null || result.getBlock() != wanted.getBlock()) {
            return false;
        }
        for (net.minecraft.state.property.Property<?> p : wanted.getProperties()) {
            if (ORIENTATION.contains(p.getName()) && result.contains(p) && !result.get(p).equals(wanted.get(p))) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    static float[] rotationFor(ClientPlayerEntity player, BlockState wanted, BlockHitResult hit) {
        ItemStack stack = player.getMainHandStack();
        float oldYaw = player.getYaw();
        float oldPitch = player.getPitch();
        try {
            // Simply looking at the spot is what a player would do; use it whenever it gives the
            // right state (always, for blocks without a facing). Only oriented blocks need the
            // fixed directions below.
            float[] natural = Look.anglesTo(player, hit.getPos());
            player.setYaw(natural[0]);
            player.setPitch(natural[1]);
            if (sameOrientation(wanted, wanted.getBlock().getPlacementState(new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit)))) {
                return natural;
            }
            for (float pitch : PITCHES) {
                for (float yaw : YAWS) {
                    player.setYaw(yaw);
                    player.setPitch(pitch);
                    BlockState result = wanted.getBlock().getPlacementState(new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit));
                    if (sameOrientation(wanted, result)) {
                        return new float[]{yaw, pitch};
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Some modded blocks don't like being asked hypothetically.
        } finally {
            player.setYaw(oldYaw);
            player.setPitch(oldPitch);
        }
        return null;
    }

    // ------------------------------------------------------------------ mining range

    /**
     * Blocks the server put back after we mined them (it thought we were too far: on a server our position
     * lags a little). Mined only from close up from then on, for the rest of the session.
     */
    private static final java.util.Set<BlockPos> CLOSE_ONLY = new java.util.HashSet<>();
    /** When we saw blocks we mined turn to air, to notice the server putting them back. */
    private static final Map<BlockPos, Long> MINED_AT = new java.util.HashMap<>();
    private static final double CLOSE_MINE_REACH = 2.5;
    private static final long PUT_BACK_MS = 3000;

    /** How close we must be to start mining {@code p}: well inside reach, much closer if the server objected. */
    static double mineReach(MinecraftClient client, BlockPos p) {
        return CLOSE_ONLY.contains(p) ? CLOSE_MINE_REACH : Reach.reach(client) - 0.8;
    }

    private static boolean withinMineReach(MinecraftClient client, BlockPos p) {
        double r = mineReach(client, p);
        return client.player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(p)) <= r * r;
    }

    /** Blocks we mined that came back within a few seconds: the server refused. Mine them again, closer. */
    private void checkPutBack(ClientWorld world) {
        if (MINED_AT.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        var it = MINED_AT.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            BlockPos p = e.getKey();
            if (now - e.getValue() > PUT_BACK_MS) {
                it.remove();
                continue;
            }
            BlockState s = world.getBlockState(p);
            if (this.scaffolds.contains(p) || this.placedHere.contains(p)) {
                // We filled it again ourselves (scaffolding on a route, the order's block): not the server.
                it.remove();
                continue;
            }
            if (!s.isAir() && !(s.getBlock() instanceof FluidBlock)) {
                it.remove();
                CLOSE_ONLY.add(p);
                BlockState wanted = this.order.get(p);
                if (wanted != null && !done(world, p, wanted)) {
                    this.todo.add(p);
                }
                warn("The server put " + p.toShortString() + " back (too far for it) - mining it from closer");
            }
        }
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

    private void startBreaking(MinecraftClient client, BlockPos pos) {
        ClientPlayerEntity player = client.player;
        BlockState state = client.world.getBlockState(pos);
        Inventories.holdBestTool(client, state);
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        this.breakingSide = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        // Mining starts in continueBreaking once we've turned toward the block.
        this.breaking = pos.toImmutable();
        this.breakTicks = 0;
        this.breakStarted = false;
    }

    private void continueBreaking(MinecraftClient client) {
        BlockPos pos = this.breaking;
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof FluidBlock) {
            this.breaking = null;
            this.todo.remove(pos);
            MINED_AT.put(pos.toImmutable(), System.currentTimeMillis());
            progress();
            return;
        }
        if (++this.breakTicks > BREAK_TIMEOUT_TICKS || !Reach.inReach(client, pos)) {
            if (this.breakStarted) {
                client.interactionManager.cancelBlockBreaking();
            }
            this.breaking = null;
            fail(pos);
            return;
        }
        Vec3d face = Vec3d.ofCenter(pos).add(this.breakingSide.getOffsetX() * 0.5, this.breakingSide.getOffsetY() * 0.5,
                this.breakingSide.getOffsetZ() * 0.5);
        boolean aimed = Look.at(client.player, face);
        if (!this.breakStarted && !Mining.aimed(client, face, aimed)) {
            return; // still turning toward it
        }
        Mining.skipPause(client);
        if (!this.breakStarted) {
            this.breakStarted = true;
            client.interactionManager.attackBlock(pos, this.breakingSide);
            client.player.swingHand(Hand.MAIN_HAND);
        } else if (client.interactionManager.updateBlockBreakingProgress(pos, this.breakingSide)) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
