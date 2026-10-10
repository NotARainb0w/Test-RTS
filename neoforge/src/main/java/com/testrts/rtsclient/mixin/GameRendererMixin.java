package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keep the vanilla crosshair target computed from the player's own eyes rather than the RTS
 * camera, so anything reading {@code minecraft.hitResult} sees what the player could reach.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Redirect(method = "pick(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;getCameraEntity()Lnet/minecraft/world/entity/Entity;"))
    private Entity rtsclient$targetFromPlayer(Minecraft instance) {
        if (RtsController.get().isActive() && instance.player != null) {
            return instance.player;
        }
        return instance.getCameraEntity();
    }
}
