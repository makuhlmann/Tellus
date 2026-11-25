package com.yucareux.tellus.worldgen.caves;

import net.minecraft.util.Mth;

/**
 * Maps vanilla's fixed Overworld underground profile into the solid material
 * available below a Tellus terrain column.
 */
public final class TellusCaveDepthMapper {
   public static final int VANILLA_MIN_Y = -64;
   public static final int VANILLA_SEA_LEVEL = 63;
   public static final int VANILLA_MAX_Y = 319;
   /**
    * When the Tellus shell is deeper than the vanilla column, this many blocks below the surface keep vanilla's
    * proportions, so cave entrances keep their vanilla size and slope instead of turning into deep shafts.
    */
   public static final int UNSTRETCHED_SURFACE_DEPTH = 48;

   private TellusCaveDepthMapper() {
   }

   /**
    * Converts an actual Tellus Y inside a terrain column to the equivalent Y
    * in a vanilla density column, for caves. When the Tellus shell is shallower
    * than the vanilla column, the profile is compressed without moving its
    * endpoints. When it is deeper, about the top {@link #UNSTRETCHED_SURFACE_DEPTH}
    * blocks map one to one (fewer when the vanilla column is under 144 blocks
    * deep), the next as many blocks ease into the stretch, and the rest of the
    * profile is stretched evenly down to the shell's bottom.
    */
   public static int virtualYForActualY(
      int actualY,
      int actualSurfaceY,
      int actualBottomY,
      int virtualSurfaceY,
      int virtualBottomY
   ) {
      int actualDepthRange = actualSurfaceY - actualBottomY;
      int virtualDepthRange = virtualSurfaceY - virtualBottomY;
      if (actualDepthRange <= virtualDepthRange || virtualDepthRange <= 0) {
         return uniformVirtualYForActualY(actualY, actualSurfaceY, actualBottomY, virtualSurfaceY, virtualBottomY);
      }

      double depth = Mth.clamp((double)(actualSurfaceY - actualY), 0.0, (double)actualDepthRange);
      double virtualDepth = stretchedVirtualDepth(depth, actualDepthRange, virtualDepthRange);
      return Mth.clamp(virtualSurfaceY - Mth.floor(virtualDepth + 0.5), virtualBottomY, virtualSurfaceY);
   }

   /**
    * Converts an actual Tellus Y to the equivalent vanilla Y by scaling the
    * whole profile evenly. Rock features such as ore veins use this, so they
    * stay aligned with the deepslate boundary from {@link #actualDeepslateBoundaryY}.
    */
   public static int uniformVirtualYForActualY(
      int actualY,
      int actualSurfaceY,
      int actualBottomY,
      int virtualSurfaceY,
      int virtualBottomY
   ) {
      int actualDepth = actualSurfaceY - actualY;
      int actualDepthRange = actualSurfaceY - actualBottomY;
      int virtualDepthRange = virtualSurfaceY - virtualBottomY;
      if (actualDepthRange <= 0 || virtualDepthRange <= 0) {
         return virtualSurfaceY;
      }

      double fraction = Mth.clamp(actualDepth / (double)actualDepthRange, 0.0, 1.0);
      return Mth.clamp(virtualSurfaceY - Mth.floor(fraction * virtualDepthRange + 0.5), virtualBottomY, virtualSurfaceY);
   }

   /**
    * Virtual depth for an actual depth when the actual shell is deeper than the virtual column. Down to
    * {@code t} blocks the scale is one to one; over the next {@code t} blocks the virtual blocks per actual
    * block fall linearly from 1 to {@code c}; below that they stay at {@code c}. {@code c} is chosen so the
    * shell's bottom lands on the virtual bottom.
    */
   private static double stretchedVirtualDepth(double depth, int actualDepthRange, int virtualDepthRange) {
      double t = Math.min(UNSTRETCHED_SURFACE_DEPTH, virtualDepthRange / 3);
      double c = (virtualDepthRange - 1.5 * t) / (actualDepthRange - 1.5 * t);
      if (depth <= t) {
         return depth;
      }
      if (depth <= 2.0 * t) {
         double u = depth - t;
         return t + u - (1.0 - c) * u * u / (2.0 * t);
      }
      return 1.5 * t + c * (depth - 1.5 * t);
   }

   /**
    * Gives height-range placed features a virtual vanilla surface matching the
    * Tellus column's elevation above sea level.
    */
   public static int virtualSurfaceForTellusColumn(int actualSurfaceY, int tellusSeaLevel) {
      return Mth.clamp(VANILLA_SEA_LEVEL + actualSurfaceY - tellusSeaLevel, VANILLA_SEA_LEVEL, VANILLA_MAX_Y);
   }

   /**
    * Locates vanilla Y=0 inside a Tellus terrain shell. Blocks below the
    * returned Y use deepslate while blocks at and above it use stone.
    */
   public static int actualDeepslateBoundaryY(int actualSurfaceY, int actualBottomY, int tellusSeaLevel) {
      int virtualSurfaceY = virtualSurfaceForTellusColumn(actualSurfaceY, tellusSeaLevel);
      int boundaryY = actualYForVirtualFeature(0, virtualSurfaceY, actualSurfaceY, actualBottomY);
      return boundaryY == Integer.MIN_VALUE ? actualBottomY : boundaryY;
   }

   /**
    * Projects a sampled vanilla feature Y into the usable Tellus underground
    * interval. Returns {@link Integer#MIN_VALUE} when vanilla would place the
    * feature above the corresponding terrain surface.
    */
   public static int actualYForVirtualFeature(
      int virtualY,
      int virtualSurfaceY,
      int actualSurfaceY,
      int actualBottomY
   ) {
      if (virtualY > virtualSurfaceY || actualBottomY >= actualSurfaceY) {
         return Integer.MIN_VALUE;
      }

      int virtualDepthRange = Math.max(1, virtualSurfaceY - VANILLA_MIN_Y);
      int virtualDepth = virtualSurfaceY - Math.max(VANILLA_MIN_Y, virtualY);
      int actualDepthRange = actualSurfaceY - actualBottomY;
      int actualDepth = Math.max(1, Mth.floor(virtualDepth / (double)virtualDepthRange * actualDepthRange + 0.5));
      return Mth.clamp(actualSurfaceY - actualDepth, actualBottomY, actualSurfaceY - 1);
   }
}
