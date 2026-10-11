package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.camera.RtsCamera;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.CameraSubmersionType;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPos(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch);

    /**
     * RTS mode: the view comes from the RTS camera. Minecraft's camera still belongs to the player
     * (nothing to restore afterwards); only where it sits and where it looks is replaced.
     */
    @Inject(method = "update", at = @At("TAIL"))
    private void rtsclient$rtsView(BlockView area, Entity focusedEntity, boolean thirdPerson, boolean inverseView,
                                  float tickDelta, CallbackInfo ci) {
        if (!RtsController.get().isActive()) {
            return;
        }
        RtsCamera rts = RtsController.get().camera();
        if (rts.isEnabled()) {
            Vec3d pos = rts.position();
            setRotation(rts.yaw(), rts.pitch());
            setPos(pos.x, pos.y, pos.z);
        }
    }

    @Inject(method = "getSubmersionType", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noFluidFog(CallbackInfoReturnable<CameraSubmersionType> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(CameraSubmersionType.NONE);
        }
    }
}
