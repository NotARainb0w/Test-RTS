package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla only applies movement input to the player while the player is the camera entity.
 * In RTS mode the camera is detached, so pretend it isn't; otherwise Baritone couldn't walk.
 */
@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {
    @Inject(method = "isCamera", at = @At("HEAD"), cancellable = true)
    private void rtsclient$stillControlled(CallbackInfoReturnable<Boolean> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(true);
        }
    }
}
