package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.build.Protection;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Last line of defence for protected areas: while RTS orders run, no block in one gets broken, whoever
 * asks (the built-in engine, Baritone, a task). The builder already avoids them; this catches the rest.
 */
@Mixin(ClientPlayerInteractionManager.class)
public abstract class ClientPlayerInteractionManagerMixin {
    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void rtsclient$protectStart(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (Protection.get().blocksBreaking(pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "updateBlockBreakingProgress", at = @At("HEAD"), cancellable = true)
    private void rtsclient$protectContinue(BlockPos pos, Direction direction, CallbackInfoReturnable<Boolean> cir) {
        if (Protection.get().blocksBreaking(pos)) {
            cir.setReturnValue(false);
        }
    }
}
