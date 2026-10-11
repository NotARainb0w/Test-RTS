package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.Camera;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The RTS camera flies through water and lava; without this the whole view turns into a blue (or
 * orange) blur of underwater fog whenever it dips below a surface.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    /** RTS mode uses the front view (see RtsCamera): look the way the RTS camera looks, not back at it. */
    @Inject(method = "setup", at = @At("TAIL"))
    private void rtsclient$unflipFrontView(BlockGetter level, Entity entity, boolean detached, boolean reverse,
                                          float partialTick, CallbackInfo ci) {
        if (RtsController.get().isActive() && reverse) {
            setRotation(entity.getViewYRot(partialTick), entity.getViewXRot(partialTick));
        }
    }

    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noFluidFog(CallbackInfoReturnable<FogType> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(FogType.NONE);
        }
    }

    /**
     * RTS mode reports third person (so mods that draw the player's body in first person don't add
     * a second copy at the camera), but the view must stay exactly where the RTS camera is.
     */
    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noThirdPersonOffset(float maxZoom, CallbackInfoReturnable<Float> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(0.0F);
        }
    }
}
