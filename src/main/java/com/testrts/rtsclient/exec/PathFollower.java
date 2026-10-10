package com.testrts.rtsclient.exec;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/** Walks the player along a list of feet positions by pressing forward/jump and turning. */
public final class PathFollower {
    public enum State { WALKING, ARRIVED, STUCK }

    private static final int STUCK_TICKS = 60;

    private final List<BlockPos> path;
    private int index;
    private double bestDist = Double.MAX_VALUE;
    private int sinceProgress;

    public PathFollower(List<BlockPos> path) {
        this.path = path;
    }

    public List<BlockPos> path() {
        return this.path;
    }

    public int index() {
        return this.index;
    }

    public State tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return State.STUCK;
        }
        MovementInput input = MovementInput.acquire(player);
        // Skip nodes we're already standing on (or past).
        while (this.index < this.path.size() && reached(player, this.path.get(this.index))) {
            this.index++;
            this.bestDist = Double.MAX_VALUE;
            this.sinceProgress = 0;
        }
        if (this.index >= this.path.size()) {
            input.release();
            return State.ARRIVED;
        }
        BlockPos target = this.path.get(this.index);
        Vec3d center = new Vec3d(target.getX() + 0.5, target.getY(), target.getZ() + 0.5);
        Vec3d pos = player.getPos();
        double dx = center.x - pos.x;
        double dz = center.z - pos.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90.0F;
        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setPitch(10.0F);

        input.forward = horizontal > 0.15;
        boolean climb = target.getY() > MathHelper.floor(pos.y + 0.01);
        input.jump = player.isTouchingWater() || player.isInLava()
                || (player.isOnGround() && (climb && horizontal < 1.4 || player.horizontalCollision));
        input.sneak = false;

        double dist = horizontal + Math.abs(center.y - pos.y);
        if (dist < this.bestDist - 0.05) {
            this.bestDist = dist;
            this.sinceProgress = 0;
        } else if (++this.sinceProgress > STUCK_TICKS) {
            input.release();
            return State.STUCK;
        }
        return State.WALKING;
    }

    private static boolean reached(ClientPlayerEntity player, BlockPos feet) {
        Vec3d pos = player.getPos();
        double dx = feet.getX() + 0.5 - pos.x;
        double dz = feet.getZ() + 0.5 - pos.z;
        double dy = pos.y - feet.getY();
        return dx * dx + dz * dz < 0.35 * 0.35 && dy > -0.5 && dy < 1.0;
    }
}
