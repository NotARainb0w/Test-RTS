package com.testrts.rtsclient.task;

import com.testrts.rtsclient.storage.ContainerTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Our own block interaction: reach checks and a "look at it, then right-click it" helper. */
public final class Reach {
    private Reach() {
    }

    public static double reach(Minecraft client) {
        // Stay a little inside the real reach so the server never rejects the click.
        // 1.20.5+: the reach is the player's block_interaction_range attribute (4.5, 5.0 in creative).
        return client.gameMode != null && client.player != null ? client.player.blockInteractionRange() - 0.3 : 4.2;
    }

    public static boolean inReach(Minecraft client, BlockPos pos) {
        LocalPlayer player = client.player;
        if (player == null) {
            return false;
        }
        double r = reach(client);
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos)) <= r * r;
    }

    /**
     * Turn toward the block and right-click it with the main hand.
     *
     * @param preferred the exact face/point the player clicked on screen, or null to use the
     *                  face pointing at the player
     */
    public static void interact(Minecraft client, BlockPos pos, @Nullable BlockHitResult preferred) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 center = Vec3.atCenterOf(pos);
        Direction side;
        Vec3 hitVec;
        if (preferred != null && preferred.getBlockPos().equals(pos)) {
            side = preferred.getDirection();
            hitVec = preferred.getLocation();
        } else {
            side = Direction.getNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
            hitVec = center.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5);
        }
        lookAt(player, hitVec);
        ContainerTracker.get().expect(pos);
        client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(hitVec, side, pos, false));
        player.swing(InteractionHand.MAIN_HAND);
    }

    /** Set an exact rotation and tell the server about it right away. */
    public static void face(LocalPlayer player, float yaw, float pitch) {
        player.setYRot(yaw);
        player.setXRot(pitch);
        player.setYHeadRot(yaw);
        player.connection.send(new ServerboundMovePlayerPacket.Rot(yaw, pitch, player.onGround()));
    }

    public static void lookAt(LocalPlayer player, Vec3 target) {
        Vec3 eye = player.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(dy, horizontal) * Mth.RAD_TO_DEG);
        face(player, yaw, pitch);
    }
}
