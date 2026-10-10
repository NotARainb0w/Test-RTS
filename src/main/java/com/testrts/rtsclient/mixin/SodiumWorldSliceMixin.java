package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.render.CutView;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cut view under Sodium (0.5.x), which builds chunks from its own world copy. Optional: does
 * nothing when Sodium isn't installed or its internals differ.
 */
@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.world.WorldSlice", remap = false)
public abstract class SodiumWorldSliceMixin {
    @Inject(method = "getBlockState(III)Lnet/minecraft/class_2680;", at = @At("HEAD"), cancellable = true, require = 0)
    private void rtsclient$cutState(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
        if (CutView.hides(y)) {
            cir.setReturnValue(Blocks.AIR.getDefaultState());
        }
    }
}
