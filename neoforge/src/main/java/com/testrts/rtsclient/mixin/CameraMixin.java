package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import com.testrts.rtsclient.camera.RtsCamera;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    /**
     * RTS mode: the view comes from the RTS camera. Minecraft's camera still belongs to the player
     * (nothing to restore afterwards); only where it sits and where it looks is replaced.
     */
    @Inject(method = "setup", at = @At("TAIL"))
    private void rtsclient$rtsView(BlockGetter level, Entity entity, boolean detached, boolean reverse, float partialTick,
                                  CallbackInfo ci) {
        if (!RtsController.get().isActive()) {
            return;
        }
        RtsCamera rts = RtsController.get().camera();
        if (rts.isEnabled()) {
            Vec3 pos = rts.position();
            setRotation(rts.yaw(), rts.pitch());
            setPosition(pos.x, pos.y, pos.z);
        }
    }

    @Inject(method = "getFluidInCamera", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noFluidFog(CallbackInfoReturnable<FogType> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(FogType.NONE);
        }
    }
}
