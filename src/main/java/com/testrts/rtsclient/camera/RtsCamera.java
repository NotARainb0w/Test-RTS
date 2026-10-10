package com.testrts.rtsclient.camera;

import com.testrts.rtsclient.config.RtsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MarkerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * A detached top-down camera. The view is rendered from a client-only marker entity that is
 * never added to the world; the player's body stays where it is and is driven by Baritone.
 */
public final class RtsCamera {
    private static final float MIN_PITCH = -10.0F;
    private static final float MAX_PITCH = 89.9F;

    private MarkerEntity entity;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private double velX;
    private double velY;
    private double velZ;

    private Perspective previousPerspective = Perspective.FIRST_PERSON;
    private boolean previousHudHidden;

    public boolean isEnabled() {
        return this.entity != null;
    }

    public void enable(MinecraftClient client) {
        PlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return;
        }
        GameOptions options = client.options;
        this.previousPerspective = options.getPerspective();
        this.previousHudHidden = options.hudHidden;

        this.yaw = player.getYaw();
        this.pitch = 55.0F;
        Vec3d eye = player.getEyePos();
        // Start above and behind the player, looking down at them.
        Vec3d back = horizontalForward(this.yaw).multiply(-8.0);
        this.x = eye.x + back.x;
        this.y = eye.y + 11.0;
        this.z = eye.z + back.z;
        this.velX = this.velY = this.velZ = 0.0;

        this.entity = new MarkerEntity(EntityType.MARKER, world);
        this.entity.noClip = true;
        apply();

