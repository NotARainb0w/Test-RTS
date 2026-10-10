package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.CameraSubmersionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The RTS camera flies through water and lava; without this the whole view turns into a blue (or
 * orange) blur of underwater fog whenever it dips below a surface.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Inject(method = "getSubmersionType", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noFluidFog(CallbackInfoReturnable<CameraSubmersionType> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(CameraSubmersionType.NONE);
        }
    }

    /**
     * RTS mode reports third person (so mods that draw the player's body in first person don't add
     * a second copy at the camera), but the view must stay exactly where the RTS camera is.
     */
    @Inject(method = "clipToSpace", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noThirdPersonOffset(double desiredDistance, CallbackInfoReturnable<Double> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(0.0);
        }
    }
}
