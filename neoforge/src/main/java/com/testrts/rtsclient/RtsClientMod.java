package com.testrts.rtsclient;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.exec.NativeEngine;
import com.testrts.rtsclient.render.RenderContext;
import com.testrts.rtsclient.render.WorldOverlay;
import com.testrts.rtsclient.storage.ContainerTracker;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.TaskQueue;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.joml.Matrix4fStack;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

@Mod(value = RtsClientMod.MOD_ID, dist = Dist.CLIENT)
public final class RtsClientMod {
    public static final String MOD_ID = "rtsclient";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String CATEGORY = "key.categories.rtsclient";
    public static KeyMapping toggleKey = new KeyMapping(
            "key.rtsclient.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY);
    public static KeyMapping stopKey = new KeyMapping(
            "key.rtsclient.stop", InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), CATEGORY);
    public static KeyMapping hologramKey = new KeyMapping(
            "key.rtsclient.hologram", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_H, CATEGORY);
    public static KeyMapping autoPlaceKey = new KeyMapping(
            "key.rtsclient.autoplace", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_Z, CATEGORY);
    public static KeyMapping aimPlaceKey = new KeyMapping(
            "key.rtsclient.aimplace", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_X, CATEGORY);

    public RtsClientMod(IEventBus modBus) {
        RtsConfig.load();

        modBus.addListener(RegisterKeyMappingsEvent.class, event -> {
            event.register(toggleKey);
            event.register(stopKey);
            event.register(hologramKey);
            event.register(autoPlaceKey);
            event.register(aimPlaceKey);
        });

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener(ClientTickEvent.Post.class, event -> onTick(Minecraft.getInstance()));
        bus.addListener(RenderLevelStageEvent.class, RtsClientMod::onRenderLevel);
        bus.addListener(RenderGuiEvent.Post.class, event -> {
            Minecraft client = Minecraft.getInstance();
            if (RtsController.get().isActive() || client.options.hideGui || TaskQueue.get().isEmpty()) {
                return;
            }
            // Outside RTS mode, keep a small reminder that orders are still running.
            RtsTask head = TaskQueue.get().snapshot().get(0);
            String text = "RTS: " + head.label() + " (" + TaskQueue.get().size() + " queued" + (TaskQueue.get().isStarted() ? "" : ", waiting for Start") + ") - "
                    + toggleKey.getTranslatedKeyMessage().getString() + " to open";
            event.getGuiGraphics().drawString(client.font, text, 4, 4, 0xFFFFFF);
        });
        bus.addListener(ClientPlayerNetworkEvent.LoggingOut.class, event -> {
            Minecraft client = Minecraft.getInstance();
            RtsController.get().disable(client);
            com.testrts.rtsclient.task.QueueStore.autosave();
            TaskQueue.get().clear(client);
            StorageManager.get().flush();
            com.testrts.rtsclient.storage.MeNetwork.flushAll();
        });
        bus.addListener(GameShuttingDownEvent.class, event -> {
            com.testrts.rtsclient.task.QueueStore.autosave();
            StorageManager.get().flush();
            com.testrts.rtsclient.storage.MeNetwork.flushAll();
        });

        // Live tests (src/devtest, dev runs only; not part of the release jar).
        if (System.getProperty("rtsclient.autotest") != null) {
            try {
                Class.forName("com.testrts.rtsclient.devtest.AutoTest").getMethod("install").invoke(null);
            } catch (ReflectiveOperationException e) {
                LOGGER.error("Couldn't start the live tests", e);
            }
        }
    }

    private static void onTick(Minecraft client) {
        while (toggleKey.consumeClick()) {
            RtsController.get().toggle(client);
        }
        while (stopKey.consumeClick()) {
            TaskQueue.get().clear(client);
        }
        while (hologramKey.consumeClick()) {
            toggleHologram();
        }
        com.testrts.rtsclient.exec.AutoPlace.tick(client, autoPlaceKey.isDown(), aimPlaceKey.isDown());
        com.testrts.rtsclient.task.QueueStore.tick(client);
        StorageManager.get().tick(client);
        com.testrts.rtsclient.build.Protection.get().tick(client);
        com.testrts.rtsclient.storage.MeNetwork.tickAll(client);
        ContainerTracker.get().tick(client);
        RtsController.get().tick(client);
        TaskQueue.get().tick(client);
        com.testrts.rtsclient.exec.Look.tick(client);
        Engine.tick(client);
        NativeEngine.get().tick(client);
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        if (!RtsController.get().isActive()) {
            // Outside RTS mode leave the render state and the shared buffers alone: swapping the view
            // matrix and flushing them here drew other things (like the player's own model) in the wrong
            // place. The hologram only draws our own preview meshes, with the matrices given to it.
            if (WorldOverlay.hasHologram()) {
                PoseStack view = new PoseStack();
                view.mulPose(event.getModelViewMatrix());
                WorldOverlay.hologram(view, event.getProjectionMatrix(), event.getCamera().getPosition());
            } else {
                WorldOverlay.idle();
            }
            return;
        }
        withViewMatrix(event, WorldOverlay::render);
    }

    /**
     * Our overlay code expects the view rotation in its own PoseStack and an identity model-view
     * matrix in RenderSystem (what vanilla does at these points differs by stage), so set that up.
     */
    private static void withViewMatrix(RenderLevelStageEvent event, Consumer<RenderContext> body) {
        Minecraft client = Minecraft.getInstance();
        MultiBufferSource.BufferSource consumers = client.renderBuffers().bufferSource();
        PoseStack poseStack = new PoseStack();
        poseStack.mulPose(event.getModelViewMatrix());
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        RenderSystem.applyModelViewMatrix();
        try {
            body.accept(new RenderContext(poseStack, event.getProjectionMatrix(), event.getCamera(), partialTick, consumers));
            consumers.endBatch();
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    private static void toggleHologram() {
        com.testrts.rtsclient.config.RtsConfig cfg = com.testrts.rtsclient.config.RtsConfig.get();
        cfg.showHologram = !cfg.showHologram;
        com.testrts.rtsclient.config.RtsConfig.save();
        com.testrts.rtsclient.util.Messages.info("Build hologram " + (cfg.showHologram ? "on" : "off"));
    }
}
