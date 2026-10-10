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
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in replacement for Baritone's builder: places and mines blocks within reach, and walks
 * (with {@link PathFinder}) to the next block when nothing is in reach.
 */
public final class NativeBuilder {
    private static final int PLACE_DELAY_TICKS = 2;
    private static final int BREAK_TIMEOUT_TICKS = 20 * 20;
    private static final int MAX_WALK_FAILS = 3;
    private static final Direction[] SUPPORT_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP};
    private static final float[] YAWS = {0F, 90F, 180F, 270F};
    private static final float[] PITCHES = {0F, 75F, -75F};

    private final Map<BlockPos, BlockState> order;
    private final boolean strict;
    private final Set<BlockPos> skipped = new HashSet<>();

    private boolean paused;
    @Nullable
    private Item missingItem;
    private int cooldown;

    @Nullable
    private BlockPos breaking;
    private Direction breakingSide = Direction.UP;
    private int breakTicks;

    @Nullable
    private PathFollower walk;
    @Nullable
    private BlockPos walkTarget;
    private int walkFails;
    private String status = "starting";

    public NativeBuilder(Map<BlockPos, BlockState> order, boolean strict) {
        this.order = new LinkedHashMap<>(order);
        this.strict = strict;
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

        List<BlockPos> pending = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> e : this.order.entrySet()) {
            if (!this.skipped.contains(e.getKey()) && !done(world, e.getKey(), e.getValue())) {
                pending.add(e.getKey());
            }
        }
        if (pending.isEmpty()) {
            MovementInput.restore(client);
            this.status = this.skipped.isEmpty() ? "done" : "done, " + this.skipped.size() + " skipped";
            return false;
        }

        // 1) Work on something within reach.
        if (actInReach(client, pending)) {
            this.walk = null;
            MovementInput.acquire(player).release();
            return true;
        }

        // 2) Otherwise walk toward the next target.
        if (this.walk != null && this.walkTarget != null && pending.contains(this.walkTarget)) {
            if (Reach.inReach(client, this.walkTarget) && !isClear()) {
                // In reach but couldn't act on it (no support yet): don't stand around forever.
                if (!placeable(world, player, this.walkTarget)) {
                    this.skipped.add(this.walkTarget);
                    this.walk = null;
                    return true;
                }
            }
            PathFollower.State s = this.walk.tick(client);
            this.status = "walking (" + pending.size() + " left)";
            if (s == PathFollower.State.WALKING) {
                return true;
            }
            this.walk = null;
            if (s == PathFollower.State.STUCK && ++this.walkFails >= MAX_WALK_FAILS) {
                this.skipped.add(this.walkTarget);
                this.walkFails = 0;
            }
            return true;
        }

        BlockPos target = pickWalkTarget(world, player, pending);
        List<BlockPos> path = PathFinder.find(world, player.getBlockPos(), feet -> canWorkFrom(client, feet, target),
                Vec3d.ofCenter(target));
        if (path == null) {
            this.skipped.add(target);
            return true;
        }
        this.walkTarget = target;
        this.walk = new PathFollower(path);
        if (path.isEmpty() && !actInReach(client, List.of(target))) {
            // Already as close as we can get and still can't do it.
            this.skipped.add(target);
            this.walk = null;
        }
        return true;
    }

    public void stop(MinecraftClient client) {
        if (this.breaking != null && client.interactionManager != null) {
            client.interactionManager.cancelBlockBreaking();
        }
        this.breaking = null;
        this.walk = null;
        MovementInput.restore(client);
    }

    public int skippedCount() {
        return this.skipped.size();
    }

    // ------------------------------------------------------------------ decisions

    private boolean isClear() {
        BlockState any = this.order.values().iterator().next();
        return any.isAir();
    }

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

    private BlockPos pickWalkTarget(ClientWorld world, ClientPlayerEntity player, List<BlockPos> pending) {
        Vec3d eye = player.getEyePos();
        Comparator<BlockPos> byDistance = Comparator.comparingDouble(p -> Vec3d.ofCenter(p).squaredDistanceTo(eye));
        if (isClear()) {
            // Mine top-down so nothing falls on us and we don't dig our own floor out first.
            return pending.stream().max(Comparator.<BlockPos>comparingInt(BlockPos::getY).thenComparing(byDistance.reversed()))
                    .orElse(pending.get(0));
        }
        // Build bottom-up; prefer blocks that already have something to attach to.
        return pending.stream()
                .min(Comparator.<BlockPos>comparingInt(p -> hasSupport(world, p) ? 0 : 1)
                        .thenComparingInt(BlockPos::getY)
                        .thenComparing(byDistance))
                .orElse(pending.get(0));
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

    private boolean actInReach(MinecraftClient client, List<BlockPos> pending) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        List<BlockPos> inReach = new ArrayList<>();
        for (BlockPos p : pending) {
            if (Reach.inReach(client, p)) {
                inReach.add(p);
            }
        }
        if (inReach.isEmpty()) {
            return false;
        }
        if (isClear()) {
            BlockPos below = player.getBlockPos().down();
            inReach.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY).reversed());
            for (BlockPos p : inReach) {
                // Leave the block we're standing on for last.
                if (p.equals(below) && inReach.size() > 1) {
                    continue;
                }
                BlockState state = world.getBlockState(p);
                if (state.getHardness(world, p) < 0) {
                    this.skipped.add(p);
                    continue;
                }
                startBreaking(client, p);
                return true;
            }
            return false;
        }
        inReach.sort(Comparator.<BlockPos>comparingInt(BlockPos::getY)
                .thenComparingDouble(p -> Vec3d.ofCenter(p).squaredDistanceTo(player.getEyePos())));
        for (BlockPos p : inReach) {
            if (!placeable(world, player, p)) {
                continue;
            }
            BlockState wanted = this.order.get(p);
            BlockState current = world.getBlockState(p);
            if (!current.isAir() && !current.isReplaceable()) {
                // Something else is in the way: mine it first.
                if (current.getHardness(world, p) < 0) {
                    this.skipped.add(p);
                    continue;
                }
                startBreaking(client, p);
                return true;
            }
            Item item = wanted.getBlock().asItem();
            if (item == Items.AIR) {
                this.skipped.add(p);
                continue;
            }
            if (!Inventories.hold(client, item)) {
                this.paused = true;
                this.missingItem = item;
                this.status = "missing " + item.getName().getString();
                return true;
            }
            if (place(client, p, wanted)) {
                this.cooldown = PLACE_DELAY_TICKS;
                this.status = "placing";
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
                this.skipped.add(pos);
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
        this.status = "mining";
        client.interactionManager.attackBlock(pos, this.breakingSide);
        player.swingHand(Hand.MAIN_HAND);
    }

    private void continueBreaking(MinecraftClient client) {
        BlockPos pos = this.breaking;
        BlockState state = client.world.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof FluidBlock) {
            this.breaking = null;
            return;
        }
        if (++this.breakTicks > BREAK_TIMEOUT_TICKS || !Reach.inReach(client, pos)) {
            client.interactionManager.cancelBlockBreaking();
            this.skipped.add(pos);
            this.breaking = null;
            return;
        }
        if (client.interactionManager.updateBlockBreakingProgress(pos, this.breakingSide)) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }
}
