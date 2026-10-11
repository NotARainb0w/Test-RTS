package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.CameraType;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While RTS mode is on, the game is told it's in the third-person front view, without changing the
 * player's setting: the player's body is drawn (and no first-person body or hand mod draws one at
 * the camera), and third-person camera mods, which handle the back view, leave the camera alone.
 */
@Mixin(Options.class)
public abstract class OptionsMixin {
    @Inject(method = "getCameraType", at = @At("HEAD"), cancellable = true)
    private void rtsclient$thirdPersonInRts(CallbackInfoReturnable<CameraType> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(CameraType.THIRD_PERSON_FRONT);
        }
    }
}
