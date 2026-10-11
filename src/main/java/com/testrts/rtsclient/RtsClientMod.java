package com.testrts.rtsclient;

import com.testrts.rtsclient.config.RtsConfig;
import com.testrts.rtsclient.exec.Engine;
import com.testrts.rtsclient.exec.NativeEngine;
import com.testrts.rtsclient.render.WorldOverlay;
import com.testrts.rtsclient.storage.ContainerTracker;
import com.testrts.rtsclient.storage.StorageManager;
import com.testrts.rtsclient.task.RtsTask;
import com.testrts.rtsclient.task.TaskQueue;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RtsClientMod implements ClientModInitializer {
    public static final String MOD_ID = "rtsclient";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final String CATEGORY = "key.categories.rtsclient";
    public static KeyBinding toggleKey;
    public static KeyBinding stopKey;

    @Override
    public void onInitializeClient() {
        RtsConfig.load();

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rtsclient.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, CATEGORY));
        stopKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.rtsclient.stop", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode(), CATEGORY));

        ClientTickEvents.END_CLIENT_TICK.register(RtsClientMod::onTick);
        WorldRenderEvents.AFTER_ENTITIES.register(WorldOverlay::renderPlayer);
        WorldRenderEvents.LAST.register(WorldOverlay::render);
        HudRenderCallback.EVENT.register((context, tickDelta) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (RtsController.get().isActive() || client.options.hudHidden || TaskQueue.get().isEmpty()) {
                return;
            }
            // Outside RTS mode, keep a small reminder that orders are still running.
            RtsTask head = TaskQueue.get().snapshot().get(0);
            String text = "RTS: " + head.label() + " (" + TaskQueue.get().size() + " queued) - "
                    + toggleKey.getBoundKeyLocalizedText().getString() + " to open";
            context.drawTextWithShadow(client.textRenderer, text, 4, 4, 0xFFFFFF);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
            RtsController.get().disable(client);
            com.testrts.rtsclient.task.QueueStore.autosave();
            TaskQueue.get().clear(client);
            StorageManager.get().flush();
        }));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            com.testrts.rtsclient.task.QueueStore.autosave();
            StorageManager.get().flush();
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

    private static void onTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            RtsController.get().toggle(client);
        }
        while (stopKey.wasPressed()) {
            TaskQueue.get().clear(client);
        }
        com.testrts.rtsclient.task.QueueStore.tick(client);
        StorageManager.get().tick(client);
        ContainerTracker.get().tick(client);
        RtsController.get().tick(client);
        TaskQueue.get().tick(client);
        com.testrts.rtsclient.exec.Look.tick(client);
        Engine.tick(client);
        NativeEngine.get().tick(client);
    }
}
