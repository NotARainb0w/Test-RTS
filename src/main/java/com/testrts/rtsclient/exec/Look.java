package com.testrts.rtsclient.exec;

import com.testrts.rtsclient.config.RtsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

/**
 * Turns the player's head toward what it works on at a limited speed, like moving the mouse,
 * instead of snapping to a new angle for every block (which looks like a cheat to other players
 * and to anti-cheat plugins).
 * <p>
 * Callers ask {@link #toward} every tick and act only once it returns true. It returns true only
 * when the player already faced the target at the start of the tick, so the server has received
 * that rotation (vanilla sends it with the player's movement) before the click arrives.
 */
public final class Look {
    /** Close enough to count as facing the target (the player may be moving a little). */
    private static final float AIMED = 1.0F;
    /** Within this many degrees the last bit is closed in one go (too small to see). */
    private static final float SETTLE = 5.0F;
    /** Slowest turn per tick, so the last part of a turn doesn't crawl. */
    private static final float MIN_STEP = 8.0F;

    @Nullable
    private static Pending pending;

    private record Pending(Vec3d target, Runnable action, int deadline) {
    }

    private Look() {
    }

    /**
     * Turn one step toward {@code yaw}/{@code pitch}.
     *
     * @return true when already facing it, i.e. it's safe to click now
     */
    public static boolean toward(ClientPlayerEntity player, float yaw, float pitch) {
        float speed = (float) RtsConfig.get().turnSpeed;
        float dYaw = MathHelper.wrapDegrees(yaw - player.getYaw());
        float dPitch = pitch - player.getPitch();
        if (Math.abs(dYaw) <= AIMED && Math.abs(dPitch) <= AIMED) {
            return true;
        }
        if (speed <= 0.0F) {
            // Instant turning (old behaviour, setting turnSpeed 0).
            com.testrts.rtsclient.task.Reach.face(player, yaw, pitch);
            return true;
        }
        float dist = (float) Math.sqrt(dYaw * dYaw + dPitch * dPitch);
        float step = dist <= SETTLE ? dist : MathHelper.clamp(dist * 0.7F, MIN_STEP, speed);
        float f = step / dist;
        set(player, player.getYaw() + dYaw * f, player.getPitch() + dPitch * f);
        return false;
    }

    /** {@link #toward} for a point in the world. */
    public static boolean at(ClientPlayerEntity player, Vec3d target) {
        float[] r = anglesTo(player, target);
        return toward(player, r[0], r[1]);
    }

    /** Whether the player already looks within {@code degrees} of {@code target} (no turning). */
    public static boolean roughly(ClientPlayerEntity player, Vec3d target, float degrees) {
        float[] r = anglesTo(player, target);
        float dYaw = MathHelper.wrapDegrees(r[0] - player.getYaw());
        float dPitch = r[1] - player.getPitch();
        return dYaw * dYaw + dPitch * dPitch <= degrees * degrees;
    }

    /** Yaw and pitch that look from the player's eyes at {@code target}. */
    public static float[] anglesTo(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * MathHelper.DEGREES_PER_RADIAN) - 90.0F;
        float pitch = (float) -(MathHelper.atan2(dy, horizontal) * MathHelper.DEGREES_PER_RADIAN);
        return new float[]{MathHelper.wrapDegrees(yaw), MathHelper.clamp(pitch, -90.0F, 90.0F)};
    }

    /**
     * For one-off clicks (opening a chest, planting a sapling): turn toward {@code target} over
     * the next ticks and run {@code action} once facing it. Replaces any earlier pending click.
     */
    public static void thenDo(MinecraftClient client, Vec3d target, Runnable action) {
        if (client.player != null && at(client.player, target)) {
            action.run();
            pending = null;
            return;
        }
        pending = new Pending(target, action, 40);
    }

    /** Called every client tick. */
    public static void tick(MinecraftClient client) {
        Pending p = pending;
        if (p == null) {
            return;
        }
        if (client.player == null || p.deadline() <= 0) {
            pending = null;
            return;
        }
        pending = new Pending(p.target(), p.action(), p.deadline() - 1);
        if (at(client.player, p.target())) {
            pending = null;
            p.action().run();
        }
    }

    public static void cancel() {
        pending = null;
    }

    private static void set(ClientPlayerEntity player, float yaw, float pitch) {
        player.setYaw(yaw);
        player.setPitch(MathHelper.clamp(pitch, -90.0F, 90.0F));
        player.setHeadYaw(yaw);
    }
}
