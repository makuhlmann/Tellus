package com.yucareux.tellus.worldgen.caves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class TellusRavinePlacementTest {
   private static final int MIN_Y = -2_048;
   private static final int MAX_Y = 4_095;

   @Test
   void startsRavinesBetween16And97BlocksBelowTheAnchor() {
      TellusRavinePlacement.Band band = TellusRavinePlacement.band(100, MIN_Y, MAX_Y, 256);

      assertEquals(3, band.floorY());
      assertEquals(84, band.ceilingY());
      assertEquals(-155, band.shellFloorY());
   }

   @Test
   void keepsTheBandInsideAShallowUndergroundLayer() {
      TellusRavinePlacement.Band band = TellusRavinePlacement.band(100, MIN_Y, MAX_Y, 64);

      assertEquals(37, band.shellFloorY());
      assertEquals(37, band.floorY());
      assertEquals(84, band.ceilingY());
   }

   @Test
   void neverReturnsAnEmptyBandAtTheWorldEdges() {
      TellusRavinePlacement.Band nearBottom = TellusRavinePlacement.band(MIN_Y + 5, MIN_Y, MAX_Y, 256);
      assertEquals(MIN_Y, nearBottom.floorY());
      assertEquals(MIN_Y, nearBottom.ceilingY());

      TellusRavinePlacement.Band aboveTop = TellusRavinePlacement.band(MAX_Y + 500, MIN_Y, MAX_Y, 256);
      assertEquals(MAX_Y - TellusRavinePlacement.SURFACE_CLEARANCE, aboveTop.ceilingY());
      assertTrue(aboveTop.floorY() <= aboveTop.ceilingY());
   }

   @Test
   void findsTheLowestSurfaceOnASlope() {
      IntBinaryOperator slope = (x, z) -> 200 - x / 4;

      assertEquals(200 - TellusRavinePlacement.REACH_BLOCKS / 4, TellusRavinePlacement.scanLowestSurface(0, 0, 200, slope));
   }

   @Test
   void findsAValleyInsideTheReachButIgnoresOneBeyondIt() {
      IntBinaryOperator nearValley = (x, z) -> x >= 96 && x <= 104 ? 150 : 200;
      IntBinaryOperator farValley = (x, z) -> x >= 136 && x <= 144 ? 150 : 200;

      assertEquals(150, TellusRavinePlacement.scanLowestSurface(0, 0, 200, nearValley));
      assertEquals(200, TellusRavinePlacement.scanLowestSurface(0, 0, 200, farValley));
   }

   @Test
   void neverAnchorsAboveTheSourceChunkSurface() {
      IntBinaryOperator hillsAround = (x, z) -> 300;

      assertEquals(120, TellusRavinePlacement.scanLowestSurface(0, 0, 120, hillsAround));
   }

   @Test
   void scansFromTheSourceChunkCentreAndCachesTheResult() {
      AtomicInteger samples = new AtomicInteger();
      int[] firstSample = new int[2];
      IntBinaryOperator sampler = (x, z) -> {
         if (samples.getAndIncrement() == 0) {
            firstSample[0] = x;
            firstSample[1] = z;
         }
         return 64;
      };
      TellusRavinePlacement placement = new TellusRavinePlacement();

      assertEquals(64, placement.lowestSurfaceInReach(-1, 2, 80, sampler));
      int samplesAfterFirstScan = samples.get();
      assertTrue(samplesAfterFirstScan > 0);
      // Chunk -1 spans x -16..-1 and chunk 2 spans z 32..47, so the scan is centred on (-8, 40). The first
      // sample inside the circular reach is straight north of the centre, at the edge of the reach.
      assertEquals(-8, firstSample[0]);
      assertEquals(40 - TellusRavinePlacement.REACH_BLOCKS, firstSample[1]);

      assertEquals(64, placement.lowestSurfaceInReach(-1, 2, 80, sampler));
      assertEquals(samplesAfterFirstScan, samples.get());
   }
}
