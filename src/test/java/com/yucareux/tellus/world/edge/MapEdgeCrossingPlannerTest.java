package com.yucareux.tellus.world.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.EarthProjection;
import com.yucareux.tellus.worldgen.MapEdgeWrap;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MapEdgeCrossingPlannerTest {
   private static final MapEdgeWrap WRAP = MapEdgeWrap.forWorld(10.0, true);
   private static final Object LEVEL = new Object();
   private static final UUID PLAYER = UUID.randomUUID();
   private static final double HALF_WIDTH = 0.3;

   @Test
   void walkingIntoTheEastBandMovesOnceAndThenWaitsForArrival() {
      MapEdgeCrossingPlanner planner = new MapEdgeCrossingPlanner();
      double x = WRAP.maxXExclusive() - HALF_WIDTH - MapEdgeWrap.TRIGGER_GAP - 0.2;
      assertNull(planner.plan(PLAYER, LEVEL, 100L, x, 64.0, 0.0, HALF_WIDTH, -90.0F, WRAP));
      MapEdgeCrossingPlanner.Jump jump = planner.plan(PLAYER, LEVEL, 101L, x + 0.3, 64.0, 0.0, HALF_WIDTH, -90.0F, WRAP);
      assertNotNull(jump);
      assertEquals(MapEdgeWrap.Crossing.Kind.EAST, jump.crossing().kind());
      assertEquals(0.3, jump.motionX(), 1.0E-9);
      assertTrue(planner.isPending(PLAYER));

      // Arriving at the landing point: no second move, and after two ticks there the move is done.
      assertNull(planner.plan(PLAYER, LEVEL, 102L, jump.crossing().x(), 64.0, 0.0, HALF_WIDTH, -90.0F, WRAP));
      assertTrue(planner.isPending(PLAYER));
      assertNull(planner.plan(PLAYER, LEVEL, 103L, jump.crossing().x() - 0.2, 64.0, 0.0, HALF_WIDTH, -90.0F, WRAP));
      assertFalse(planner.isPending(PLAYER));
      // Walking straight back does not reach the edge on this side for another two blocks.
      assertNull(planner.plan(PLAYER, LEVEL, 104L, jump.crossing().x() - 0.4, 64.0, 0.0, HALF_WIDTH, 90.0F, WRAP));
   }

   @Test
   void anEntityDraggedBackIsMovedAgainAFewTimesAtMost() {
      MapEdgeCrossingPlanner planner = new MapEdgeCrossingPlanner();
      double x = WRAP.maxXExclusive() - 0.4;
      MapEdgeCrossingPlanner.Jump first = planner.plan(PLAYER, LEVEL, 10L, x, 64.0, 5.0, HALF_WIDTH, 0.0F, WRAP);
      assertNotNull(first);
      int repeats = 0;
      for (long tick = 11L; tick < 20L; tick++) {
         MapEdgeCrossingPlanner.Jump again = planner.plan(PLAYER, LEVEL, tick, x, 64.0, 5.0, HALF_WIDTH, 0.0F, WRAP);
         if (again != null) {
            assertTrue(again.repeat());
            assertEquals(first.crossing(), again.crossing());
            repeats++;
         }
      }
      assertEquals(MapEdgeCrossingPlanner.MAX_REPEATS, repeats);
   }

   @Test
   void pendingMovesExpireSoAStuckEntityIsMovedFreshLater() {
      MapEdgeCrossingPlanner planner = new MapEdgeCrossingPlanner();
      double x = WRAP.maxXExclusive() - 0.4;
      assertNotNull(planner.plan(PLAYER, LEVEL, 10L, x, 64.0, 5.0, HALF_WIDTH, 0.0F, WRAP));
      long expired = 10L + MapEdgeCrossingPlanner.PENDING_TIMEOUT_TICKS + 1L;
      MapEdgeCrossingPlanner.Jump fresh = planner.plan(PLAYER, LEVEL, expired, x, 64.0, 5.0, HALF_WIDTH, 0.0F, WRAP);
      assertNotNull(fresh);
      assertFalse(fresh.repeat());
   }

   @Test
   void motionIsMeasuredOnlyBetweenConsecutiveTicksInOneLevel() {
      MapEdgeCrossingPlanner planner = new MapEdgeCrossingPlanner();
      double blocksPerDegree = EarthProjection.blocksPerDegree(10.0);
      double z = WRAP.minZ() + HALF_WIDTH + MapEdgeWrap.TRIGGER_GAP + 0.1;
      double x = 20.0 * blocksPerDegree;
      assertNull(planner.plan(PLAYER, LEVEL, 5L, x, 64.0, z, HALF_WIDTH, 180.0F, WRAP));
      // North-east at 45 degrees: comes out about 90 degrees further east.
      MapEdgeCrossingPlanner.Jump jump = planner.plan(PLAYER, LEVEL, 6L, x + 0.2, 64.0, z - 0.2, HALF_WIDTH, 180.0F, WRAP);
      assertNotNull(jump);
      assertEquals(MapEdgeWrap.Crossing.Kind.NORTH, jump.crossing().kind());
      assertEquals(20.0 + 90.214, jump.crossing().x() / blocksPerDegree, 1.0E-2);

      MapEdgeCrossingPlanner other = new MapEdgeCrossingPlanner();
      assertNull(other.plan(PLAYER, new Object(), 5L, x, 64.0, z, HALF_WIDTH, 180.0F, WRAP));
      // A sample from another level is not motion: the heading falls back to the yaw (due north here).
      MapEdgeCrossingPlanner.Jump fromYaw = other.plan(PLAYER, LEVEL, 6L, x + 0.2, 64.0, z - 0.2, HALF_WIDTH, 180.0F, WRAP);
      assertNotNull(fromYaw);
      assertEquals(0.0, fromYaw.motionX(), 0.0);
      assertEquals(20.0 - 180.0, fromYaw.crossing().x() / blocksPerDegree, 1.0E-2);
   }

   @Test
   void theLastMotionIsKeptForLoadingAhead() {
      MapEdgeCrossingPlanner planner = new MapEdgeCrossingPlanner();
      assertEquals(0.0, planner.motion(PLAYER).x(), 0.0);
      assertNull(planner.plan(PLAYER, LEVEL, 1L, 100.0, 64.0, 200.0, HALF_WIDTH, 0.0F, WRAP));
      assertNull(planner.plan(PLAYER, LEVEL, 2L, 100.4, 64.0, 199.9, HALF_WIDTH, 0.0F, WRAP));
      assertEquals(0.4, planner.motion(PLAYER).x(), 1.0E-9);
      assertEquals(-0.1, planner.motion(PLAYER).z(), 1.0E-9);
      planner.forget(PLAYER);
      assertEquals(0.0, planner.motion(PLAYER).z(), 0.0);
   }

   @Test
   void aTeleportIsNotMotion() {
      // A map teleport moves a player hundreds of thousands of blocks in one tick; that says nothing about a heading.
      MapEdgeCrossingPlanner planner = new MapEdgeCrossingPlanner();
      assertNull(planner.plan(PLAYER, LEVEL, 1L, 387_000.0, 64.0, -224_216.0, HALF_WIDTH, 0.0F, WRAP));
      assertNull(planner.plan(PLAYER, LEVEL, 2L, 86.0, 64.0, WRAP.minZ() + 300.0, HALF_WIDTH, 0.0F, WRAP));
      assertEquals(0.0, planner.motion(PLAYER).x(), 0.0);
      assertEquals(0.0, planner.motion(PLAYER).z(), 0.0);
      assertNull(planner.plan(PLAYER, LEVEL, 3L, 86.0, 64.0, WRAP.minZ() + 299.8, HALF_WIDTH, 0.0F, WRAP));
      assertEquals(-0.2, planner.motion(PLAYER).z(), 1.0E-9);
   }

   @Test
   void motionIsClampedAndForgottenEntitiesStartAfresh() {
      MapEdgeCrossingPlanner planner = new MapEdgeCrossingPlanner();
      // Six blocks in a tick: faster than any client reports, but no teleport.
      double x = WRAP.minX() + 6.5;
      assertNull(planner.plan(PLAYER, LEVEL, 1L, x, 64.0, 0.0, HALF_WIDTH, 90.0F, WRAP));
      MapEdgeCrossingPlanner.Jump jump = planner.plan(PLAYER, LEVEL, 2L, WRAP.minX() + 0.5, 64.0, 0.0, HALF_WIDTH, 90.0F, WRAP);
      assertNotNull(jump);
      assertEquals(-MapEdgeCrossingPlanner.MAX_MOTION_PER_TICK, jump.motionX(), 0.0);

      planner.retainOnly(Set.of());
      assertFalse(planner.isPending(PLAYER));
      assertNotNull(planner.plan(PLAYER, LEVEL, 3L, WRAP.minX() + 0.5, 64.0, 0.0, HALF_WIDTH, 90.0F, WRAP));
   }
}
