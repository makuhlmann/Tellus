package com.yucareux.tellus.mixin;

import com.yucareux.tellus.world.realtime.TellusRealtimeState;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Melts snow layers with the real-world temperature; vanilla's melting by block light is unchanged. */
@Mixin(SnowLayerBlock.class)
public abstract class SnowLayerMeltMixin {
   private static final int UPDATE_ALL = 3;

   @Inject(
      method = "randomTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
      at = @At("HEAD"),
      cancellable = true
   )
   private void tellus$meltWithRealTemperature(BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
      if (level.getChunkSource().getGenerator() instanceof EarthChunkGenerator) {
         float chance = TellusRealtimeState.snowMeltChance(pos);
         if (chance > 0.0F && random.nextFloat() < chance) {
            // A layer at a time, so deep snow outlasts a dusting. The last goes as vanilla's light melt takes it,
            // and the neighbour update clears the snowy look of the grass beneath.
            int layers = state.getValue(SnowLayerBlock.LAYERS);
            if (layers > 1) {
               level.setBlock(pos, state.setValue(SnowLayerBlock.LAYERS, layers - 1), UPDATE_ALL);
            } else {
               level.removeBlock(pos, false);
            }

            ci.cancel();
         }
      }
   }
}
