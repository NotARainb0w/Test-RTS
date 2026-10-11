package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.RtsController;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(WorldRenderer.class)
public abstract class WorldRendererMixin {
    /**
     * RTS mode: work out which chunks are visible the way spectator mode does, so chunks don't drop
     * out when the free camera is close to or inside terrain (cave culling assumes the camera is in
     * open air at the player). Optional: renderer mods like Sodium replace this call.
     */
    @ModifyArg(method = "render", require = 0,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/WorldRenderer;setupTerrain(Lnet/minecraft/client/render/Camera;Lnet/minecraft/client/render/Frustum;ZZ)V"),
            index = 3)
    private boolean rtsclient$spectatorCulling(boolean spectator) {
        return spectator || RtsController.get().isActive();
    }
}
