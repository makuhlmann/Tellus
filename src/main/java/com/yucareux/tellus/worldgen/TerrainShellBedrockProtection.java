package com.yucareux.tellus.worldgen;

/**
 * Defines the bedrock boundary around Tellus's surface-relative terrain shell.
 * A bottom layer alone is not sufficient on steep terrain: adjacent columns
 * can have different shell bottoms, leaving a vertical opening into the void.
 * Each bottom is clamped to the world floor, so where the floor lies within the
 * terrain depth of a surface (sea level and below, with the default depth and
 * a floor of Y -192) the bottoms are flat and none of this is needed.
 */
public final class TerrainShellBedrockProtection {
   static final int CHUNK_SIDE = 16;
   static final int CHUNK_AREA = CHUNK_SIDE * CHUNK_SIDE;
   /**
    * Columns just outside a chunk that its edge columns touch: west of x 0, east
    * of x 15, north of z 0 and south of z 15, sixteen each.
    */
   public static final int NEIGHBOR_RING_SIZE = 4 * CHUNK_SIDE;

   private TerrainShellBedrockProtection() {
   }

   public static int westRingIndex(int localZ) {
      return localZ;
   }

   public static int eastRingIndex(int localZ) {
      return CHUNK_SIDE + localZ;
   }

   public static int northRingIndex(int localX) {
      return 2 * CHUNK_SIDE + localX;
   }

   public static int southRingIndex(int localX) {
      return 3 * CHUNK_SIDE + localX;
   }

   /**
    * Returns, per column of a chunk, the highest block {@link #sideSkinTopY}
    * turns into bedrock. Surfaces are indexed {@code localZ * 16 + localX}; the
    * ring must hold the surfaces the neighbouring chunks build their own edge
    * columns from, or the two sides of a chunk border disagree on the bedrock
    * between them.
    */
   public static int[] sideSkinTopYs(int[] surfaces, int[] neighborRing, int terrainDepth, int minimumY, int maximumY) {
      int[] result = new int[CHUNK_AREA];

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = localZ * CHUNK_SIDE + localX;
            int bottomY = supportBottomY(surfaces[index], terrainDepth, minimumY);
            int highestNeighborBottomY = Integer.MIN_VALUE;
            for (int side = 0; side < 4; side++) {
               int neighborSurfaceY = neighborSurfaceY(surfaces, neighborRing, localX, localZ, side, minimumY, maximumY);
               highestNeighborBottomY = Math.max(highestNeighborBottomY, supportBottomY(neighborSurfaceY, terrainDepth, minimumY));
            }

            result[index] = sideSkinTopY(bottomY, clamp(surfaces[index], minimumY, maximumY), highestNeighborBottomY);
         }
      }

      return result;
   }

   /**
    * Returns, per column of a chunk, the first block of the
    * {@link #voidCurtainBottomY curtain} hanging below its bottom. Takes the
    * same surfaces and neighbour ring as {@link #sideSkinTopYs}.
    */
   public static int[] voidCurtainBottomYs(int[] surfaces, int[] neighborRing, int terrainDepth, int minimumY, int maximumY) {
      int[] result = new int[CHUNK_AREA];

      for (int localZ = 0; localZ < CHUNK_SIDE; localZ++) {
         for (int localX = 0; localX < CHUNK_SIDE; localX++) {
            int index = localZ * CHUNK_SIDE + localX;
            int bottomY = supportBottomY(surfaces[index], terrainDepth, minimumY);
            int lowestNeighborSurfaceY = Integer.MAX_VALUE;
            for (int side = 0; side < 4; side++) {
               lowestNeighborSurfaceY = Math.min(
                  lowestNeighborSurfaceY, neighborSurfaceY(surfaces, neighborRing, localX, localZ, side, minimumY, maximumY)
               );
            }

            result[index] = voidCurtainBottomY(bottomY, minimumY, lowestNeighborSurfaceY);
         }
      }

      return result;
   }

   private static int neighborSurfaceY(
      int[] surfaces, int[] neighborRing, int localX, int localZ, int side, int minimumY, int maximumY
   ) {
      int surfaceY = switch (side) {
         case 0 -> localX == 0 ? neighborRing[westRingIndex(localZ)] : surfaces[localZ * CHUNK_SIDE + localX - 1];
         case 1 -> localX == CHUNK_SIDE - 1 ? neighborRing[eastRingIndex(localZ)] : surfaces[localZ * CHUNK_SIDE + localX + 1];
         case 2 -> localZ == 0 ? neighborRing[northRingIndex(localX)] : surfaces[(localZ - 1) * CHUNK_SIDE + localX];
         default -> localZ == CHUNK_SIDE - 1 ? neighborRing[southRingIndex(localX)] : surfaces[(localZ + 1) * CHUNK_SIDE + localX];
      };
      return clamp(surfaceY, minimumY, maximumY);
   }

   private static int clamp(int value, int minimum, int maximum) {
      return Math.max(minimum, Math.min(maximum, value));
   }

   public static int supportBottomY(int surfaceY, int terrainDepth, int minimumY) {
      long bottomY = (long)surfaceY - Math.max(0, terrainDepth);
      return (int)Math.max(minimumY, bottomY);
   }

   /**
    * Returns the highest block in the current column that must become bedrock
    * to cover void below an adjacent column. A result at or below the current
    * support bottom means the ordinary bottom bedrock layer is sufficient.
    */
   public static int sideSkinTopY(int supportBottomY, int maximumY, int highestNeighborSupportBottomY) {
      if (highestNeighborSupportBottomY <= supportBottomY) {
         return supportBottomY;
      }

      long exposedTopY = (long)highestNeighborSupportBottomY - 1L;
      return (int)Math.min(maximumY, Math.max(Integer.MIN_VALUE, exposedTopY));
   }

   /**
    * Returns the first bedrock block in a curtain hanging from this column's
    * bottom. The curtain closes an air gap when a neighboring surface ends
    * before this column's terrain shell begins.
    */
   public static int voidCurtainBottomY(int supportBottomY, int minimumY, int lowestNeighborSurfaceY) {
      long curtainBottomY = (long)lowestNeighborSurfaceY + 1L;
      if (curtainBottomY >= supportBottomY) {
         return supportBottomY;
      }

      return (int)Math.max(minimumY, curtainBottomY);
   }
}
