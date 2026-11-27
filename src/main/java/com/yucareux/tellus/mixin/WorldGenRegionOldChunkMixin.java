package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells vanilla's terrain blending there are no old chunks around in a Tellus world.
 *
 * <p>Before a chunk's biomes, vanilla asks whether chunks from an older version lie nearby, to blend new terrain into
 * them. The first time it asks near a region file, it reads every chunk saved in that region (up to 1,024 of them,
 * each decompressed whole) one at a time on the disk thread, while the single worldgen queue waits; in a tall world
 * that stops all generation for seconds at a time and delays the chunk loads queued behind it. Tellus terrain never
 * blends: its generator ignores the blender it is handed and samples caves with an empty one.
 */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionOldChunkMixin {
   @Inject(method = "isOldChunkAround(Lnet/minecraft/world/level/ChunkPos;I)Z", at = @At("HEAD"), cancellable = true)
   private void tellus$noBlendingInTellusWorlds(ChunkPos pos, int range, CallbackInfoReturnable<Boolean> cir) {
      if (((WorldGenRegion)(Object)this).getLevel().getChunkSource().getGenerator() instanceof EarthChunkGenerator) {
         cir.setReturnValue(false);
      }
   }
}
