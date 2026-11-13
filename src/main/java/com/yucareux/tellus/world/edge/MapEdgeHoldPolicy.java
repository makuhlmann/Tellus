package com.yucareux.tellus.world.edge;

/**
 * When to load, keep, move or let go of the area a player near a map edge would land in.
 *
 * <p>Each player holds at most one area. Over the pole the landing point moves by twice every degree the heading
 * turns (at 1:30 that is some 7,400 blocks), so following every change would scatter loads all over the map, each
 * dragging in hundreds of chunks of generation, until the player's own chunks starve and the server stalls waiting
 * for one. So a hold only moves once its area has loaded or {@link #RETARGET_COOLDOWN_TICKS} have passed since it
 * was placed, and the old area is let go first.</p>
 */
public final class MapEdgeHoldPolicy {
   /** How far, in chunks, the landing point may drift before the hold is moved. */
   static final int RETARGET_CHUNKS = 2;
   /** How long a hold stays where it is while its area is still loading. */
   static final int RETARGET_COOLDOWN_TICKS = 100;
   /** How often a kept hold is renewed (its ticket lasts longer than this). */
   static final int REFRESH_TICKS = 20;

   private MapEdgeHoldPolicy() {
   }

   public enum Action {
      /** No hold yet: load the new area. */
      PLACE,
      /** Keep the current area and renew its ticket. */
      REFRESH,
      /** Keep the current area; nothing to do this tick. */
      KEEP,
      /** Let go of the current area and load the new one. */
      MOVE
   }

   /**
    * @param current the player's hold, or {@code null}
    * @param chunkX chunk the player would now land in
    * @param chunkZ chunk the player would now land in
    * @param currentLoaded whether the current hold's centre chunk has finished loading
    */
   public static Action decide(Hold current, int chunkX, int chunkZ, long tick, boolean currentLoaded) {
      if (current == null) {
         return Action.PLACE;
      }

      boolean drifted = Math.abs(current.chunkX() - chunkX) > RETARGET_CHUNKS || Math.abs(current.chunkZ() - chunkZ) > RETARGET_CHUNKS;
      if (drifted && (currentLoaded || tick - current.placedTick() >= RETARGET_COOLDOWN_TICKS)) {
         return Action.MOVE;
      }
      return tick - current.refreshedTick() >= REFRESH_TICKS ? Action.REFRESH : Action.KEEP;
   }

   /** A held area: its centre chunk, when it was placed and when its ticket was last renewed. */
   public record Hold(int chunkX, int chunkZ, long placedTick, long refreshedTick) {
      public Hold refreshed(long tick) {
         return new Hold(this.chunkX, this.chunkZ, this.placedTick, tick);
      }
   }
}
