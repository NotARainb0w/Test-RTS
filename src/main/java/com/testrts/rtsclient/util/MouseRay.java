package com.testrts.rtsclient.util;

import com.testrts.rtsclient.camera.RtsCamera;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.Nullable;

/** Turns a cursor position on screen into a world ray from the RTS camera. */
public final class MouseRay {
    public static final double MAX_DISTANCE = 256.0;

    private MouseRay() {
    }

    public static Vec3d direction(RtsCamera camera, double mouseX, double mouseY, int width, int height) {
        MinecraftClient client = MinecraftClient.getInstance();
        double fov = client.options.getFov().getValue();
        double tanHalf = Math.tan(Math.toRadians(fov) / 2.0);
        double aspect = (double) width / Math.max(1, height);
        double ndcX = 2.0 * mouseX / width - 1.0;
        double ndcY = 1.0 - 2.0 * mouseY / height;

        Vec3d forward = camera.lookVector();
        Vec3d worldUp = new Vec3d(0.0, 1.0, 0.0);
        Vec3d right = forward.crossProduct(worldUp);
        if (right.lengthSquared() < 1.0e-6) {
            // Looking straight down: derive "right" from yaw instead.
            right = RtsCamera.horizontalRight(camera.yaw());
        }
        right = right.normalize();
        Vec3d up = right.crossProduct(forward).normalize();

        return forward
                .add(right.multiply(ndcX * tanHalf * aspect))
                .add(up.multiply(ndcY * tanHalf))
                .normalize();
    }

    @Nullable
    public static BlockHitResult pick(RtsCamera camera, double mouseX, double mouseY, int width, int height, boolean includeFluids) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null || !camera.isEnabled()) {
            return null;
        }
        Vec3d start = camera.position();
        Vec3d end = start.add(direction(camera, mouseX, mouseY, width, height).multiply(MAX_DISTANCE));
        BlockHitResult hit = client.world.raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.OUTLINE,
                includeFluids ? RaycastContext.FluidHandling.SOURCE_ONLY : RaycastContext.FluidHandling.NONE,
                client.player));
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit;
    }
}
