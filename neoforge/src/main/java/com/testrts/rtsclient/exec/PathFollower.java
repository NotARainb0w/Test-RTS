package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.task.Reach;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.WebBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
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
    /** Check the next steps are still walkable as we go (planned routes only, see {@link #routeChanged}). */
    private boolean watchRoute;
    /** Steps ahead checked by {@link #routeChanged}. */
    private static final int LOOKAHEAD = 6;

    // A parkour jump in progress (see tickParkour)
    @Nullable
    private BlockPos jumpTarget;
    private int jumpTicks;
    private boolean jumped;
    private boolean airborne;
    private boolean landed;
    /** Back up to the rear of the take-off block first, for a long jump. */
    private boolean runUp;

    /** Places the temporary blocks a route needs. Implemented by the builder. */
    public interface Scaffolder {
        enum Result { DONE, BUSY, FAILED }

        /** Put a scaffolding block in hand. */
        boolean holdScaffold();

        /** Turn toward {@code at} and place a scaffolding block there (over several ticks). */
        Result place(BlockPos at);

        /** A scaffolding block was put at {@code at} by the follower itself (pillaring). */
        void placed(BlockPos at);

        /** {@code was} at {@code at} is being mined to tunnel through (to be put back later). */
        default void dug(BlockPos at, BlockState was) {
        }
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
        this.watchRoute = true;
        return this;
    }

    /** How each step of a walking route is taken (walks, climbs, parkour jumps); also watches the route ahead. */
    public PathFollower steps(List<PathFinder.Action> actions) {
        this.actions = actions;
        this.watchRoute = true;
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

    public State tick(Minecraft client) {
        this.descending = false;
        State state = tickSteps(client);
        // Sneaking on scaffolding drops us through it: never, unless this step is meant to go down.
        if (client.player != null && client.level != null && !this.descending) {
            BlockPos at = client.player.blockPosition();
            if (client.level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING) || client.level.getBlockState(at.below()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)) {
                MovementInput.acquire(client.player).sneak = false;
            }
        }
        return state;
    }

    /** Set by a climb step going down (sneaking through scaffolding is then wanted). */
    private boolean descending;
    /** The climb step being worked on, the closest we got to its height, and ticks since that improved. */
    @Nullable
    private BlockPos climbTarget;
    private double climbBest;
    private int climbTicks;

    private State tickSteps(Minecraft client) {
        LocalPlayer player = client.player;
        ClientLevel world = client.level;
        if (player == null || world == null) {
            return State.STUCK;
        }
        MovementInput input = MovementInput.acquire(player);
        if (player.getAbilities().flying) {
            // Flying (a flight item in survival, or creative): the route is for walking. Fly over to the next
            // node and come down onto it (the game turns flight off once we touch the ground); just sinking
            // where we are would never land over the void or deep water.
            input.release();
            if (this.path.isEmpty() || ++this.ticks > this.path.size() * TICKS_PER_NODE + 200) {
                return State.STUCK;
            }
            BlockPos node = this.path.get(Math.min(this.index, this.path.size() - 1));
            Vec3 pos = player.position();
            double dx = node.getX() + 0.5 - pos.x;
            double dz = node.getZ() + 0.5 - pos.z;
            double dy = Walkability.standY(world, node) - pos.y;
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat > 0.3) {
                float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
                Look.toward(player, yaw, player.getXRot());
                float rad = player.getYRot() * Mth.DEG_TO_RAD;
                double forward = -dx * Mth.sin(rad) + dz * Mth.cos(rad);
                double left = dx * Mth.cos(rad) + dz * Mth.sin(rad);
                input.forward = forward > 0.15;
                input.back = forward < -0.15;
                input.left = left > 0.15;
                input.right = left < -0.15;
            }
            input.jump = dy > 0.3;
            // Stay up until over the node, so we don't drop into a gap on the way.
            input.sneak = dy < -0.2 && flat < 1.5;
            return State.WALKING;
        }
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
        // Straight up or down a ladder, vine or scaffolding column.
        BlockPos at = player.blockPosition();
        if (target.getX() == at.getX() && target.getZ() == at.getZ() && target.getY() != at.getY()
                && (Walkability.climbable(world, at) || Walkability.climbable(world, target) || Walkability.climbable(world, at.below()))) {
            return tickClimb(world, player, input, target);
        }
        // A block placed or mined on the route since it was planned (by us, another player, an enderman...):
        // plan again rather than walk into it or off it.
        if (this.watchRoute && this.ticks % 5 == 0 && routeChanged(world)) {
            this.offPath = true;
            input.release();
            return State.STUCK;
        }
        if (action == PathFinder.Action.PARKOUR) {
            State s = tickParkour(world, player, input, target);
            if (s != null) {
                return s;
            }
        }
        if (action != PathFinder.Action.WALK && this.scaffolder != null) {
            State s = tickBuildStep(client, world, player, input, target, action);
            if (s != null) {
                return s;
            }
        }
        double targetY = Walkability.standY(world, target);
        Vec3 pos = player.position();
        double dx = target.getX() + 0.5 - pos.x;
        double dz = target.getZ() + 0.5 - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        boolean inFluid = player.isInWater() || player.isInLava();

        // Knocked away or fell off the route: better to plan again from here than fight back.
        if (horizontal > OFF_PATH_DISTANCE || (player.onGround() && !inFluid && pos.y < targetY - 1.3)) {
            this.offPath = true;
            input.release();
            return State.STUCK;
        }

        // Dropping down to the next step: steer in the air toward the middle of it rather than holding
        // forward, or we sail past a narrow ledge (the side of a dome or sphere) into a long fall.
        if (!player.onGround() && !inFluid && pos.y > targetY + 0.3 && this.recovery == Recovery.NONE) {
            nudge(player, input, dx, dz);
            input.sneak = false;
            input.jump = false;
            return State.WALKING;
        }

        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        // Turn toward the next step like a player would (no snapping); walk once roughly facing it.
        Look.toward(player, yaw, 10.0F);
        float off = Math.abs(Mth.wrapDegrees(yaw - player.getYRot()));
        boolean facing = off < 50.0F;
        // Up on scaffolding or a narrow bridge, a step taken while still turning (or while backing off
        // to get unstuck) can go over the side. Sneak there - vanilla keeps a sneaking player from
        // walking off an edge - unless this step is meant to drop down.
        boolean dropping = targetY < pos.y - 0.5;
        this.edgeGuard = !dropping && !inFluid && player.onGround() && nearDrop(world, player.blockPosition());

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
        boolean bump = player.horizontalCollision && Walkability.passable(world, player.blockPosition().above(2));
        input.jump = inFluid || (player.onGround() && (climb && horizontal < 1.4 || bump));

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
    private State tickBuildStep(Minecraft client, ClientLevel world, LocalPlayer player, MovementInput input,
                                BlockPos target, PathFinder.Action action) {
        switch (action) {
            case PILLAR -> {
                BlockPos base = target.below();
                if (!world.getBlockState(base).canBeReplaced()) {
                    // Placed: land on it, then the step counts as reached.
                    input.release();
                    if (player.onGround()) {
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
                Vec3 pos = player.position();
                if (player.onGround()) {
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
                    if (Look.toward(player, player.getYRot(), 90.0F)) {
                        input.jump = true;
                    }
                    return State.WALKING;
                }
                BlockPos below = base.below();
                // Near the top of the jump: put the block where our feet just were.
                if (pos.y >= base.getY() + 1.0 && !world.getBlockState(below).canBeReplaced()) {
                    BlockHitResult hit = new BlockHitResult(new Vec3(base.getX() + 0.5, base.getY(), base.getZ() + 0.5),
                            Direction.UP, below, false);
                    Clicks.place(client, player, hit);
                    this.scaffolder.placed(base);
                }
                return State.WALKING;
            }
            case BRIDGE -> {
                BlockPos floor = target.below();
                if (!world.getBlockState(floor).canBeReplaced()) {
                    this.buildTicks = 0;
                    this.windowTicks = 0;
                    return null;
                }
                input.release();
                if (++this.buildTicks > 100) {
                    return State.STUCK;
                }
                if (!player.onGround()) {
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
                if (s.isAir() || s.canBeReplaced()) {
                    this.buildTicks = 0;
                    this.windowTicks = 0;
                    return null;
                }
                input.release();
                if (++this.buildTicks > 200) {
                    return State.STUCK;
                }
                if (player.onGround() && this.breaking == null) {
                    Inventories.holdBestTool(client, s);
                    this.breaking = target.immutable();
                    this.breakingSide = Direction.UP;
                    this.breakTicks = 0;
                    this.breakStarted = false;
                    this.digging = true;
                }
                return State.WALKING;
            }
            case TUNNEL -> {
                BlockPos from = this.index > 0 ? this.path.get(this.index - 1) : this.start;
                BlockPos cell = null;
                for (BlockPos c : PathFinder.tunnelCells(from, target)) {
                    if (!Walkability.passable(world, c)) {
                        cell = c;
                        break;
                    }
                }
                if (cell == null) {
                    // Through: walk the step like any other.
                    this.buildTicks = 0;
                    this.windowTicks = 0;
                    return null;
                }
                input.release();
                // Sand or gravel falling in keeps it busy a while; give up only if it never clears.
                if (++this.buildTicks > 400 || !Reach.inReach(client, cell)) {
                    return State.STUCK;
                }
                if (player.onGround()) {
                    BlockState s = world.getBlockState(cell);
                    this.scaffolder.dug(cell, s);
                    Inventories.holdBestTool(client, s);
                    Vec3 eye = player.getEyePosition();
                    Vec3 center = Vec3.atCenterOf(cell);
                    this.breakingSide = Direction.getNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
                    this.breaking = cell.immutable();
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

    /** One of the next few walking (or jumping) steps can't be stood on any more. */
    private boolean routeChanged(ClientLevel world) {
        int end = Math.min(this.path.size(), this.index + LOOKAHEAD);
        for (int i = this.index; i < end; i++) {
            PathFinder.Action a = action(i);
            if ((a == PathFinder.Action.WALK || a == PathFinder.Action.PARKOUR) && !Walkability.standable(world, this.path.get(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A parkour step: face the gap, back up first for a long jump, run (sprinting for gaps wider than one) and jump
     * off the edge, then steer onto the landing. A missed jump is reported as stuck on this step, so the next plan
     * goes another way.
     *
     * @return the state to report, or null once landed (the rest is walked like any step)
     */
    @Nullable
    private State tickParkour(ClientLevel world, LocalPlayer player, MovementInput input, BlockPos target) {
        BlockPos from = this.index > 0 ? this.path.get(this.index - 1) : this.start;
        if (!target.equals(this.jumpTarget)) {
            this.jumpTarget = target.immutable();
            this.jumpTicks = 0;
            this.jumped = false;
            this.airborne = false;
            this.landed = false;
            this.runUp = from.distSqr(target.atY(from.getY())) >= 3.5 * 3.5;
        }
        if (this.landed) {
            return null;
        }
        input.release();
        if (++this.jumpTicks > 100) {
            return State.STUCK;
        }
        // The jump's direction (straight or at an angle) and length, centre to centre.
        double jx = target.getX() - from.getX();
        double jz = target.getZ() - from.getZ();
        double length = Math.sqrt(jx * jx + jz * jz);
        double dx = jx / length;
        double dz = jz / length;
        // Where the take-off block ends along that direction: 0.5 straight out, 0.71 diagonally.
        double edge = 0.5 / Math.max(Math.abs(dx), Math.abs(dz));
        boolean sprint = length > 2.9;
        Vec3 pos = player.position();
        double fromY = Walkability.standY(world, from);
        double targetY = Walkability.standY(world, target);
        if (!this.jumped) {
            if (!player.onGround()) {
                if (pos.y < fromY - 0.5) {
                    this.offPath = true; // slipped off the edge before the jump
                    return State.STUCK;
                }
                return State.WALKING;
            }
            // How far past the middle of the take-off block we are, toward the gap, and how far off the line.
            double ox = pos.x - (from.getX() + 0.5);
            double oz = pos.z - (from.getZ() + 0.5);
            double along = ox * dx + oz * dz;
            double across = ox * dz - oz * dx;
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            boolean facing = Look.toward(player, yaw, 0.0F);
            if (this.runUp || Math.abs(across) > 0.2) {
                // Line up in the middle of the block (at its back for a long jump: a run-up).
                double back = this.runUp ? 0.25 : 0.0;
                double bx = from.getX() + 0.5 - dx * back - pos.x;
                double bz = from.getZ() + 0.5 - dz * back - pos.z;
                if (bx * bx + bz * bz > 0.08 * 0.08) {
                    nudge(player, input, bx, bz);
                    return State.WALKING;
                }
                this.runUp = false;
            }
            if (!facing) {
                return State.WALKING;
            }
            input.forward = true;
            player.setSprinting(sprint);
            // Jump near the edge: late enough to clear the gap, before the floor runs out (0.3 past the edge).
            if (along >= (length >= 3.5 ? edge : edge - 0.2)) {
                input.jump = true;
                this.jumped = true;
            }
            return State.WALKING;
        }
        if (!this.airborne) {
            // Jump pressed; we leave the ground next tick.
            this.airborne = !player.onGround();
            input.forward = true;
            input.jump = true;
            if (sprint) {
                player.setSprinting(true);
            }
            return State.WALKING;
        }
        if (player.onGround() || player.isInWater()) {
            if (pos.y < targetY - 0.5) {
                return State.STUCK; // fell short into the gap
            }
            this.landed = true;
            return null;
        }
        // In the air: keep going toward the landing, letting go near it so we don't sail past.
        double remaining = (target.getX() + 0.5 - pos.x) * dx + (target.getZ() + 0.5 - pos.z) * dz;
        input.forward = remaining > 0.6;
        input.back = remaining < 0.0;
        return State.WALKING;
    }

    /** Press the keys that move us by (dx, dz), whichever way we're facing. Sneaks. */
    /**
     * Climbing like a player: centred on the column, holding jump goes up a ladder, vine or scaffolding; down,
     * sneaking drops through scaffolding, and letting go slides down a ladder or vine.
     */
    private State tickClimb(ClientLevel world, LocalPlayer player, MovementInput input, BlockPos target) {
        input.release();
        var pos = player.position();
        double dx = target.getX() + 0.5 - pos.x;
        double dz = target.getZ() + 0.5 - pos.z;
        // Watchdog: a climb that gains no height (or loses none, going down) for 2 seconds is re-planned
        // instead of waiting for the route's own, much longer, timeout.
        double gap = Math.abs(target.getY() - pos.y);
        if (this.climbTarget == null || !this.climbTarget.equals(target)) {
            this.climbTarget = target.immutable();
            this.climbBest = gap;
            this.climbTicks = 0;
        } else if (gap < this.climbBest - 0.05) {
            this.climbBest = gap;
            this.climbTicks = 0;
        } else if (++this.climbTicks > 40) {
            this.climbTarget = null;
            input.release();
            return State.STUCK;
        }
        if (dx * dx + dz * dz > 0.25 * 0.25) {
            nudge(player, input, dx, dz);
        }
        BlockPos at = player.blockPosition();
        if (target.getY() > at.getY()) {
            input.sneak = false;
            input.jump = true;
        } else {
            this.descending = true;
            input.sneak = world.getBlockState(at).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING) || world.getBlockState(at.below()).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING);
        }
        return State.WALKING;
    }

    private static void nudge(LocalPlayer player, MovementInput input, double dx, double dz) {
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        // The player's forward is (-sin, cos) and left is (cos, sin).
        double forward = -dx * Mth.sin(yaw) + dz * Mth.cos(yaw);
        double left = dx * Mth.cos(yaw) + dz * Mth.sin(yaw);
        input.forward = forward > 0.05;
        input.back = forward < -0.05;
        input.left = left > 0.05;
        input.right = left < -0.05;
        input.sneak = true;
    }

    /** No progress for a whole window: try the next, stronger way of getting unstuck. */
    private State escalate(Minecraft client, LocalPlayer player, MovementInput input, BlockPos target) {
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

    private void applyRecovery(LocalPlayer player, MovementInput input) {
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
                input.jump = player.horizontalCollision && player.onGround();
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
    private boolean startClearing(Minecraft client, LocalPlayer player, BlockPos target) {
        ClientLevel world = client.level;
        if (client.gameMode == null) {
            return false;
        }
        BlockPos feet = player.blockPosition();
        Vec3 pos = player.position();
        Direction toward = Direction.getNearest(target.getX() + 0.5 - pos.x, 0.0, target.getZ() + 0.5 - pos.z);
        BlockPos ahead = feet.relative(toward);
        BlockPos[] candidates = {target, target.above(), ahead, ahead.above(), feet.above(2), target.above(2)};
        for (BlockPos p : candidates) {
            BlockState state = world.getBlockState(p);
            if (state.getCollisionShape(world, p).isEmpty() && !(state.getBlock() instanceof WebBlock)) {
                continue;
            }
            if (!soft(state) || state.getDestroySpeed(world, p) < 0 || !this.mayBreak.test(p) || !Reach.inReach(client, p)) {
                continue;
            }
            Inventories.holdBestTool(client, state);
            Vec3 eye = player.getEyePosition();
            Vec3 center = Vec3.atCenterOf(p);
            this.breakingSide = Direction.getNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
            this.breaking = p.immutable();
            this.breakTicks = 0;
            this.breakStarted = false;
            return true;
        }
        return false;
    }

    private void tickBreaking(Minecraft client) {
        BlockPos p = this.breaking;
        BlockState state = client.level.getBlockState(p);
        boolean gone = state.isAir() || (this.digging ? state.canBeReplaced() : !soft(state));
        if (gone || ++this.breakTicks > BREAK_TIMEOUT_TICKS || !Reach.inReach(client, p)) {
            if (!gone && this.breakStarted && client.gameMode != null) {
                client.gameMode.stopDestroyBlock();
            }
            this.breaking = null;
            this.digging = false;
            this.windowTicks = 0;
            return;
        }
        Vec3 face = Vec3.atCenterOf(p).add(this.breakingSide.getStepX() * 0.5, this.breakingSide.getStepY() * 0.5,
                this.breakingSide.getStepZ() * 0.5);
        boolean aimed = Look.at(client.player, face);
        if (!this.breakStarted && !Mining.aimed(client, face, aimed)) {
            return; // still turning toward it
        }
        Mining.skipPause(client);
        if (!this.breakStarted) {
            this.breakStarted = true;
            client.gameMode.startDestroyBlock(p, this.breakingSide);
            client.player.swing(InteractionHand.MAIN_HAND);
        } else if (client.gameMode != null && client.gameMode.continueDestroyBlock(p, this.breakingSide)) {
            client.player.swing(InteractionHand.MAIN_HAND);
        }
    }

    /** A neighbouring cell has a drop of 3 or more blocks under it (a fall that hurts). */
    private static boolean nearDrop(ClientLevel world, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            BlockPos c = feet.relative(d);
            if (!Walkability.passable(world, c)) {
                continue;
            }
            int air = 0;
            BlockPos below = c.below();
            while (air < 3 && Walkability.passable(world, below)) {
                air++;
                below = below.below();
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
        return b instanceof LeavesBlock || b instanceof BambooStalkBlock || b instanceof SnowLayerBlock || b instanceof WebBlock;
    }

    private boolean reached(ClientLevel world, LocalPlayer player, int i) {
        BlockPos feet = this.path.get(i);
        PathFinder.Action a = action(i);
        if ((a == PathFinder.Action.PILLAR || a == PathFinder.Action.BRIDGE)
                && (world.getBlockState(feet.below()).canBeReplaced() || !player.onGround())) {
            // Not there until the block we stand on exists and we're on it.
            return false;
        }
        boolean careful = this.precise && i == this.path.size() - 1;
        double radius = careful ? 0.18 : 0.35;
        Vec3 pos = player.position();
        double dx = feet.getX() + 0.5 - pos.x;
        double dz = feet.getZ() + 0.5 - pos.z;
        if (dx * dx + dz * dz >= radius * radius) {
            return false;
        }
        // Compare with where the feet really are on this floor (a slab or carpet is lower than the node).
        double dy = pos.y - Walkability.standY(world, feet);
        if (player.isInWater()) {
            // Swimming bobs up and down around the surface.
            return dy > -1.5 && dy < 1.2;
        }
        return dy > -0.5 && dy < 1.0;
    }
}
