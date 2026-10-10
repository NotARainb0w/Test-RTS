package com.testrts.rtsclient.util;

import com.testrts.rtsclient.camera.RtsCamera;
import com.testrts.rtsclient.render.CutView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/** Turns a cursor position on screen into a world ray from the RTS camera. */
public final class MouseRay {
    public static final double MAX_DISTANCE = 256.0;

    private MouseRay() {
    }

    public static Vec3 direction(RtsCamera camera, double mouseX, double mouseY, int width, int height) {
        Minecraft client = Minecraft.getInstance();
        double fov = client.options.fov().get();
        double tanHalf = Math.tan(Math.toRadians(fov) / 2.0);
        double aspect = (double) width / Math.max(1, height);
        double ndcX = 2.0 * mouseX / width - 1.0;
        double ndcY = 1.0 - 2.0 * mouseY / height;

        Vec3 forward = camera.lookVector();
        Vec3 worldUp = new Vec3(0.0, 1.0, 0.0);
        Vec3 right = forward.cross(worldUp);
        if (right.lengthSqr() < 1.0e-6) {
            // Looking straight down: derive "right" from yaw instead.
            right = RtsCamera.horizontalRight(camera.yaw());
        }
        right = right.normalize();
        Vec3 up = right.cross(forward).normalize();

        return forward
                .add(right.scale(ndcX * tanHalf * aspect))
                .add(up.scale(ndcY * tanHalf))
                .normalize();
    }

    @Nullable
    public static BlockHitResult pick(RtsCamera camera, double mouseX, double mouseY, int width, int height, boolean includeFluids) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel world = client.level;
        if (world == null || client.player == null || !camera.isEnabled()) {
            return null;
        }
        Vec3 start = camera.position();
        Vec3 end = start.add(direction(camera, mouseX, mouseY, width, height).scale(MAX_DISTANCE));
        // Walk the blocks along the ray ourselves so blocks hidden by the cut view can't be clicked.
        return BlockGetter.traverseBlocks(start, end, world, (w, pos) -> {
            if (CutView.hides(pos.getY())) {
                return null;
            }
            BlockState state = w.getBlockState(pos);
            BlockHitResult hit = w.clipWithInteractionOverride(start, end, pos, state.getShape(w, pos), state);
            if (hit == null && includeFluids && !state.getFluidState().isEmpty() && state.getFluidState().isSource()) {
                hit = w.clipWithInteractionOverride(start, end, pos, state.getFluidState().getShape(w, pos), state);
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
        Vec3 start = camera.position();
        Vec3 dir = direction(camera, mouseX, mouseY, width, height);
        if (Math.abs(dir.y) < 1.0e-4) {
            return null;
        }
        double t = (y + 0.5 - start.y) / dir.y;
        if (t <= 0.0 || t > MAX_DISTANCE) {
            return null;
        }
        Vec3 at = start.add(dir.scale(t));
        BlockPos cell = BlockPos.containing(at.x, y, at.z);
        return new BlockHitResult(at, dir.y < 0 ? Direction.UP : Direction.DOWN, cell, false);
    }
}
