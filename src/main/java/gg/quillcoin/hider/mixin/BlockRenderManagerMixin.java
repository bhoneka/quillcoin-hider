package gg.quillcoin.hider.mixin;

import gg.quillcoin.hider.QuillHider;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.block.BlockRenderManager;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.BlockRenderView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The ground half of a blind run: no block is drawn unless the hider says so - only the run's dungeon (and nothing at all
 * once the book is stashed). Fluids and entities still render; lava stays visible, mobs stay visible.
 */
@Mixin(BlockRenderManager.class)
public class BlockRenderManagerMixin {
    @Inject(method = "renderBlock", at = @At("HEAD"), cancellable = true)
    private void quillcoin$hideBlocks(BlockState state, BlockPos pos, BlockRenderView world, MatrixStack matrices, VertexConsumer consumer, boolean cull, Random random, CallbackInfo ci) {
        if (QuillHider.hideBlock(pos)) ci.cancel();
    }
}
