package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No HUD (hotbar, crosshair, hearts) under the RTS screen; the F1 setting itself isn't touched. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noHudInRts(DrawContext context, float tickDelta, CallbackInfo ci) {
        if (RtsController.get().isActive()) {
            ci.cancel();
        }
    }
}
