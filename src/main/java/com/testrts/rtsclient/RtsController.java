package com.testrts.rtsclient;

import com.testrts.rtsclient.baritone.BaritoneBridge;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.camera.RtsCamera;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.screen.RtsScreen;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.hit.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Owns RTS mode: turning it on/off, the detached camera, and keeping the RTS screen up. */
public final class RtsController {
    private static final RtsController INSTANCE = new RtsController();

    private final RtsCamera camera = new RtsCamera();
    private final BuildSession session = new BuildSession();
    private RtsScreen screen;
    private boolean active;
    private ClientWorld world;
    private ClientPlayerEntity player;
    @Nullable
    private BlockHitResult hover;

    public static RtsController get() {
        return INSTANCE;
    }

    public boolean isActive() {
        return this.active;
    }

    public RtsCamera camera() {
        return this.camera;
    }

    public BuildSession session() {
        return this.session;
    }

    /** Block under the cursor, as last computed by the RTS screen. */
    @Nullable
    public BlockHitResult hover() {
        return this.active ? this.hover : null;
    }

    public void setHover(@Nullable BlockHitResult hover) {
        this.hover = hover;
    }

    public void toggle(MinecraftClient client) {
        if (this.active) {
            disable(client);
        } else {
            enable(client);
        }
    }

    public void enable(MinecraftClient client) {
        if (this.active || client.player == null || client.world == null) {
            return;
        }
        if (client.player.isSpectator()) {
            Messages.warn("RTS mode isn't available in spectator mode");
            return;
        }
        this.active = true;
        this.world = client.world;
        this.player = client.player;
        this.camera.enable(client);
        if (!BaritoneBridge.isAvailable()) {
            Messages.warn("Baritone not found: you can look around and link storage, but not build or move");
        } else if (RtsConfig.get().manageBaritoneSettings) {
            BaritoneBridge.applySettings(RtsConfig.get().strictOrientation);
        }
        openScreen(client);
    }

    public void disable(MinecraftClient client) {
        if (!this.active) {
            return;
        }
        this.active = false;
        this.camera.disable(client);
        this.session.clearSelection();
        if (client.currentScreen instanceof RtsScreen) {
            client.setScreen(null);
        }
        this.world = null;
        this.player = null;
        this.hover = null;
    }

    private void openScreen(MinecraftClient client) {
        if (this.screen == null) {
            this.screen = new RtsScreen(this);
        }
        client.setScreen(this.screen);
    }

    public void tick(MinecraftClient client) {
        if (!this.active) {
            return;
        }
        ClientPlayerEntity p = client.player;
        if (p == null || client.world == null) {
            disable(client);
            return;
        }
        if (client.world != this.world) {
            // Dimension change or world switch: the old camera entity belongs to the old world.
            Messages.info("World changed - RTS mode closed");
            disable(client);
            return;
        }
        if (p != this.player) {
            this.player = p;
        }
        if (!p.isAlive()) {
            disable(client);
            return;
        }
        this.camera.ensureAttached(client);
        // A container, chat or inventory screen replaced ours; once it closes, come back.
        if (client.currentScreen == null) {
            openScreen(client);
        }
    }
}
