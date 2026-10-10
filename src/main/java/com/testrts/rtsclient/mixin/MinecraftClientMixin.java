package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * With the RTS screen open, vanilla's per-tick block-breaking handler would abort any block
 * Baritone is mining. The player can't left-click the world directly in RTS mode anyway.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "handleBlockBreaking", at = @At("HEAD"), cancellable = true)
    private void rtsclient$letBaritoneMine(boolean breaking, CallbackInfo ci) {
        if (RtsController.get().isActive()) {
            ci.cancel();
        }
    }
}
