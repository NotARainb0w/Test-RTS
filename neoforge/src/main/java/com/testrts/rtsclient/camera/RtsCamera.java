package com.testrts.rtsclient.camera;

import com.testrts.rtsclient.config.RtsConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * The RTS view: a free camera position and rotation. The player stays the game's camera entity and
 * no game setting is touched; while RTS mode is on, CameraMixin moves Minecraft's camera here each
 * frame, so leaving RTS mode leaves nothing behind.
 */
public final class RtsCamera {
    private static final float MIN_PITCH = -10.0F;
    private static final float MAX_PITCH = 89.9F;

    private boolean enabled;
    private double x;
    private double y;
    private double z;
    private float yaw;
    private float pitch;
    private double velX;
    private double velY;
    private double velZ;

    public boolean isEnabled() {
        return this.enabled;
    }

    /**
     * Start the RTS view above and behind the player. Nothing in the game is changed: no settings
     * (view mode, HUD), no camera entity. While RTS mode is on, mixins read the view from here
     * (CameraMixin), report the third-person front view (OptionsMixin) and skip the HUD (GuiMixin);
     * when it's off there is nothing to put back.
     */
    public void enable(Minecraft client) {
        Player player = client.player;
        if (player == null || client.level == null) {
            return;
        }
        this.yaw = player.getYRot();
        this.pitch = 55.0F;
        Vec3 eye = player.getEyePosition();
        Vec3 back = horizontalForward(this.yaw).scale(-8.0);
        this.x = eye.x + back.x;
        this.y = eye.y + 11.0;
        this.z = eye.z + back.z;
        this.velX = this.velY = this.velZ = 0.0;
        this.enabled = true;
    }

    public void disable(Minecraft client) {
        this.enabled = false;
    }

    /**
     * Integrates movement for one rendered frame.
     *
     * @param forward  -1..1 along the horizontal view direction
     * @param strafe   -1..1, positive is screen-right
     * @param vertical -1..1, positive is up
     */
    public void update(Minecraft client, double dt, double forward, double strafe, double vertical, boolean fast) {
        if (!this.enabled) {
            return;
        }
        dt = Mth.clamp(dt, 0.0, 0.1);
        double speed = RtsConfig.get().cameraSpeed * (fast ? 2.5 : 1.0);
        // Pan faster when zoomed out so the screen-space speed feels constant.
        speed *= Mth.clamp(heightAboveGround(client) / 12.0, 0.5, 3.0);

        Vec3 f = horizontalForward(this.yaw);
        Vec3 r = horizontalRight(this.yaw);
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

    public void zoom(Minecraft client, double notches) {
        if (!this.enabled) {
            return;
        }
        Vec3 look = lookVector();
        double step = notches * RtsConfig.get().zoomStep * Mth.clamp(heightAboveGround(client) / 16.0, 0.6, 4.0);
        moveClipped(client, look.x * step, look.y * step, look.z * step);
        apply();
    }

    /** Pan by a screen-space drag, scaled so the ground roughly follows the cursor. */
    public void dragPan(Minecraft client, double dxPixels, double dyPixels, int screenHeight) {
        if (!this.enabled) {
            return;
        }
        double unitsPerPixel = Math.max(4.0, heightAboveGround(client)) * 1.6 / Math.max(1, screenHeight);
        Vec3 f = horizontalForward(this.yaw);
        Vec3 r = horizontalRight(this.yaw);
        double mx = (-r.x * dxPixels + f.x * dyPixels) * unitsPerPixel;
        double mz = (-r.z * dxPixels + f.z * dyPixels) * unitsPerPixel;
        moveClipped(client, mx, 0.0, mz);
        apply();
    }

    public void rotate(double dxPixels, double dyPixels) {
        float sens = RtsConfig.get().rotateSensitivity;
        this.yaw += (float) (dxPixels * sens);
        this.pitch = Mth.clamp(this.pitch + (float) (dyPixels * sens), MIN_PITCH, MAX_PITCH);
        apply();
    }

    public void rotateYaw(float degrees) {
        this.yaw += degrees;
        apply();
    }

    private void moveClipped(Minecraft client, double dx, double dy, double dz) {
        ClientLevel world = client.level;
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
        this.y = Mth.clamp(this.y, world.getMinBuildHeight() - 16.0, world.getMaxBuildHeight() + 96.0);
        clampToPlayer(client);
    }

    private static boolean isFree(ClientLevel world, double px, double py, double pz) {
        if (!RtsConfig.get().cameraCollision) {
            return true;
        }
        BlockPos pos = BlockPos.containing(px, py, pz);
        return !world.getBlockState(pos).isSolidRender(world, pos);
    }

    private void clampToPlayer(Minecraft client) {
        if (client.player == null) {
            return;
        }
        Vec3 eye = client.player.getEyePosition();
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

    private double heightAboveGround(Minecraft client) {
        ClientLevel world = client.level;
        if (world == null) {
            return 12.0;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(Mth.floor(this.x), Mth.floor(this.y), Mth.floor(this.z));
        int start = pos.getY();
        for (int i = 0; i < 96 && pos.getY() > world.getMinBuildHeight(); i++) {
            if (!world.getBlockState(pos).isAir()) {
                return Math.max(1.0, this.y - pos.getY());
            }
            pos.move(0, -1, 0);
        }
        return Math.max(1.0, this.y - (start - 96));
    }

    /** Nothing to push anywhere: CameraMixin reads the position and rotation every frame. */
    private void apply() {
    }

    /** A saved camera position. */
    public record Pose(double x, double y, double z, float yaw, float pitch) {
    }

    public Pose pose() {
        return new Pose(this.x, this.y, this.z, this.yaw, this.pitch);
    }

    public void setPose(Minecraft client, Pose pose) {
        this.x = pose.x();
        this.y = pose.y();
        this.z = pose.z();
        this.yaw = pose.yaw();
        this.pitch = pose.pitch();
        this.velX = this.velY = this.velZ = 0.0;
        clampToPlayer(client);
        apply();
    }

    public Vec3 position() {
        return new Vec3(this.x, this.y, this.z);
    }

    public float yaw() {
        return this.yaw;
    }

    public float pitch() {
        return this.pitch;
    }

    public Vec3 lookVector() {
        return Vec3.directionFromRotation(this.pitch, this.yaw);
    }

    public static Vec3 horizontalForward(float yaw) {
        float rad = yaw * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.sin(rad), 0.0, Mth.cos(rad));
    }

    public static Vec3 horizontalRight(float yaw) {
        float rad = yaw * Mth.DEG_TO_RAD;
        return new Vec3(-Mth.cos(rad), 0.0, -Mth.sin(rad));
    }
}
