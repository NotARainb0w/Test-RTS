package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keep the vanilla crosshair target computed from the player's own eyes rather than the RTS
 * camera, so anything reading {@code client.crosshairTarget} sees what the player could reach.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Redirect(method = "updateTargetedEntity",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MinecraftClient;getCameraEntity()Lnet/minecraft/entity/Entity;"))
    private Entity rtsclient$targetFromPlayer(MinecraftClient instance) {
        if (RtsController.get().isActive() && instance.player != null) {
            return instance.player;
        }
        return instance.getCameraEntity();
    }
}
