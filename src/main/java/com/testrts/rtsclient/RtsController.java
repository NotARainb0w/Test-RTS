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

    public void setCut(MinecraftClient client, @Nullable Integer y) {
        if (java.util.Objects.equals(this.cutY, y)) {
            return;
        }
        this.cutY = y;
        com.testrts.rtsclient.render.CutView.set(client, this.active ? y : null);
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
            Messages.info("Baritone not found - using the built-in engine for walking, building and mining");
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
        com.testrts.rtsclient.render.CutView.set(client, null);
        this.cutY = null;
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
        keepUnblurred();
        client.setScreen(this.screen);
    }

    /**
     * The Blur mod blurs the world behind every open screen, but the RTS screen is a view of the
     * world. Put it on Blur's own exclusion list (like Blur does for chat). Only in memory: Blur's
     * config file keeps it only if Blur's settings are saved afterwards.
     */
    private static void keepUnblurred() {
        if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("blur")) {
            return;
        }
        try {
            java.lang.reflect.Field field = Class.forName("com.tterrag.blur.config.BlurConfig").getField("blurExclusions");
            @SuppressWarnings("unchecked")
            java.util.List<String> exclusions = (java.util.List<String>) field.get(null);
            String name = RtsScreen.class.getName();
            if (exclusions == null || !exclusions.contains(name)) {
                java.util.List<String> withUs = exclusions == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(exclusions);
                withUs.add(name);
                field.set(null, withUs);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // A Blur version that works differently: the RTS view just stays blurred.
            RtsClientMod.LOGGER.debug("Couldn't exclude the RTS screen from Blur", e);
        }
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
