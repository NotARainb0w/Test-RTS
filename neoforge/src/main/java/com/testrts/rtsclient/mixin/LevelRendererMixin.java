package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    /**
     * RTS mode: work out which chunks are visible the way spectator mode does, so chunks don't drop
     * out when the free camera is close to or inside terrain (cave culling assumes the camera is in
     * open air at the player). Optional: renderer mods like Embeddium replace this call.
     */
    @ModifyArg(method = "renderLevel", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V"),
            index = 3)
    private boolean rtsclient$spectatorCulling(boolean spectator) {
        return spectator || RtsController.get().isActive();
    }
}
