package com.testrts.rtsclient.mixin;

import com.testrts.rtsclient.render.CutView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cut view (vanilla renderer): chunk meshes are built as if everything above the cut were air. */
@Mixin(targets = "net.minecraft.client.renderer.chunk.RenderChunk")
public abstract class RenderChunkMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void rtsclient$cutState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (CutView.hides(pos.getY())) {
            cir.setReturnValue(Blocks.AIR.defaultBlockState());
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void rtsclient$cutBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (CutView.hides(pos.getY())) {
            cir.setReturnValue(null);
        }
    }
}
