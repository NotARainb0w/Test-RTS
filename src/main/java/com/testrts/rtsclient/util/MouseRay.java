package com.testrts.rtsclient.util;

import com.testrts.rtsclient.camera.RtsCamera;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import com.testrts.rtsclient.render.CutView;
import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.BlockView;
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
        ClientWorld world = client.world;
        if (world == null || client.player == null || !camera.isEnabled()) {
            return null;
        }
        Vec3d start = camera.position();
        Vec3d end = start.add(direction(camera, mouseX, mouseY, width, height).multiply(MAX_DISTANCE));
        // Walk the blocks along the ray ourselves so blocks hidden by the cut view can't be clicked.
        return BlockView.raycast(start, end, world, (w, pos) -> {
            if (CutView.hides(pos.getY())) {
                return null;
            }
            BlockState state = w.getBlockState(pos);
            BlockHitResult hit = w.raycastBlock(start, end, pos, state.getOutlineShape(w, pos), state);
            if (hit == null && includeFluids && !state.getFluidState().isEmpty() && state.getFluidState().isStill()) {
                hit = w.raycastBlock(start, end, pos, state.getFluidState().getShape(w, pos), state);
            }
            return hit;
        }, w -> null);
    }

    /**
     * Where the cursor ray crosses the block layer at {@code y} (through the middle of the layer),
     * as a hit on that cell even when it's empty air. Null when the ray runs parallel or away from it.
     */
    @Nullable
    public static BlockHitResult pickLayer(RtsCamera camera, double mouseX, double mouseY, int width, int height, int y) {
        if (!camera.isEnabled()) {
            return null;
        }
        Vec3d start = camera.position();
        Vec3d dir = direction(camera, mouseX, mouseY, width, height);
        if (Math.abs(dir.y) < 1.0e-4) {
            return null;
        }
        double t = (y + 0.5 - start.y) / dir.y;
        if (t <= 0.0 || t > MAX_DISTANCE) {
            return null;
        }
        Vec3d at = start.add(dir.multiply(t));
        BlockPos cell = BlockPos.ofFloored(at.x, y, at.z);
        return new BlockHitResult(at, dir.y < 0 ? Direction.UP : Direction.DOWN, cell, false);
    }
}