        client.setCameraEntity(this.entity);
        options.setPerspective(Perspective.FIRST_PERSON);
        options.hudHidden = true;
        client.gameRenderer.setRenderHand(false);
    }

    public void disable(MinecraftClient client) {
        if (this.entity == null) {
            return;
        }
        this.entity = null;
        if (client.player != null) {
            client.setCameraEntity(client.player);
        }
        client.options.setPerspective(this.previousPerspective);
        client.options.hudHidden = this.previousHudHidden;
        client.gameRenderer.setRenderHand(true);
    }

    /** Re-attach after something (respawn, another mod) swapped the camera entity. */
    public void ensureAttached(MinecraftClient client) {
        if (this.entity == null || client.world == null) {
            return;
        }
        if (this.entity.getWorld() != client.world) {
            this.entity = new MarkerEntity(EntityType.MARKER, client.world);
            this.entity.noClip = true;
            apply();
        }
        if (client.getCameraEntity() != this.entity) {
            client.setCameraEntity(this.entity);
        }
        client.options.hudHidden = true;
        client.gameRenderer.setRenderHand(false);
    }

    /**
     * Integrates movement for one rendered frame.
     *
     * @param forward  -1..1 along the horizontal view direction
     * @param strafe   -1..1, positive is screen-right
     * @param vertical -1..1, positive is up
     */
    public void update(MinecraftClient client, double dt, double forward, double strafe, double vertical, boolean fast) {
        if (this.entity == null) {
            return;
        }
        dt = MathHelper.clamp(dt, 0.0, 0.1);
        double speed = RtsConfig.get().cameraSpeed * (fast ? 2.5 : 1.0);
        // Pan faster when zoomed out so the screen-space speed feels constant.
        speed *= MathHelper.clamp(heightAboveGround(client) / 12.0, 0.5, 3.0);

        Vec3d f = horizontalForward(this.yaw);
        Vec3d r = horizontalRight(this.yaw);
        double tx = (f.x * forward + r.x * strafe) * speed;
        double tz = (f.z * forward + r.z * strafe) * speed;
        double ty = vertical * speed;

        double blend = 1.0 - Math.exp(-dt * 14.0);
        this.velX += (tx - this.velX) * blend;
        this.velY += (ty - this.velY) * blend;
        this.velZ += (tz - this.velZ) * blend;

        moveClipped(client, this.velX * dt, this.velY * dt, this.velZ * dt);
        apply();
    }

    public void zoom(MinecraftClient client, double notches) {
        if (this.entity == null) {
            return;
        }
        Vec3d look = lookVector();
        double step = notches * RtsConfig.get().zoomStep * MathHelper.clamp(heightAboveGround(client) / 16.0, 0.6, 4.0);
        moveClipped(client, look.x * step, look.y * step, look.z * step);
        apply();
    }

    /** Pan by a screen-space drag, scaled so the ground roughly follows the cursor. */
    public void dragPan(MinecraftClient client, double dxPixels, double dyPixels, int screenHeight) {
        if (this.entity == null) {
            return;
        }
        double unitsPerPixel = Math.max(4.0, heightAboveGround(client)) * 1.6 / Math.max(1, screenHeight);
        Vec3d f = horizontalForward(this.yaw);
        Vec3d r = horizontalRight(this.yaw);
        double mx = (-r.x * dxPixels + f.x * dyPixels) * unitsPerPixel;
        double mz = (-r.z * dxPixels + f.z * dyPixels) * unitsPerPixel;
        moveClipped(client, mx, 0.0, mz);
        apply();
    }

    public void rotate(double dxPixels, double dyPixels) {
        float sens = RtsConfig.get().rotateSensitivity;
        this.yaw += (float) (dxPixels * sens);
        this.pitch = MathHelper.clamp(this.pitch + (float) (dyPixels * sens), MIN_PITCH, MAX_PITCH);
        apply();
    }

    public void rotateYaw(float degrees) {
        this.yaw += degrees;
        apply();
    }

    private void moveClipped(MinecraftClient client, double dx, double dy, double dz) {
        ClientWorld world = client.world;
        if (world == null) {
            return;
        }
        // Move one axis at a time; with camera collision on, refuse to enter opaque blocks.
        if (dx != 0.0 && isFree(world, this.x + dx, this.y, this.z)) {
            this.x += dx;
        } else {
            this.velX = 0.0;
        }
        if (dy != 0.0 && isFree(world, this.x, this.y + dy, this.z)) {
            this.y += dy;
        } else {
            this.velY = 0.0;
        }
        if (dz != 0.0 && isFree(world, this.x, this.y, this.z + dz)) {
            this.z += dz;
        } else {
            this.velZ = 0.0;
        }
        this.y = MathHelper.clamp(this.y, world.getBottomY() - 16.0, world.getTopY() + 96.0);
        clampToPlayer(client);
    }

    private static boolean isFree(ClientWorld world, double px, double py, double pz) {
        if (!RtsConfig.get().cameraCollision) {
            return true;
        }
        BlockPos pos = BlockPos.ofFloored(px, py, pz);
        return !world.getBlockState(pos).isOpaqueFullCube(world, pos);
    }

    private void clampToPlayer(MinecraftClient client) {
        if (client.player == null) {
            return;
        }
        Vec3d eye = client.player.getEyePos();
        double max = RtsConfig.get().maxCameraDistance;
        double dx = this.x - eye.x;
        double dy = this.y - eye.y;
        double dz = this.z - eye.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (dist > max) {
            double s = max / dist;
            this.x = eye.x + dx * s;
            this.y = eye.y + dy * s;
            this.z = eye.z + dz * s;
        }
    }

    private double heightAboveGround(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null) {
            return 12.0;
        }
        BlockPos.Mutable pos = new BlockPos.Mutable(MathHelper.floor(this.x), MathHelper.floor(this.y), MathHelper.floor(this.z));
        int start = pos.getY();
        for (int i = 0; i < 96 && pos.getY() > world.getBottomY(); i++) {
            if (!world.getBlockState(pos).isAir()) {
                return Math.max(1.0, this.y - pos.getY());
            }
            pos.move(0, -1, 0);
        }
        return Math.max(1.0, this.y - (start - 96));
    }

    private void apply() {
        if (this.entity == null) {
            return;
        }
        this.entity.refreshPositionAndAngles(this.x, this.y, this.z, this.yaw, this.pitch);
        // Keep previous == current so render interpolation never lags a frame behind.
        this.entity.prevX = this.entity.lastRenderX = this.x;
        this.entity.prevY = this.entity.lastRenderY = this.y;
        this.entity.prevZ = this.entity.lastRenderZ = this.z;
        this.entity.prevYaw = this.yaw;
        this.entity.prevPitch = this.pitch;
    }

    /** A saved camera position. */
    public record Pose(double x, double y, double z, float yaw, float pitch) {
    }

    public Pose pose() {
        return new Pose(this.x, this.y, this.z, this.yaw, this.pitch);
    }

    public void setPose(MinecraftClient client, Pose pose) {
        this.x = pose.x();
        this.y = pose.y();
        this.z = pose.z();
        this.yaw = pose.yaw();
        this.pitch = pose.pitch();
        this.velX = this.velY = this.velZ = 0.0;
        clampToPlayer(client);
        apply();
    }

    public Vec3d position() {
        return new Vec3d(this.x, this.y, this.z);
    }

    public float yaw() {
        return this.yaw;
    }

    public float pitch() {
        return this.pitch;
    }

    public Vec3d lookVector() {
        return Vec3d.fromPolar(this.pitch, this.yaw);
    }

    public static Vec3d horizontalForward(float yaw) {
        float rad = yaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-MathHelper.sin(rad), 0.0, MathHelper.cos(rad));
    }

    public static Vec3d horizontalRight(float yaw) {
        float rad = yaw * MathHelper.RADIANS_PER_DEGREE;
        return new Vec3d(-MathHelper.cos(rad), 0.0, -MathHelper.sin(rad));
    }
}
