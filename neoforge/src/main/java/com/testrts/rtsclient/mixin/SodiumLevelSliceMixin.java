package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.render.CutView;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Cut view under Sodium 0.6 / Embeddium, which build chunks from their own world copy. Optional: does
 * nothing when neither is installed or their internals differ.
 */
@Pseudo
@Mixin(targets = {
        "net.caffeinemc.mods.sodium.client.world.LevelSlice",
        "org.embeddedt.embeddium.impl.world.WorldSlice"
}, remap = false)
public abstract class SodiumLevelSliceMixin {
    @Inject(method = "getBlockState(III)Lnet/minecraft/world/level/block/state/BlockState;", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void rtsclient$cutState(int x, int y, int z, CallbackInfoReturnable<BlockState> cir) {
        if (CutView.hides(y)) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }
}
