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
 * The chunk builder asks this region for every block it meshes. Hidden positions answer "air", so no renderer - vanilla
 * or Fabric's Indigo - ever draws them. What stays visible depends on the run: a 7x7x7 bubble around the hider in the
 * nether, only the run's dungeon in the overworld, nothing at all after the stash. Beds, portals, obsidian and torches
 * are always drawn so the portal-and-bed routine works. Fluids and entities are untouched: lava stays visible.
 */
@Mixin(ChunkRendererRegion.class)
public class ChunkRendererRegionMixin {
    @Inject(method = "getBlockState", at = @At("RETURN"), cancellable = true)
    private void quillcoin$hideBlocks(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
        if (QuillHider.hideBlock(pos, cir.getReturnValue())) cir.setReturnValue(Blocks.AIR.getDefaultState());
    }
}
