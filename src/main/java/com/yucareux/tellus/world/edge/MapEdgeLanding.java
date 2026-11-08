package com.yucareux.tellus.world.edge;

/**
 * Where a crossing comes down vertically. The two sides of a crossing are a few dozen blocks apart (over the pole,
 * half the world), so the ground can lie higher or lower than where the player left. The player keeps their height
 * above the ground: walkers land on it, boats on the water, flyers at the same clearance.
 */
public final class MapEdgeLanding {
   private MapEdgeLanding() {
   }

   /**
    * The landing height for an entity that left at {@code clearance} blocks above the ground ({@code NaN} when that
    * could not be measured: the absolute height is kept).
    *
    * @param groundY the first free height above the ground at the landing column (water counts as ground)
    * @param minY the world's lowest block
    */
   public static double targetY(double currentY, double clearance, int groundY, int minY) {
      if (!Double.isFinite(clearance)) {
         return currentY;
      }

      return Math.max(minY, groundY + clearance);
   }

   /**
    * The height to lift to when {@link #targetY} is inside blocks: the top of everything solid there, leaves included.
    * Never lower than the target itself.
    */
   public static double liftedY(double targetY, int solidTopY) {
      return Math.max(targetY, solidTopY);
   }
}
