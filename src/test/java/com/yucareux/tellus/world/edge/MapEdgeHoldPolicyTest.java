package com.yucareux.tellus.world.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.world.edge.MapEdgeHoldPolicy.Action;
import com.yucareux.tellus.world.edge.MapEdgeHoldPolicy.Hold;
import org.junit.jupiter.api.Test;

class MapEdgeHoldPolicyTest {
   @Test
   void theFirstLandingPointIsLoadedAndThenRenewed() {
      assertEquals(Action.PLACE, MapEdgeHoldPolicy.decide(null, 10, 20, 100L, false));
      Hold hold = new Hold(10, 20, 100L, 100L);
      assertEquals(Action.KEEP, MapEdgeHoldPolicy.decide(hold, 11, 21, 110L, false));
      assertEquals(Action.REFRESH, MapEdgeHoldPolicy.decide(hold, 11, 21, 100L + MapEdgeHoldPolicy.REFRESH_TICKS, false));
   }

   @Test
   void aSwingingLandingPointDoesNotScatterLoadsAcrossTheMap() {
      // Over the pole, turning the heading moves the landing point thousands of blocks each tick. While the area is
      // still loading, the hold stays put until the cooldown passes: at most one new area every five seconds.
      Hold hold = new Hold(0, -41_734, 0L, 0L);
      int moves = 0;
      Hold current = hold;
      for (long tick = 1; tick <= 1200; tick++) {
         int target = (int)(tick * 463 % 50_000) - 25_000;
         Action action = MapEdgeHoldPolicy.decide(current, target, -41_734, tick, false);
         if (action == Action.MOVE) {
            moves++;
            current = new Hold(target, -41_734, tick, tick);
         } else if (action == Action.REFRESH) {
            current = current.refreshed(tick);
         }
         assertTrue(action != Action.PLACE);
      }
      assertEquals(1200 / MapEdgeHoldPolicy.RETARGET_COOLDOWN_TICKS, moves);
   }

   @Test
   void aLoadedAreaMovesWithTheLandingPointAtOnce() {
      Hold hold = new Hold(10, 20, 100L, 100L);
      assertEquals(Action.MOVE, MapEdgeHoldPolicy.decide(hold, 10 + MapEdgeHoldPolicy.RETARGET_CHUNKS + 1, 20, 101L, true));
      // Small drift (walking along the edge) keeps the area.
      assertEquals(Action.KEEP, MapEdgeHoldPolicy.decide(hold, 10 + MapEdgeHoldPolicy.RETARGET_CHUNKS, 20, 101L, true));
   }
}
