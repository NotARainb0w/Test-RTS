package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.CameraSubmersionType;
import net.minecraft.entity.Entity;
import net.minecraft.world.BlockView;
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
    protected abstract void setRotation(float yaw, float pitch);

    /** RTS mode uses the front view (see RtsCamera): look the way the RTS camera looks, not back at it. */
    @Inject(method = "update", at = @At("TAIL"))
    private void rtsclient$unflipFrontView(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
                                          float tickDelta, CallbackInfo ci) {
        if (RtsController.get().isActive() && inverseView) {
            setRotation(focusedEntity.getYaw(tickDelta), focusedEntity.getPitch(tickDelta));
        }
    }

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
