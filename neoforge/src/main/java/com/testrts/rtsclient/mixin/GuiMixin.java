package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No HUD (hotbar, crosshair, hearts) under the RTS screen; the F1 setting itself isn't touched. */
@Mixin(Gui.class)
public abstract class GuiMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noHudInRts(GuiGraphics graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (RtsController.get().isActive()) {
            ci.cancel();
        }
    }
}
