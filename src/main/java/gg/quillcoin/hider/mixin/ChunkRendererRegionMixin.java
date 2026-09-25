package gg.quillcoin.hider.mixin;

import gg.quillcoin.hider.QuillHider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.render.chunk.ChunkRendererRegion;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The ground half of a blind run: the chunk builder asks this region for every block it meshes. Hidden positions answer
 * "air", so no renderer - vanilla or Fabric's Indigo - ever draws them. The run's dungeon still answers truthfully, and
 * because its neighbours read as air, its whole shell is drawn. Fluids and entities are untouched: lava stays visible.
 */
@Mixin(ChunkRendererRegion.class)
public class ChunkRendererRegionMixin {
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void quillcoin$hideBlocks(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (QuillHider.hideBlock(pos)) cir.setReturnValue(Blocks.AIR.getDefaultState());
    }
}
