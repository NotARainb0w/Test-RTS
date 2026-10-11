package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * The player stays the camera entity, so {@code client.crosshairTarget} is still what the player
     * looks at (Baritone relies on it); just don't outline that block in the RTS view.
     */
    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noOutlineInRts(CallbackInfoReturnable<Boolean> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(false);
        }
    }
}
