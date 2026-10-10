package com.testrts.rtsclient;

import com.testrts.rtsclient.baritone.BaritoneBridge;
import com.testrts.rtsclient.build.BuildSession;
import com.testrts.rtsclient.camera.RtsCamera;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.screen.RtsScreen;
import com.testrts.rtsclient.util.Messages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Owns RTS mode: turning it on/off, the detached camera, and keeping the RTS screen up. */
public final class RtsController {
    private static final RtsController INSTANCE = new RtsController();

    private final RtsCamera camera = new RtsCamera();
    private final BuildSession session = new BuildSession();
    private RtsScreen screen;
    private boolean active;
    private ClientLevel world;
    private LocalPlayer player;
    @Nullable
    private BlockHitResult hover;
    @Nullable
    private Integer cutY;

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

    /** Y level above which the world is hidden ("cut view"), or null when off. */
    @Nullable
    public Integer cutY() {
        return this.active ? this.cutY : null;
    }

    public void setCut(Minecraft client, @Nullable Integer y) {
        if (java.util.Objects.equals(this.cutY, y)) {
            return;
        }
        this.cutY = y;
        com.testrts.rtsclient.render.CutView.set(client, this.active ? y : null);
    }

    public void toggle(Minecraft client) {
        if (this.active) {
            disable(client);
        } else {
            enable(client);
        }
    }

    public void enable(Minecraft client) {
        if (this.active || client.player == null || client.level == null) {
            return;
        }
        if (client.player.isSpectator()) {
            Messages.warn("RTS mode isn't available in spectator mode");
            return;
        }
        this.active = true;
        this.world = client.level;
        this.player = client.player;
        this.camera.enable(client);
        if (!BaritoneBridge.isAvailable()) {
            Messages.info("Baritone not found - using the built-in engine for walking, building and mining");
        } else if (RtsConfig.get().manageBaritoneSettings) {
            BaritoneBridge.applySettings(RtsConfig.get().strictOrientation);
        }
        openScreen(client);
    }

    public void disable(Minecraft client) {
        if (!this.active) {
            return;
        }
        this.active = false;
        com.testrts.rtsclient.render.CutView.set(client, null);
        this.cutY = null;
        this.camera.disable(client);
        this.session.clearSelection();
        if (client.screen instanceof RtsScreen) {
            client.setScreen(null);
        }
        this.world = null;
        this.player = null;
        this.hover = null;
    }

    private void openScreen(Minecraft client) {
        if (this.screen == null) {
            this.screen = new RtsScreen(this);
        }
        client.setScreen(this.screen);
    }

    public void tick(Minecraft client) {
        if (!this.active) {
            return;
        }
        LocalPlayer p = client.player;
        if (p == null || client.level == null) {
            disable(client);
            return;
        }
        if (client.level != this.world) {
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
        if (client.screen == null) {
            openScreen(client);
        }
    }
}
