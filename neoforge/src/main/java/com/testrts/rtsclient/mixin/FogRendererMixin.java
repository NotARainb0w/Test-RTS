package com.testrts.rtsclient.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.testrts.rtsclient.RtsController;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.effect.MobEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Looking down from high above, distance fog (and the thick fog of the Nether) hazes most of the
 * view. In RTS mode, push terrain fog to the very edge of the render distance.
 */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    @Inject(method = "setupFog", at = @At("RETURN"))
    private static void rtsclient$clearView(Camera camera, FogRenderer.FogMode fogMode, float renderDistance,
                                            boolean thickFog, float partialTick, CallbackInfo ci) {
        if (!RtsController.get().isActive() || fogMode != FogRenderer.FogMode.FOG_TERRAIN) {
            return;
        }
        var player = Minecraft.getInstance().player;
        if (player != null && (player.hasEffect(MobEffects.BLINDNESS) || player.hasEffect(MobEffects.DARKNESS))) {
            return;
        }
        RenderSystem.setShaderFogStart(renderDistance * 0.92F);
        RenderSystem.setShaderFogEnd(renderDistance * 1.1F);
    }
}
