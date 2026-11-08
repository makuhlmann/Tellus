package com.yucareux.tellus.world.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.yucareux.tellus.worldgen.MapEdgeWrap;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class MapEdgeLandingTest {
   @Test
   void walkersLandOnTheGroundWhetherItIsHigherOrLower() {
      // Left standing on ground at Y 120; the far side's ground is 30 blocks lower, then 12 higher.
      double clearance = 120.0 - 120;
      assertEquals(90.0, MapEdgeLanding.targetY(120.0, clearance, 90, -640), 0.0);
      assertEquals(132.0, MapEdgeLanding.targetY(120.0, clearance, 132, -640), 0.0);
   }

   @Test
   void flyersBoatsAndSwimmersKeepTheirHeightAboveTheGround() {
      // Gliding 40 blocks above the ground lands 40 blocks above the far side's ground.
      assertEquals(104.0 + 40.0, MapEdgeLanding.targetY(250.0, 40.0, 104, -640), 0.0);
      // A boat sits a little below the top of the water column; so it does on the far side.
      assertEquals(63.6, MapEdgeLanding.targetY(63.6, 63.6 - 64, 64, -640), 1.0E-9);
      // A swimmer three blocks down stays three blocks down.
      assertEquals(61.0, MapEdgeLanding.targetY(61.0, -3.0, 64, -640), 0.0);
   }

   @Test
   void unmeasuredHeightsAreKeptAndNothingLandsBelowTheWorld() {
      assertEquals(77.5, MapEdgeLanding.targetY(77.5, Double.NaN, 300, -640), 0.0);
      assertEquals(-640.0, MapEdgeLanding.targetY(10.0, -2000.0, -100, -640), 0.0);
   }

   @Test
   void landingsInsideBlocksAreLiftedOnTopOfThem() {
      assertEquals(70.0, MapEdgeLanding.liftedY(64.0, 70), 0.0);
      assertEquals(80.0, MapEdgeLanding.liftedY(80.0, 70), 0.0);
   }

   @Test
   void portalTargetsPastTheMapEdgeAreKeptInsideItAwayFromTheEdges() {
      MapEdgeWrap wrap = MapEdgeWrap.forWorld(1000.0, true);
      // A Nether portal at X 3000 leads to overworld X 24,000; the map ends at 20,032.
      BlockPos far = new BlockPos(24_000, 70, -30_000);
      BlockPos inside = MapEdgeBorders.clamp(wrap, far);
      assertEquals(wrap.maxXExclusive() - (int)MapEdgeWrap.SAFE_MARGIN, inside.getX());
      assertEquals(wrap.minZ() + (int)MapEdgeWrap.SAFE_MARGIN, inside.getZ());
      assertEquals(70, inside.getY());
      assertEquals(false, wrap.triggers(inside.getX() + 0.5, inside.getZ() + 0.5, 0.3));
      assertNull(MapEdgeBorders.clamp(wrap, new BlockPos(1000, 70, -500)));
      assertNull(MapEdgeBorders.clamp(MapEdgeWrap.DISABLED, far));
   }
}
