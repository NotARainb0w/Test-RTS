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

    private final Map<BlockPos, BlockState> order;
    private final boolean clearing;
    private final boolean strict;
    private final boolean layered;
    /** Order positions grouped by Y, in the order layers are worked on. */
    private final TreeMap<Integer, List<BlockPos>> layers = new TreeMap<>();
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

    public NativeBuilder(Map<BlockPos, BlockState> order, boolean strict, boolean layered) {
        this.order = new LinkedHashMap<>(order);
        this.clearing = !order.isEmpty() && order.values().iterator().next().isAir();
        this.strict = strict;
        this.layered = layered;
        for (BlockPos p : this.order.keySet()) {
            this.layers.computeIfAbsent(layered ? p.getY() : 0, y -> new ArrayList<>()).add(p);
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
    public boolean tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null || client.interactionManager == null) {
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

        if (!advanceLayer(world)) {
            MovementInput.restore(client);
            int left = skippedCount();
            this.status = left == 0 ? "done" : "done, " + left + " skipped";
            return false;
        }
        String layerInfo = this.layered && this.layerOrder.size() > 1
                ? " (layer " + (this.layerIndex + 1) + "/" + this.layerOrder.size() + ", " + this.todo.size() + " left)"
                : " (" + this.todo.size() + " left)";

        // 1) Work on something within reach.
        if (actInReach(client)) {
            this.walk = null;
            this.search = null;
            MovementInput.acquire(player).release();
            this.status = (this.clearing ? "mining" : "placing") + layerInfo;
            return true;
        }

        // 2) A path search in progress: advance it a bit.
        if (this.search != null) {
            this.status = "planning" + layerInfo;
            PathFinder.Search.State s = this.search.step(PathFinder.NODES_PER_TICK);
            if (s == PathFinder.Search.State.FOUND) {
                this.walk = new PathFollower(this.search.result());
                this.search = null;
                if (this.walk.path().isEmpty()) {
                    // Already standing where we'd go and still can't act: try other blocks first.
                    fail(this.walkTarget);
                    this.walk = null;
                }
            } else if (s == PathFinder.Search.State.FAILED) {
                this.search = null;
                fail(this.walkTarget);
            }
            return true;
        }

        // 3) Walking toward a target.
        if (this.walk != null && this.walkTarget != null && this.todo.contains(this.walkTarget)) {
            PathFollower.State s = this.walk.tick(client);
            this.status = "walking" + layerInfo;
            if (s == PathFollower.State.WALKING) {
                return true;
            }
            this.walk = null;
            if (s == PathFollower.State.STUCK && ++this.walkFails >= MAX_WALK_FAILS) {
                fail(this.walkTarget);
                this.walkFails = 0;
            }
            if (s == PathFollower.State.ARRIVED && Reach.inReach(client, this.walkTarget)
                    && !this.clearing && !placeable(world, player, this.walkTarget)) {
                // Got there but there's nothing to attach it to yet: its neighbours may fix that.
                fail(this.walkTarget);
            }
            return true;
        }

        // 4) Pick the next block and start planning a route to it.
        BlockPos target = pickWalkTarget(world, player);
        if (target == null) {
            return true;
        }
        this.walkTarget = target;
        this.search = PathFinder.start(world, player.getBlockPos(), feet -> canWorkFrom(client, feet, target),
                Vec3d.ofCenter(target));
        this.status = "planning" + layerInfo;
        return true;
    }

    /**
     * Make sure {@link #todo} holds the current layer's unfinished blocks, moving to the next layer
     * when this one is finished. Rescans only every {@link #RESCAN_TICKS} ticks.
     *
     * @return false when every layer is finished
     */
    private boolean advanceLayer(ClientWorld world) {
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

    public void stop(MinecraftClient client) {
        if (this.breaking != null && client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
        this.breaking = null;
        this.walk = null;
        this.search = null;
        MovementInput.restore(client);
    }

    // ------------------------------------------------------------------ decisions

    private static boolean done(ClientWorld world, BlockPos pos, BlockState wanted) {
        if (!world.isChunkLoaded(pos)) {
            return false;
        }
        BlockState current = world.getBlockState(pos);
        if (wanted.isAir()) {
            return current.isAir() || current.getBlock() instanceof FluidBlock;
        }
        return current.getBlock() == wanted.getBlock();
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
        double reach = Reach.reach(client) - 0.4;
        Vec3d eye = new Vec3d(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
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
        if (inReach.isEmpty()) {
            return false;
        }
        inReach.sort(Comparator.comparingDouble(p -> Vec3d.ofCenter(p).squaredDistanceTo(eye)));
        if (this.clearing) {
            BlockPos below = player.getBlockPos().down();
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
                if (state.getHardness(world, p) < 0) {
                    impossible(p);
                    continue;
                }
                startBreaking(client, p);
                return true;
            }
            return false;
        }
        for (BlockPos p : inReach) {
            BlockState wanted = this.order.get(p);
            if (done(world, p, wanted)) {
                this.todo.remove(p);
                continue;
            }
            if (!placeable(world, player, p)) {
                continue;
            }
            BlockState current = world.getBlockState(p);
            if (!current.isAir() && !current.isReplaceable()) {
                // Something else is in the way: mine it first.
                if (current.getHardness(world, p) < 0) {
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
                this.status = "missing " + item.getName().getString();
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
        return hasSupport(world, pos);
    }

    /** The side of the target to click: a neighbour that is solid and won't open a GUI. */
    @Nullable
    private static Direction support(ClientWorld world, BlockPos pos) {
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

    private boolean place(MinecraftClient client, BlockPos pos, BlockState wanted) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        Direction toSupport = support(world, pos);
        if (toSupport == null) {
            return false;
        }
        BlockPos supportPos = pos.offset(toSupport);
        Direction face = toSupport.getOpposite();
        Vec3d hitVec = Vec3d.ofCenter(pos).add(toSupport.getOffsetX() * 0.5, toSupport.getOffsetY() * 0.5, toSupport.getOffsetZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, supportPos, false);

        // Vanilla decides the placed state from where the player looks. Try the four directions
        // (and up/down) until the block would come out the way the order wants it.
        float[] rotation = rotationFor(player, wanted, hit);
        if (rotation == null) {
            if (this.strict && !wanted.getBlock().getDefaultState().equals(wanted)) {
                impossible(pos);
                return false;
            }
            Reach.lookAt(player, hitVec);
        } else {
            Reach.face(player, rotation[0], rotation[1]);
        }
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
        player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    @Nullable
    private static float[] rotationFor(ClientPlayerEntity player, BlockState wanted, BlockHitResult hit) {
        ItemStack stack = player.getMainHandStack();
        float oldYaw = player.getYaw();
        float oldPitch = player.getPitch();
        try {
            for (float pitch : PITCHES) {
                for (float yaw : YAWS) {
                    player.setYaw(yaw);
                    player.setPitch(pitch);
                    BlockState result = wanted.getBlock().getPlacementState(new ItemPlacementContext(player, Hand.MAIN_HAND, stack, hit));
                    if (wanted.equals(result)) {
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

    // ------------------------------------------------------------------ mining

    private void startBreaking(MinecraftClient client, BlockPos pos) {
        ClientPlayerEntity player = client.player;
        BlockState state = client.world.getBlockState(pos);
        Inventories.holdBestTool(client, state);
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        this.breakingSide = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        Reach.lookAt(player, center.add(this.breakingSide.getOffsetX() * 0.5, this.breakingSide.getOffsetY() * 0.5,
                this.breakingSide.getOffsetZ() * 0.5));
        this.breaking = pos.toImmutable();
        this.breakTicks = 0;
        client.interactionManager.attackBlock(pos, this.breakingSide);
        player.swingHand(Hand.MAIN_HAND);
    }

    private void continueBreaking(MinecraftClient client) {
        BlockPos pos = this.breaking;
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof FluidBlock) {
            this.breaking = null;
            this.todo.remove(pos);
            return;
        }
        if (++this.breakTicks > BREAK_TIMEOUT_TICKS || !Reach.inReach(client, pos)) {
            client.interactionManager.cancelBlockBreaking();
            this.breaking = null;
            fail(pos);
            return;
        }
        if (client.interactionManager.updateBlockBreakingProgress(pos, this.breakingSide)) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
