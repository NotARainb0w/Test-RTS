package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.task.Reach;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.CobwebBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.Predicate;

/**
 * Walks the player along a list of feet positions by pressing forward/jump and turning.
 * <p>
 * A watchdog checks every {@link #WINDOW_TICKS} ticks that the player got closer to the next
 * node. If not, it tries in turn: a jump, backing off to one side, and (when the caller allows
 * it) mining a soft block in the way such as leaves. When none of that helps it reports
 * {@link State#STUCK}; the caller can then plan again around {@link #failedFrom()} ->
 * {@link #failedTo()}. Being knocked off the route is also reported as STUCK, with
 * {@link #offPath()} set.
 */
public final class PathFollower {
    public enum State { WALKING, ARRIVED, STUCK }

    private enum Recovery { NONE, JUMP, BACK, SIDE }

    /** Ticks per progress check (1.5 s). */
    private static final int WINDOW_TICKS = 30;
    /** How much closer to the next node the player must get per window. */
    private static final double MIN_PROGRESS = 0.25;
    private static final int JUMP_TICKS = 10;
    private static final int BACK_TICKS = 8;
    private static final int SIDE_TICKS = 8;
    private static final int BREAK_TIMEOUT_TICKS = 100;
    /** Hard cap on the whole walk, whatever else happens: this many ticks per node, plus a margin. */
    private static final int TICKS_PER_NODE = 120;
    /** Further than this from the next node means we were pushed or fell off the route. */
    private static final double OFF_PATH_DISTANCE = 3.5;

    private final List<BlockPos> path;
    /** Blocks the follower may mine to get past; null means never mine. */
    @Nullable
    private final Predicate<BlockPos> mayBreak;
    /** Stop right in the middle of the last node (to step out of a block's way). */
    private final boolean precise;
    @Nullable
    private BlockPos start;
    private int index;
    private int ticks;
    private boolean offPath;

    // Watchdog
    private int windowTicks;
    private double windowStart;
    private int strikes;
    private Recovery recovery = Recovery.NONE;
    private int recoveryTicks;
    private boolean sideLeft;

    // Mining something soft out of the way
    @Nullable
    private BlockPos breaking;
    private Direction breakingSide = Direction.UP;
    private int breakTicks;

    public PathFollower(List<BlockPos> path) {
        this(path, null, false);
    }

    /**
     * @param mayBreak which blocks may be mined when they block the way, or null for none
     * @param precise  end in the middle of the last node instead of anywhere near it
     */
    public PathFollower(List<BlockPos> path, @Nullable Predicate<BlockPos> mayBreak, boolean precise) {
        this.path = path;
        this.mayBreak = mayBreak;
        this.precise = precise;
    }

    public List<BlockPos> path() {
        return this.path;
    }

    public int index() {
        return this.index;
    }

    /** True when the last {@link State#STUCK} was the player leaving the route (fall, knock-back). */
    public boolean offPath() {
        return this.offPath;
    }

    /** The node the player last stood on before the step that failed. */
    @Nullable
    public BlockPos failedFrom() {
        return this.index > 0 && this.index <= this.path.size() ? this.path.get(this.index - 1) : this.start;
    }

    /** The node the player couldn't get to. */
    @Nullable
    public BlockPos failedTo() {
        return this.index < this.path.size() ? this.path.get(this.index) : null;
    }

