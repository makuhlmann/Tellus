package com.yucareux.tellus.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.WorldGenLevel;

/**
 * Whether Tellus decoration may write a block. During chunk generation {@link WorldGenLevel#ensureCanWrite} already
 * refuses positions past the map edge (see {@code WorldGenRegionMapEdgeMixin}); details applied later write to the
 * live level, whose check lets everything through, so they ask here.
 */
public final class MapEdgeWrites {
   private MapEdgeWrites() {
   }

   public static boolean canWrite(WorldGenLevel level, BlockPos pos) {
      return level.ensureCanWrite(pos) && !isPastMapEdge(level, pos);
   }

   static boolean isPastMapEdge(WorldGenLevel level, BlockPos pos) {
      return level instanceof ServerLevel serverLevel
         && serverLevel.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator
         && generator.mapEdgeWrap().isPastCut(pos.getX(), pos.getZ());
   }
}
