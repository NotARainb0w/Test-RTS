package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
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
    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noFluidFog(CallbackInfoReturnable<FogType> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(FogType.NONE);
        }
    }
}