    public State tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return State.STUCK;
        }
        MovementInput input = MovementInput.acquire(player);
        if (this.start == null) {
            this.start = Walkability.feetNode(world, player);
        }
        if (this.breaking != null) {
            input.release();
            tickBreaking(client);
            return State.WALKING;
        }
        // Skip nodes we're already standing on (or past).
        while (this.index < this.path.size() && reached(world, player, this.index)) {
            this.index++;
            this.strikes = 0;
            this.windowTicks = 0;
        }
        if (this.index >= this.path.size()) {
            input.release();
            return State.ARRIVED;
        }
        if (++this.ticks > this.path.size() * TICKS_PER_NODE + 200) {
            input.release();
            return State.STUCK;
        }
        BlockPos target = this.path.get(this.index);
        double targetY = Walkability.standY(world, target);
        Vec3d pos = player.getPos();
        double dx = target.getX() + 0.5 - pos.x;
        double dz = target.getZ() + 0.5 - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean inFluid = player.isTouchingWater() || player.isInLava();

        // Knocked away or fell off the route: better to plan again from here than fight back.
        if (horizontal > OFF_PATH_DISTANCE || (player.isOnGround() && !inFluid && pos.y < targetY - 1.3)) {
            this.offPath = true;
            input.release();
            return State.STUCK;
        }

        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90.0F;
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setPitch(10.0F);

        if (this.recovery != Recovery.NONE) {
            applyRecovery(player, input);
            return State.WALKING;
        }

        boolean last = this.index == this.path.size() - 1;
        boolean careful = this.precise && last;
        input.back = false;
        input.left = false;
        input.right = false;
        input.forward = horizontal > (careful ? 0.1 : 0.15);
        // Sneak the last bit of a precise stop so we don't overshoot (or slide off an edge).
        input.sneak = careful && horizontal < 0.8;
        // Rise measured from where we really stand, so slabs and carpets don't trigger needless jumps.
        boolean climb = targetY - pos.y > 0.6;
        // Bumping into something: jump over it, but not into a low ceiling.
        boolean bump = player.horizontalCollision && Walkability.passable(world, player.getBlockPos().up(2));
        input.jump = inFluid || (player.isOnGround() && (climb && horizontal < 1.4 || bump));

        // Watchdog: the player must get MIN_PROGRESS closer to the node every window.
        double dist = horizontal + Math.abs(targetY - pos.y);
        if (this.windowTicks++ == 0) {
            this.windowStart = dist;
        } else if (this.windowTicks >= WINDOW_TICKS) {
            this.windowTicks = 0;
            if (this.windowStart - dist >= MIN_PROGRESS) {
                this.strikes = 0;
            } else {
                return escalate(client, player, input, target);
            }
        }
        return State.WALKING;
    }

    /** No progress for a whole window: try the next, stronger way of getting unstuck. */
    private State escalate(MinecraftClient client, ClientPlayerEntity player, MovementInput input, BlockPos target) {
        this.strikes++;
        if (this.strikes == 1) {
            // Maybe a lip or a mob: jump while pushing forward.
            begin(Recovery.JUMP, JUMP_TICKS);
        } else if (this.strikes == 2) {
            // Caught on a corner: back off diagonally, then come at it from the side.
            this.sideLeft = !this.sideLeft;
            begin(Recovery.BACK, BACK_TICKS);
        } else if (this.strikes == 3 && this.mayBreak != null && startClearing(client, player, target)) {
            input.release();
        } else {
            input.release();
            return State.STUCK;
        }
        return State.WALKING;
    }

    private void begin(Recovery r, int ticks) {
        this.recovery = r;
        this.recoveryTicks = ticks;
    }

    private void applyRecovery(ClientPlayerEntity player, MovementInput input) {
        input.sneak = false;
        switch (this.recovery) {
            case JUMP -> {
                input.forward = true;
                input.back = false;
                input.left = false;
                input.right = false;
                input.jump = true;
            }
            case BACK -> {
                input.forward = false;
                input.back = true;
                input.left = this.sideLeft;
                input.right = !this.sideLeft;
                input.jump = false;
            }
            case SIDE -> {
                input.forward = true;
                input.back = false;
                input.left = this.sideLeft;
                input.right = !this.sideLeft;
                input.jump = player.horizontalCollision && player.isOnGround();
            }
            default -> {
            }
        }
        if (--this.recoveryTicks <= 0) {
            if (this.recovery == Recovery.BACK) {
                begin(Recovery.SIDE, SIDE_TICKS);
            } else {
                this.recovery = Recovery.NONE;
                input.release();
                this.windowTicks = 0;
            }
        }
    }

    /** Find leaves, bamboo, snow or cobweb in the way (feet or head height) and start mining it. */
    private boolean startClearing(MinecraftClient client, ClientPlayerEntity player, BlockPos target) {
        ClientWorld world = client.world;
        if (client.interactionManager == null) {
            return false;
        }
        BlockPos feet = player.getBlockPos();
        Vec3d pos = player.getPos();
        Direction toward = Direction.getFacing(target.getX() + 0.5 - pos.x, 0.0, target.getZ() + 0.5 - pos.z);
        BlockPos ahead = feet.offset(toward);
        BlockPos[] candidates = {target, target.up(), ahead, ahead.up(), feet.up(2), target.up(2)};
        for (BlockPos p : candidates) {
            BlockState state = world.getBlockState(p);
            if (state.getCollisionShape(world, p).isEmpty() && !(state.getBlock() instanceof CobwebBlock)) {
                continue;
            }
            if (!soft(state) || state.getHardness(world, p) < 0 || !this.mayBreak.test(p) || !Reach.inReach(client, p)) {
                continue;
            }
            Inventories.holdBestTool(client, state);
            Vec3d eye = player.getEyePos();
            Vec3d center = Vec3d.ofCenter(p);
            this.breakingSide = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
            Reach.lookAt(player, center.add(this.breakingSide.getOffsetX() * 0.5, this.breakingSide.getOffsetY() * 0.5,
                    this.breakingSide.getOffsetZ() * 0.5));
            this.breaking = p.toImmutable();
            this.breakTicks = 0;
            client.interactionManager.attackBlock(p, this.breakingSide);
            player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private void tickBreaking(MinecraftClient client) {
        BlockPos p = this.breaking;
        BlockState state = client.world.getBlockState(p);
        boolean gone = state.isAir() || !soft(state);
        if (gone || ++this.breakTicks > BREAK_TIMEOUT_TICKS || !Reach.inReach(client, p)) {
            if (!gone && client.interactionManager != null) {
                client.interactionManager.cancelBlockBreaking();
            }
            this.breaking = null;
            this.windowTicks = 0;
            return;
        }
        if (client.interactionManager != null && client.interactionManager.updateBlockBreakingProgress(p, this.breakingSide)) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** Blocks that are quick to mine and never part of anything worth keeping in the way of a walk. */
    private static boolean soft(BlockState state) {
        Block b = state.getBlock();
        return b instanceof LeavesBlock || b instanceof BambooBlock || b instanceof SnowBlock || b instanceof CobwebBlock;
    }

    private boolean reached(ClientWorld world, ClientPlayerEntity player, int i) {
        BlockPos feet = this.path.get(i);
        boolean careful = this.precise && i == this.path.size() - 1;
        double radius = careful ? 0.18 : 0.35;
        Vec3d pos = player.getPos();
        double dx = feet.getX() + 0.5 - pos.x;
        double dz = feet.getZ() + 0.5 - pos.z;
        if (dx * dx + dz * dz >= radius * radius) {
            return false;
        }
        // Compare with where the feet really are on this floor (a slab or carpet is lower than the node).
        double dy = pos.y - Walkability.standY(world, feet);
        if (player.isTouchingWater()) {
            // Swimming bobs up and down around the surface.
            return dy > -1.5 && dy < 1.2;
        }
        return dy > -0.5 && dy < 1.0;
    }
}
