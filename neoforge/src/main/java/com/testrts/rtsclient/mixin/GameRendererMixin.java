package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    /**
     * The player stays the camera entity, so {@code minecraft.hitResult} is still what the player
     * looks at (Baritone relies on it); just don't outline that block in the RTS view.
     */
    @Inject(method = "shouldRenderBlockOutline", at = @At("HEAD"), cancellable = true)
    private void rtsclient$noOutlineInRts(CallbackInfoReturnable<Boolean> cir) {
        if (RtsController.get().isActive()) {
            cir.setReturnValue(false);
        }
    }

    /**
     * RTS mode: the view uses exactly the FOV setting. The player is the camera entity, so flying,
     * sprinting or Speed would otherwise widen or narrow the view, and the cursor would no longer be
     * over the block it points at (picking assumes the plain FOV).
     */
    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true, require = 0)
    private void rtsclient$plainFovInRts(net.minecraft.client.Camera camera, float partialTicks, boolean useFovSetting,
                                         CallbackInfoReturnable<Double> cir) {
        if (useFovSetting && RtsController.get().isActive()) {
            cir.setReturnValue((double) net.minecraft.client.Minecraft.getInstance().options.fov().get());
        }
    }
}
