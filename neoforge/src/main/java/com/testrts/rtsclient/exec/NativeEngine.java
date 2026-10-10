package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.task.Reach;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** The built-in engine: our own pathfinding, placing and mining. Ticked every client tick. */
public final class NativeEngine {
    private static final NativeEngine INSTANCE = new NativeEngine();
    /** Times a walk re-plans around a step it got stuck on before giving up. */
    private static final int MAX_STUCK_REPATHS = 3;
    /** Times a walk re-plans after being knocked or falling off the route. */
    private static final int MAX_OFF_PATH_REPATHS = 5;

    @Nullable
    private PathFinder.Search search;
    @Nullable
    private PathFollower walk;
    @Nullable
    private NativeBuilder builder;
    private final java.util.Set<BlockPos> orphanScaffolds = new java.util.LinkedHashSet<>();
    private String status = "Idle";

    // The current walk request, kept so it can be planned again from wherever the player ends up.
    @Nullable
    private Predicate<BlockPos> goal;
    @Nullable
    private Vec3 toward;
    @Nullable
    private BlockPos destination;
    private int stuckRepaths;
    private int offPathRepaths;

    public static NativeEngine get() {
        return INSTANCE;
    }

    public void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            this.walk = null;
            this.search = null;
            this.builder = null;
            return;
        }
        if (this.search != null) {
            PathFinder.Search.State s = this.search.step(PathFinder.NODES_PER_TICK);
            if (s == PathFinder.Search.State.FOUND) {
                this.walk = new PathFollower(this.search.result());
                this.search = null;
            } else if (s == PathFinder.Search.State.FAILED) {
                this.search = null;
                this.status = "No path";
                MovementInput.restore(client);
                if (this.stuckRepaths + this.offPathRepaths > 0) {
                    Messages.warn("Stuck on the way to " + describe(this.destination) + " and found no other way round");
                } else {
                    Messages.warn("No walkable path there (the built-in engine can't bridge or pillar)");
                }
            } else {
                this.status = "Planning";
            }
            return;
        }
        if (this.builder != null) {
            if (!this.builder.tick(client)) {
                this.status = "Build " + this.builder.status();
                this.builder.stop(client);
                this.builder = null;
            } else {
                this.status = "Build: " + this.builder.status();
            }
            return;
        }
        if (this.walk != null) {
            PathFollower.State s = this.walk.tick(client);
            if (s == PathFollower.State.WALKING) {
                this.status = "Walking";
                return;
            }
            PathFollower failed = this.walk;
            this.walk = null;
            if (s == PathFollower.State.STUCK && replan(client, failed)) {
                return;
            }
            this.status = s == PathFollower.State.ARRIVED ? "Arrived" : "Stuck";
            MovementInput.restore(client);
            if (s == PathFollower.State.STUCK) {
                Messages.warn("Stuck on the way to " + describe(this.destination) + ", gave up");
            }
        }
    }

    /**
     * The follower couldn't go on: plan again from where the player is now, steering around the
     * step that failed. Gives up (returns false) after a few tries so callers can move on.
     */
    private boolean replan(Minecraft client, PathFollower failed) {
        LocalPlayer player = client.player;
        if (this.goal == null || player == null || client.level == null) {
            return false;
        }
        if (failed.offPath()) {
            if (++this.offPathRepaths > MAX_OFF_PATH_REPATHS) {
                return false;
            }
        } else {
            if (++this.stuckRepaths > MAX_STUCK_REPATHS) {
                return false;
            }
            PathFinder.reportStuck(failed.failedFrom(), failed.failedTo());
        }
        MovementInput.acquire(player).release();
        this.search = PathFinder.start(client.level, Walkability.feetNode(client.level, player), this.goal, this.toward);
        this.status = "Re-planning";
        return true;
    }

    private static String describe(@Nullable BlockPos pos) {
        return pos != null ? pos.toShortString() : "the target";
    }

    /** @return false if no path exists. */
    public boolean goNextTo(Minecraft client, BlockPos pos) {
        double reach = Reach.reach(client) - 0.4;
        Vec3 center = Vec3.atCenterOf(pos);
        return startWalk(client, feet -> {
            if (feet.equals(pos) || feet.above().equals(pos)) {
                return false;
            }
            // Eye height over the real floor: on a slab or carpet the eyes are lower than the node suggests.
            ClientLevel world = client.level;
            double eyeY = (world != null ? Walkability.standY(world, feet) : feet.getY()) + 1.62;
            Vec3 eye = new Vec3(feet.getX() + 0.5, eyeY, feet.getZ() + 0.5);
            return eye.distanceToSqr(center) <= reach * reach;
        }, center, pos);
    }

    public boolean goTo(Minecraft client, BlockPos feet) {
        return startWalk(client, p -> p.distSqr(feet) <= 1.0, Vec3.atBottomCenterOf(feet), feet);
    }

    private boolean startWalk(Minecraft client, Predicate<BlockPos> goal, Vec3 toward, BlockPos destination) {
        stop(client);
        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            return false;
        }
        this.goal = goal;
        this.toward = toward;
        this.destination = destination.immutable();
        this.stuckRepaths = 0;
        this.offPathRepaths = 0;
        this.search = PathFinder.start(client.level, Walkability.feetNode(client.level, player), goal, toward);
        this.status = "Planning";
        return true;
    }

    public void build(Map<BlockPos, BlockState> order, boolean strict, boolean layered) {
        stop(Minecraft.getInstance());
        // Scaffolding left by an interrupted build (e.g. paused to fetch blocks) is cleaned up by this one.
        java.util.Set<BlockPos> carry = new java.util.LinkedHashSet<>(this.orphanScaffolds);
        this.orphanScaffolds.clear();
        this.builder = new NativeBuilder(order, strict, layered, carry);
        this.status = "Building";
    }

    public boolean isMoving() {
        return this.walk != null || this.search != null;
    }

    public boolean isBuilding() {
        return this.builder != null;
    }

    public boolean isBuilderPaused() {
        return this.builder != null && this.builder.isPaused();
    }

    /** What the built-in builder is waiting for (a block or scaffolding), if paused. */
    @org.jetbrains.annotations.Nullable
    public net.minecraft.world.item.Item missingItem() {
        return this.builder != null && this.builder.isPaused() ? this.builder.missingItem() : null;
    }

    public void resumeBuilder() {
        if (this.builder != null) {
            this.builder.resume();
        }
    }

    public boolean isBusy() {
        return this.walk != null || this.search != null || this.builder != null;
    }

    public void stop(Minecraft client) {
        if (this.builder != null) {
            this.builder.stop(client);
            this.orphanScaffolds.addAll(this.builder.scaffolds());
        }
        this.builder = null;
        this.walk = null;
        this.search = null;
        this.goal = null;
        MovementInput.restore(client);
    }

    public String status() {
        return this.status;
    }

    /** The current path, for drawing. */
    @Nullable
    public List<BlockPos> currentPath() {
        return this.walk != null ? this.walk.path() : null;
    }
}
