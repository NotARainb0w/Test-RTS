package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.task.Reach;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** The built-in engine: our own pathfinding, placing and mining. Ticked every client tick. */
public final class NativeEngine {
    private static final NativeEngine INSTANCE = new NativeEngine();

    @Nullable
    private PathFinder.Search search;
    @Nullable
    private PathFollower walk;
    @Nullable
    private NativeBuilder builder;
    private String status = "Idle";

    public static NativeEngine get() {
        return INSTANCE;
    }

    public void tick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
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
                com.testrts.rtsclient.util.Messages.warn("No walkable path there (the built-in engine can't bridge or pillar)");
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
            if (s != PathFollower.State.WALKING) {
                this.status = s == PathFollower.State.ARRIVED ? "Arrived" : "Stuck";
                this.walk = null;
                MovementInput.restore(client);
            } else {
                this.status = "Walking";
            }
        }
    }

    /** @return false if no path exists. */
    public boolean goNextTo(MinecraftClient client, BlockPos pos) {
        double reach = Reach.reach(client) - 0.4;
        Vec3d center = Vec3d.ofCenter(pos);
        return startWalk(client, feet -> {
            if (feet.equals(pos) || feet.up().equals(pos)) {
                return false;
            }
            Vec3d eye = new Vec3d(feet.getX() + 0.5, feet.getY() + 1.62, feet.getZ() + 0.5);
            return eye.squaredDistanceTo(center) <= reach * reach;
        }, center);
    }

    public boolean goTo(MinecraftClient client, BlockPos feet) {
        return startWalk(client, p -> p.getSquaredDistance(feet) <= 1.0, Vec3d.ofBottomCenter(feet));
    }

    private boolean startWalk(MinecraftClient client, Predicate<BlockPos> goal, Vec3d toward) {
        stop(client);
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null) {
            return false;
        }
        this.search = PathFinder.start(client.world, player.getBlockPos(), goal, toward);
        this.status = "Planning";
        return true;
    }

    public void build(Map<BlockPos, BlockState> order, boolean strict, boolean layered) {
        stop(MinecraftClient.getInstance());
        this.builder = new NativeBuilder(order, strict, layered);
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

    public void resumeBuilder() {
        if (this.builder != null) {
            this.builder.resume();
        }
    }

    public boolean isBusy() {
        return this.walk != null || this.search != null || this.builder != null;
    }

    public void stop(MinecraftClient client) {
        if (this.builder != null) {
            this.builder.stop(client);
        }
        this.builder = null;
        this.walk = null;
        this.search = null;
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
