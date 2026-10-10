package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.ContainerTracker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/** Our own block interaction: reach checks and a "look at it, then right-click it" helper. */
public final class Reach {
    private Reach() {
    }

    public static double reach(MinecraftClient client) {
        // Stay a little inside the real reach so the server never rejects the click.
        return client.interactionManager != null ? client.interactionManager.getReachDistance() - 0.3 : 4.2;
    }

    public static boolean inReach(MinecraftClient client, BlockPos pos) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return false;
        }
        double r = reach(client);
        return player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(pos)) <= r * r;
    }

    /**
     * Turn toward the block and right-click it with the main hand.
     *
     * @param preferred the exact face/point the player clicked on screen, or null to use the
     *                  face pointing at the player
     */
    public static void interact(MinecraftClient client, BlockPos pos, @Nullable BlockHitResult preferred) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) {
            return;
        }
        Vec3d eye = player.getEyePos();
        Vec3d center = Vec3d.ofCenter(pos);
        Direction side;
        Vec3d hitVec;
        if (preferred != null && preferred.getBlockPos().equals(pos)) {
            side = preferred.getSide();
            hitVec = preferred.getPos();
        } else {
            side = Direction.getFacing(eye.x - center.x, eye.y - center.y, eye.z - center.z);
            hitVec = center.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5);
        }
        lookAt(player, hitVec);
        ContainerTracker.get().expect(pos);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, new BlockHitResult(hitVec, side, pos, false));
        player.swingHand(Hand.MAIN_HAND);
    }

    /** Set an exact rotation and tell the server about it right away. */
    public static void face(ClientPlayerEntity player, float yaw, float pitch) {
        player.setYaw(yaw);
        player.setPitch(pitch);
        player.setHeadYaw(yaw);
        player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, player.isOnGround()));
    }

    public static void lookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * MathHelper.DEGREES_PER_RADIAN);
        face(player, yaw, pitch);
    }
}
