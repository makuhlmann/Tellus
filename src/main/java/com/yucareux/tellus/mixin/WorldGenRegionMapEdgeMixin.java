package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps decoration of the last chunks inside the map (trees, buildings, vanilla features) from reaching into the
 * empty chunks past its edge.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMapEdgeMixin {
   @Inject(method = "ensureCanWrite(Lnet/minecraft/core/BlockPos;)Z", at = @At("HEAD"), cancellable = true)
   private void tellus$refuseWritesPastMapEdge(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
      if (((WorldGenRegion)(Object)this).getLevel().getChunkSource().getGenerator() instanceof EarthChunkGenerator generator
         && generator.mapEdgeWrap().isPastCut(pos.getX(), pos.getZ())) {
         cir.setReturnValue(false);
      }
   }
}
