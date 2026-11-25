package com.yucareux.tellus.worldgen.caves;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntBinaryOperator;

/**
 * Chooses the start heights of ravines.
 *
 * <p>Vanilla starts ravines at a fixed Y 10-67, so they only break through the surface in lowlands near sea
 * level. Tellus terrain sits at any height, so the band is measured from the terrain instead: from the lowest
 * surface the ravine can reach, and at least {@link #SURFACE_CLEARANCE} blocks below it. A ravine therefore
 * stays buried when it runs under a slope or towards a valley, and only a few short openings remain on flat
 * ground.
 *
 * <p>Only the start height changes. A ravine's start chunk, position and shape come from the same random draws
 * as before, so ravines keep their horizontal layout.
 */
final class TellusRavinePlacement {
   /** The highest ravine start lies this many blocks below the lowest surface the ravine can reach. */
   static final int SURFACE_CLEARANCE = 16;
   /** The lowest ravine start lies this many blocks below the lowest surface the ravine can reach. */
   static final int BAND_BOTTOM_DEPTH = 97;
   /**
    * Horizontal distance from a source chunk's centre that its ravine can carve: up to 11 blocks of start
    * offset inside the chunk, a path of up to 112 blocks and a radius of up to 7.5 blocks.
    */
   static final int REACH_BLOCKS = 128;
   static final int SAMPLE_SPACING_BLOCKS = 8;
   private static final int CACHE_SIZE = 4096;

   private final Map<Long, Integer> lowestSurfaceBySourceChunk = new LinkedHashMap<>(CACHE_SIZE, 0.75F, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<Long, Integer> eldest) {
         return this.size() > CACHE_SIZE;
      }
   };

   /**
    * Returns the lowest terrain surface within reach of a ravine that starts in the given chunk. Each ravine
    * is carved into every chunk it crosses, so the result is cached per source chunk.
    */
   int lowestSurfaceInReach(int sourceChunkX, int sourceChunkZ, int sourceSurfaceY, IntBinaryOperator surfaceSampler) {
      long key = ((long)sourceChunkX << 32) | (sourceChunkZ & 0xFFFFFFFFL);
      synchronized (this.lowestSurfaceBySourceChunk) {
         Integer cached = this.lowestSurfaceBySourceChunk.get(key);
         if (cached != null) {
            return cached;
         }
      }

      int lowest = scanLowestSurface(
         (sourceChunkX << 4) + 8, (sourceChunkZ << 4) + 8, sourceSurfaceY, surfaceSampler
      );
      synchronized (this.lowestSurfaceBySourceChunk) {
         this.lowestSurfaceBySourceChunk.put(key, lowest);
      }
      return lowest;
   }

   /**
    * Samples the surface on an {@link #SAMPLE_SPACING_BLOCKS}-block grid within {@link #REACH_BLOCKS} of the
    * centre and returns the lowest height, never higher than the centre's own height.
    */
   static int scanLowestSurface(int centerX, int centerZ, int centerSurfaceY, IntBinaryOperator surfaceSampler) {
      int lowest = centerSurfaceY;
      int steps = REACH_BLOCKS / SAMPLE_SPACING_BLOCKS;
      long reachSquared = (long)REACH_BLOCKS * REACH_BLOCKS;
      for (int stepZ = -steps; stepZ <= steps; stepZ++) {
         for (int stepX = -steps; stepX <= steps; stepX++) {
            long offsetX = (long)stepX * SAMPLE_SPACING_BLOCKS;
            long offsetZ = (long)stepZ * SAMPLE_SPACING_BLOCKS;
            if (offsetX == 0 && offsetZ == 0 || offsetX * offsetX + offsetZ * offsetZ > reachSquared) {
               continue;
            }
            lowest = Math.min(lowest, surfaceSampler.applyAsInt(centerX + (int)offsetX, centerZ + (int)offsetZ));
         }
      }
      return lowest;
   }

   /**
    * Returns the start height band for a ravine whose lowest reachable surface is {@code anchorSurfaceY}. The
    * band stays inside the underground layer below that surface and inside the world's height range.
    */
   static Band band(int anchorSurfaceY, int minY, int maxY, int undergroundDepth) {
      int anchor = Math.max(minY, Math.min(maxY, anchorSurfaceY));
      int shellFloorY = Math.max(minY, anchor - Math.max(0, undergroundDepth) + 1);
      int floorY = Math.max(shellFloorY, anchor - BAND_BOTTOM_DEPTH);
      int ceilingY = Math.max(floorY, Math.min(maxY, anchor - SURFACE_CLEARANCE));
      return new Band(floorY, ceilingY, shellFloorY);
   }

   /**
    * Ravine start heights from {@code floorY} to {@code ceilingY}, both inclusive. {@code shellFloorY} is the
    * deepest Y of the underground layer below the anchor surface.
    */
   record Band(int floorY, int ceilingY, int shellFloorY) {
   }
}
