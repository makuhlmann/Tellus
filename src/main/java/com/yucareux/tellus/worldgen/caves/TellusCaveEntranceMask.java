package com.yucareux.tellus.worldgen.caves;

/**
 * Decides where noise caves may open to the surface when "caves reach surface" is on.
 *
 * <p>Tellus projects vanilla's noise caves under every column, so their surface openings come at vanilla's
 * frequency everywhere. This mask keeps them in about {@link #OPEN_SHARE} of the land and leaves the usual
 * four-block roof over caves elsewhere. It is a smooth value noise over {@link #CELL_BLOCKS}-block cells, so the
 * open areas are patches of varying size rather than a visible grid, and an opening that crosses an edge ends in
 * an overhang instead of a straight cut.
 */
public final class TellusCaveEntranceMask {
   static final int CELL_BLOCKS = 128;
   /** Share of the land where noise caves may open; {@link #OPEN_THRESHOLD} is calibrated for it. */
   static final double OPEN_SHARE = 0.05;
   /** 95th percentile of {@link #value}: smoothstep-bilinear interpolation of uniform cell values. */
   static final double OPEN_THRESHOLD = 0.852;
   private static final long SALT = 0x5DEECE66DL * 31L + 0x2F0A_7C3BL;

   private TellusCaveEntranceMask() {
   }

   public static boolean allowsOpening(long worldSeed, int blockX, int blockZ) {
      return value(worldSeed, blockX, blockZ) >= OPEN_THRESHOLD;
   }

   /** Smooth noise in [0, 1) that changes over about {@link #CELL_BLOCKS} blocks. */
   static double value(long worldSeed, int blockX, int blockZ) {
      int cellX = Math.floorDiv(blockX, CELL_BLOCKS);
      int cellZ = Math.floorDiv(blockZ, CELL_BLOCKS);
      double fx = smoothstep((blockX - (double)cellX * CELL_BLOCKS) / CELL_BLOCKS);
      double fz = smoothstep((blockZ - (double)cellZ * CELL_BLOCKS) / CELL_BLOCKS);
      double north = lerp(cellValue(worldSeed, cellX, cellZ), cellValue(worldSeed, cellX + 1, cellZ), fx);
      double south = lerp(cellValue(worldSeed, cellX, cellZ + 1), cellValue(worldSeed, cellX + 1, cellZ + 1), fx);
      return lerp(north, south, fz);
   }

   private static double cellValue(long worldSeed, int cellX, int cellZ) {
      long h = worldSeed ^ SALT;
      h = mix(h + cellX * 0x9E3779B97F4A7C15L);
      h = mix(h + cellZ * 0xC2B2AE3D27D4EB4FL);
      return (h >>> 11) * 0x1.0p-53;
   }

   private static long mix(long z) {
      z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
      z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
      return z ^ (z >>> 31);
   }

   private static double smoothstep(double t) {
      return t * t * (3.0 - 2.0 * t);
   }

   private static double lerp(double a, double b, double t) {
      return a + (b - a) * t;
   }
}
