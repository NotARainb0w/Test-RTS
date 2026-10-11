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
import net.minecraft.util.hit.BlockHitResult;
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
    /** Mining began (we turn toward the block first). */
    private boolean breakStarted;
    /** Next to a drop that hurts: sneak while not lined up (and while backing off). */
    private boolean edgeGuard;
    /** {@link #breaking} is the floor we dig through (any block), not something soft in the way. */
    private boolean digging;

    // Building our own way (pillars, bridges, digging down); null actions = walking only.
    @Nullable
    private List<PathFinder.Action> actions;
    @Nullable
    private Scaffolder scaffolder;
    private int buildTicks;

    /** Places the temporary blocks a route needs. Implemented by the builder. */
    public interface Scaffolder {
        enum Result { DONE, BUSY, FAILED }

        /** Put a scaffolding block in hand. */
        boolean holdScaffold();

        /** Turn toward {@code at} and place a scaffolding block there (over several ticks). */
        Result place(BlockPos at);

        /** A scaffolding block was put at {@code at} by the follower itself (pillaring). */
        void placed(BlockPos at);
    }

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

    /** Let the route pillar, bridge and dig ({@link PathFinder.Action}s, one per path step). */
    public PathFollower building(List<PathFinder.Action> actions, Scaffolder scaffolder) {
        this.actions = actions;
        this.scaffolder = scaffolder;
        return this;
    }

    /** In the middle of placing a pillar or bridge block (don't interrupt it). */
    public boolean buildingStep() {
        return this.buildTicks > 0 && this.index < this.path.size() && action(this.index) != PathFinder.Action.WALK;
    }

    private PathFinder.Action action(int i) {
        return this.actions == null || i >= this.actions.size() ? PathFinder.Action.WALK : this.actions.get(i);
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
        PathFinder.Action action = action(this.index);
        if (action != PathFinder.Action.WALK && this.scaffolder != null) {
            State s = tickBuildStep(client, world, player, input, target, action);
            if (s != null) {
                return s;
            }
        }
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

        // Dropping down to the next step: steer in the air toward the middle of it rather than holding
        // forward, or we sail past a narrow ledge (the side of a dome or sphere) into a long fall.
        if (!player.isOnGround() && !inFluid && pos.y > targetY + 0.3 && this.recovery == Recovery.NONE) {
            nudge(player, input, dx, dz);
            input.sneak = false;
            input.jump = false;
            return State.WALKING;
        }

        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90.0F;
        // Turn toward the next step like a player would (no snapping); walk once roughly facing it.
        Look.toward(player, yaw, 10.0F);
        float off = Math.abs(MathHelper.wrapDegrees(yaw - player.getYaw()));
        boolean facing = off < 50.0F;
        // Up on scaffolding or a narrow bridge, a step taken while still turning (or while backing off
        // to get unstuck) can go over the side. Sneak there - vanilla keeps a sneaking player from
        // walking off an edge - unless this step is meant to drop down.
        boolean dropping = targetY < pos.y - 0.5;
        this.edgeGuard = !dropping && !inFluid && player.isOnGround() && nearDrop(world, player.getBlockPos());

        if (this.recovery != Recovery.NONE) {
            applyRecovery(player, input);
            return State.WALKING;
        }

        boolean last = this.index == this.path.size() - 1;
        boolean careful = this.precise && last;
        input.back = false;
        input.left = false;
        input.right = false;
        input.forward = facing && horizontal > (careful ? 0.1 : 0.15);
        // Sneak the last bit of a precise stop so we don't overshoot (or slide off an edge).
        input.sneak = careful && horizontal < 0.8 || this.edgeGuard && off > 8.0F;
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

    /**
     * The block work of a pillar, bridge or dig step.
     *
     * @return the state to report, or null once the block is in place (or gone) and the step can be
     * walked like any other
     */
    @Nullable
    private State tickBuildStep(MinecraftClient client, ClientWorld world, ClientPlayerEntity player, MovementInput input,
                                BlockPos target, PathFinder.Action action) {
        switch (action) {
            case PILLAR -> {
                BlockPos base = target.down();
                if (!world.getBlockState(base).isReplaceable()) {
                    // Placed: land on it, then the step counts as reached.
                    input.release();
                    if (player.isOnGround()) {
                        this.buildTicks = 0;
                        this.windowTicks = 0;
                        return null;
                    }
                    return State.WALKING;
                }
                if (++this.buildTicks > 100) {
                    input.release();
                    return State.STUCK;
                }
                input.release();
                Vec3d pos = player.getPos();
                if (player.isOnGround()) {
                    double dx = base.getX() + 0.5 - pos.x;
                    double dz = base.getZ() + 0.5 - pos.z;
                    if (dx * dx + dz * dz > 0.15 * 0.15) {
                        // Get over the middle of the cell first (sneaking, so we don't step off).
                        nudge(player, input, dx, dz);
                        return State.WALKING;
                    }
                    if (!this.scaffolder.holdScaffold()) {
                        return State.STUCK;
                    }
                    // Look down at our feet (turning, not snapping), then jump.
                    if (Look.toward(player, player.getYaw(), 90.0F)) {
                        input.jump = true;
                    }
                    return State.WALKING;
                }
                BlockPos below = base.down();
                // Near the top of the jump: put the block where our feet just were.
                if (pos.y >= base.getY() + 1.0 && !world.getBlockState(below).isReplaceable()) {
                    BlockHitResult hit = new BlockHitResult(new Vec3d(base.getX() + 0.5, base.getY(), base.getZ() + 0.5),
                            Direction.UP, below, false);
                    Clicks.place(client, player, hit);
                    this.scaffolder.placed(base);
                }
                return State.WALKING;
            }
            case BRIDGE -> {
                BlockPos floor = target.down();
                if (!world.getBlockState(floor).isReplaceable()) {
                    this.buildTicks = 0;
                    this.windowTicks = 0;
                    return null;
                }
                input.release();
                if (++this.buildTicks > 100) {
                    return State.STUCK;
                }
                if (!player.isOnGround()) {
                    return State.WALKING;
                }
                input.sneak = true;
                return switch (this.scaffolder.place(floor)) {
                    case DONE, BUSY -> State.WALKING;
                    case FAILED -> State.STUCK;
                };
            }
            case DIG -> {
                BlockState s = world.getBlockState(target);
                if (s.isAir() || s.isReplaceable()) {
                    this.buildTicks = 0;
                    this.windowTicks = 0;
                    return null;
                }
                input.release();
                if (++this.buildTicks > 200) {
                    return State.STUCK;
                }
                if (player.isOnGround() && this.breaking == null) {
                    Inventories.holdBestTool(client, s);
                    this.breaking = target.toImmutable();
                    this.breakingSide = Direction.UP;
                    this.breakTicks = 0;
                    this.breakStarted = false;
                    this.digging = true;
                }
                return State.WALKING;
            }
            default -> {
                return null;
            }
        }
    }

    /** Press the keys that move us by (dx, dz), whichever way we're facing. Sneaks. */
    private static void nudge(ClientPlayerEntity player, MovementInput input, double dx, double dz) {
        float yaw = player.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        // The player's forward is (-sin, cos) and left is (cos, sin).
        double forward = -dx * MathHelper.sin(yaw) + dz * MathHelper.cos(yaw);
        double left = dx * MathHelper.cos(yaw) + dz * MathHelper.sin(yaw);
        input.forward = forward > 0.05;
        input.back = forward < -0.05;
        input.left = left > 0.05;
        input.right = left < -0.05;
        input.sneak = true;
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
        input.sneak = this.edgeGuard;
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
            this.breaking = p.toImmutable();
            this.breakTicks = 0;
            this.breakStarted = false;
            return true;
        }
        return false;
    }

    private void tickBreaking(MinecraftClient client) {
        BlockPos p = this.breaking;
        BlockState state = client.world.getBlockState(p);
        boolean gone = state.isAir() || (this.digging ? state.isReplaceable() : !soft(state));
        if (gone || ++this.breakTicks > BREAK_TIMEOUT_TICKS || !Reach.inReach(client, p)) {
            if (!gone && this.breakStarted && client.interactionManager != null) {
                client.interactionManager.cancelBlockBreaking();
            }
            this.breaking = null;
            this.digging = false;
            this.windowTicks = 0;
            return;
        }
        Vec3d face = Vec3d.ofCenter(p).add(this.breakingSide.getOffsetX() * 0.5, this.breakingSide.getOffsetY() * 0.5,
                this.breakingSide.getOffsetZ() * 0.5);
        if (!Look.at(client.player, face) && !this.breakStarted) {
            return; // still turning toward it
        }
        if (!this.breakStarted) {
            this.breakStarted = true;
            client.interactionManager.attackBlock(p, this.breakingSide);
            client.player.swingHand(Hand.MAIN_HAND);
        } else if (client.interactionManager != null && client.interactionManager.updateBlockBreakingProgress(p, this.breakingSide)) {
            client.player.swingHand(Hand.MAIN_HAND);
        }
    }

    /** A neighbouring cell has a drop of 3 or more blocks under it (a fall that hurts). */
    private static boolean nearDrop(ClientWorld world, BlockPos feet) {
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos c = feet.offset(d);
            if (!Walkability.passable(world, c)) {
                continue;
            }
            int air = 0;
            BlockPos below = c.down();
            while (air < 3 && Walkability.passable(world, below)) {
                air++;
                below = below.down();
            }
            if (air >= 3) {
                return true;
            }
        }
        return false;
    }

    /** Blocks that are quick to mine and never part of anything worth keeping in the way of a walk. */
    private static boolean soft(BlockState state) {
        Block b = state.getBlock();
        return b instanceof LeavesBlock || b instanceof BambooBlock || b instanceof SnowBlock || b instanceof CobwebBlock;
    }

    private boolean reached(ClientWorld world, ClientPlayerEntity player, int i) {
        BlockPos feet = this.path.get(i);
        PathFinder.Action a = action(i);
        if ((a == PathFinder.Action.PILLAR || a == PathFinder.Action.BRIDGE)
                && (world.getBlockState(feet.down()).isReplaceable() || !player.isOnGround())) {
            // Not there until the block we stand on exists and we're on it.
            return false;
        }
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
