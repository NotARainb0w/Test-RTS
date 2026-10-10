package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.render.CutView;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cut view (vanilla renderer): chunk meshes are built as if everything above the cut were air. */
@Mixin(targets = "net.minecraft.client.render.chunk.RenderedChunk")
public abstract class RenderedChunkMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void rtsclient$cutState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (CutView.hides(pos.getY())) {
            cir.setReturnValue(Blocks.AIR.getDefaultState());
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void rtsclient$cutBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (CutView.hides(pos.getY())) {
            cir.setReturnValue(null);
        }
    }
}
