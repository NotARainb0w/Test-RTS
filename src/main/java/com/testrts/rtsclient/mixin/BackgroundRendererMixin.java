package com.testrts.rtsclient.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.testrts.rtsclient.RtsController;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BackgroundRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.effect.StatusEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Looking down from high above, distance fog (and the thick fog of the Nether) hazes most of the
 * view. In RTS mode, push terrain fog to the very edge of the render distance.
 */
@Mixin(BackgroundRenderer.class)
public abstract class BackgroundRendererMixin {
    @Inject(method = "applyFog", at = @At("TAIL"))
    private static void rtsclient$clearView(Camera camera, BackgroundRenderer.FogType fogType, float viewDistance,
                                            boolean thickFog, float tickDelta, CallbackInfo ci) {
        if (!RtsController.get().isActive() || fogType != BackgroundRenderer.FogType.FOG_TERRAIN) {
            return;
        }
        var player = MinecraftClient.getInstance().player;
        if (player != null && (player.hasStatusEffect(StatusEffects.BLINDNESS) || player.hasStatusEffect(StatusEffects.DARKNESS))) {
            return;
        }
        RenderSystem.setShaderFogStart(viewDistance * 0.92F);
        RenderSystem.setShaderFogEnd(viewDistance * 1.1F);
    }
}
