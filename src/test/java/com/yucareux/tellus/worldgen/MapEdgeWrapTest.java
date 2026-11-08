package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class MapEdgeWrapTest {
   private static final double SCALE = 10.0;
   private static final MapEdgeWrap WRAP = MapEdgeWrap.forWorld(SCALE, true);
   /** A player's hitbox is 0.6 blocks wide. */
   private static final double PLAYER = 0.3;
   private static final double TRIGGER = MapEdgeWrap.TRIGGER_GAP;
   private static final double LANDING = MapEdgeWrap.LANDING_GAP;

   @Test
   void eastAndWestEndAtTheMeridianSnappedInwardToWholeChunks() {
      assertTrue(WRAP.enabled());
      assertEquals(-2_003_744, WRAP.minX());
      assertEquals(2_003_744, WRAP.maxXExclusive());
      assertEquals(0, WRAP.minX() & 15);
   }

   @Test
   void northAndSouthEndWhereTheMapDataDoes() {
      // At 1:10 Overture's ocean ends at Z -2,003,261.65 and Mapterhorn's elevation at 1,997,186.9 (85°S); its last
      // pixels blend into zeros, so the last chunk with real terrain is 124,822.
      assertEquals(85.047336, MapEdgeWrap.OVERTURE_NORTH_LIMIT, 1.0E-6);
      assertEquals(-2_003_261.65, EarthProjection.latToBlockZ(MapEdgeWrap.OVERTURE_NORTH_LIMIT, SCALE), 0.01);
      assertEquals(-125_203, WRAP.minZ() >> 4);
      assertEquals(124_822, (WRAP.maxZExclusive() >> 4) - 1);
      assertEquals(0, WRAP.maxZExclusive() & 15);
      // The data ends short of the projection's square edge on both sides.
      assertTrue(-WRAP.minZ() < EarthProjection.halfHeightBlocks(SCALE));
      assertTrue(WRAP.maxZExclusive() < EarthProjection.latToBlockZ(-MapEdgeWrap.MAPTERHORN_SOUTH_LIMIT, SCALE));
   }

   @Test
   void fullScaleFitsThePackedRangeButSmallerScalesDoNot() {
      MapEdgeWrap fullScale = MapEdgeWrap.forWorld(1.0, true);
      assertTrue(fullScale.enabled());
      assertEquals(20_037_504, fullScale.maxXExclusive());
      assertTrue(fullScale.maxXExclusive() <= HighYPackedCoordinateProfile.X_MAX);
      assertTrue(fullScale.minX() - 1 >= HighYPackedCoordinateProfile.X_MIN);

      assertFalse(MapEdgeWrap.forWorld(0.5, true).enabled());
      assertTrue(MapEdgeWrap.forWorld(1000.0, true).enabled());
      assertSame(MapEdgeWrap.DISABLED, MapEdgeWrap.forWorld(10.0, false));
   }

   @Test
   void explicitExtentsSnapEachCutInward() {
      MapEdgeWrap wrap = MapEdgeWrap.of(1000.7, -900.2, 801.9, 5.0, 85.0, 84.0, MapEdgeWrap.PACKED_LIMIT);
      assertEquals(-992, wrap.minX());
      assertEquals(992, wrap.maxXExclusive());
      assertEquals(-896, wrap.minZ());
      assertEquals(800, wrap.maxZExclusive());
   }

   @Test
   void pastCutPredicatesAgreeAtEveryBoundary() {
      int max = WRAP.maxXExclusive();
      int min = WRAP.minX();
      assertFalse(WRAP.isPastCut(max - 1, 0));
      assertTrue(WRAP.isPastCut(max, 0));
      assertFalse(WRAP.isPastCut(min, 0));
      assertTrue(WRAP.isPastCut(min - 1, 0));
      assertFalse(WRAP.isPastCut(0, WRAP.minZ()));
      assertTrue(WRAP.isPastCut(0, WRAP.minZ() - 1));
      assertFalse(WRAP.isPastCut(0, WRAP.maxZExclusive() - 1));
      assertTrue(WRAP.isPastCut(0, WRAP.maxZExclusive()));
      assertTrue(WRAP.isPastCut(max + 0.001, 0.0));
      assertFalse(MapEdgeWrap.DISABLED.isPastCut(Integer.MAX_VALUE, 0));

      int[] edgeChunksX = {(max >> 4) - 1, max >> 4, (min >> 4) - 1, min >> 4};
      int[] edgeChunksZ = {(WRAP.minZ() >> 4) - 1, WRAP.minZ() >> 4, (WRAP.maxZExclusive() >> 4) - 1, WRAP.maxZExclusive() >> 4};
      for (int chunkX : edgeChunksX) {
         for (int chunkZ : edgeChunksZ) {
            boolean chunkPast = WRAP.isChunkPastCut(chunkX, chunkZ);
            for (int local = 0; local < 256; local++) {
               int x = (chunkX << 4) + (local & 15);
               int z = (chunkZ << 4) + (local >> 4);
               assertEquals(chunkPast, WRAP.isPastCut(x, z), "chunk " + chunkX + "," + chunkZ + " column " + x + "," + z);
            }
         }
      }
   }

   @Test
   void barrierIsTheFirstColumnOrRowPastEachCutIncludingCorners() {
      int max = WRAP.maxXExclusive();
      int min = WRAP.minX();
      assertTrue(WRAP.isBarrier(max, 0));
      assertTrue(WRAP.isBarrier(min - 1, 0));
      assertTrue(WRAP.isBarrier(0, WRAP.minZ() - 1));
      assertTrue(WRAP.isBarrier(0, WRAP.maxZExclusive()));
      assertTrue(WRAP.isBarrier(max, WRAP.maxZExclusive()));
      assertTrue(WRAP.isBarrier(min - 1, WRAP.minZ() - 1));
      assertFalse(WRAP.isBarrier(max - 1, 0));
      assertFalse(WRAP.isBarrier(max + 1, 0));
      assertFalse(WRAP.isBarrier(max, WRAP.maxZExclusive() + 1));

      assertTrue(WRAP.chunkHasBarrier(max >> 4, 0));
      assertTrue(WRAP.chunkHasBarrier((min >> 4) - 1, 0));
      assertTrue(WRAP.chunkHasBarrier(0, (WRAP.minZ() >> 4) - 1));
      assertFalse(WRAP.chunkHasBarrier((max >> 4) + 1, 0));
      assertFalse(WRAP.chunkHasBarrier((max >> 4) - 1, 0));
   }

   @Test
   void aPlayerIsMovedOnlyWhenTheirHitboxAlmostTouchesTheEdge() {
      int max = WRAP.maxXExclusive();
      // The barrier stops the hitbox at the cut; half a block before that the player is moved.
      assertFalse(WRAP.triggers(max - TRIGGER - PLAYER - 0.001, 0.0, PLAYER));
      assertTrue(WRAP.triggers(max - TRIGGER - PLAYER + 0.001, 0.0, PLAYER));
      assertTrue(WRAP.triggers(max - PLAYER, 0.0, PLAYER));
      assertTrue(WRAP.triggers(WRAP.minX() + PLAYER + 0.4, 0.0, PLAYER));
      assertFalse(WRAP.triggers(WRAP.minX() + PLAYER + 0.6, 0.0, PLAYER));
      assertTrue(WRAP.triggers(0.0, WRAP.minZ() + PLAYER + 0.4, PLAYER));
      assertTrue(WRAP.triggers(0.0, WRAP.maxZExclusive() - PLAYER - 0.4, PLAYER));
      assertNull(WRAP.cross(0.0, 0.0, PLAYER, 1.0, 0.0, 0.0F));
   }

   @Test
   void wideVehiclesAreMovedBeforeTheirHitboxReachesTheBarrier() {
      // A 4-block-wide mount stops with its centre two blocks from the cut, and is moved there too.
      double halfWidth = 2.0;
      double stopped = WRAP.maxXExclusive() - halfWidth;
      assertTrue(WRAP.triggers(stopped, 0.0, halfWidth));
      MapEdgeWrap.Crossing crossing = WRAP.cross(stopped, 0.0, halfWidth, 0.2, 0.0, -90.0F);
      assertNotNull(crossing);
      assertEquals(WRAP.minX() + LANDING + halfWidth, crossing.x(), 1.0E-9);
      assertFalse(WRAP.triggers(crossing.x(), crossing.z(), halfWidth));
   }

   @Test
   void eastWestCrossingLandsJustInsideTheOtherSideKeepingYaw() {
      MapEdgeWrap.Crossing east = WRAP.cross(WRAP.maxXExclusive() - PLAYER - 0.1, 1234.5, PLAYER, 0.3, 0.1, -90.0F);
      assertNotNull(east);
      assertEquals(MapEdgeWrap.Crossing.Kind.EAST, east.kind());
      assertEquals(WRAP.minX() + LANDING + PLAYER, east.x(), 1.0E-9);
      assertEquals(1234.5, east.z(), 1.0E-9);
      assertEquals(-90.0F, east.yaw());
      assertFalse(east.mirror());

      MapEdgeWrap.Crossing west = WRAP.cross(WRAP.minX() + PLAYER + 0.2, -77.0, PLAYER, -0.3, 0.0, 90.0F);
      assertNotNull(west);
      assertEquals(MapEdgeWrap.Crossing.Kind.WEST, west.kind());
      assertEquals(WRAP.maxXExclusive() - LANDING - PLAYER, west.x(), 1.0E-9);
   }

   @Test
   void positionsFarPastTheCutMapGeographically() {
      double x = WRAP.maxXExclusive() + 1000.0;
      MapEdgeWrap.Crossing crossing = WRAP.cross(x, 0.0, PLAYER, 0.0, 0.0, 0.0F);
      assertNotNull(crossing);
      assertEquals(x - 360.0 * EarthProjection.blocksPerDegree(SCALE), crossing.x(), 1.0E-6);
      double longitude = EarthProjection.blockXToLongitude(x, SCALE) - 360.0;
      assertEquals(longitude, EarthProjection.blockXToLongitude(crossing.x(), SCALE), 1.0E-9);
   }

   @Test
   void positionsFarPastAPolarEdgeComeBackInOnTheFarSide() {
      // A spectator, or a flyer above the barrier, can be far past the cut before the next check.
      double blocksPerDegree = EarthProjection.blocksPerDegree(SCALE);
      MapEdgeWrap.Crossing north = WRAP.cross(30.0 * blocksPerDegree, WRAP.minZ() - 1000.0, PLAYER, 0.0, 0.0, 180.0F);
      assertNotNull(north);
      assertEquals(MapEdgeWrap.Crossing.Kind.NORTH, north.kind());
      assertEquals(-150.0, north.x() / blocksPerDegree, 1.0E-9);
      assertEquals(WRAP.minZ() + LANDING + PLAYER, north.z(), 1.0E-9);
      assertFalse(WRAP.triggers(north.x(), north.z(), PLAYER));

      MapEdgeWrap.Crossing south = WRAP.cross(-60.0 * blocksPerDegree, WRAP.maxZExclusive() + 5000.0, PLAYER, 0.0, 0.0, 0.0F);
      assertNotNull(south);
      assertEquals(MapEdgeWrap.Crossing.Kind.SOUTH, south.kind());
      assertEquals(120.0, south.x() / blocksPerDegree, 1.0E-9);
      assertEquals(WRAP.maxZExclusive() - LANDING - PLAYER, south.z(), 1.0E-9);
      assertFalse(WRAP.triggers(south.x(), south.z(), PLAYER));
   }

   @Test
   void greatCircleAcrossThePolarCapMatchesAVectorMarch() {
      for (double latitude : new double[]{MapEdgeWrap.OVERTURE_NORTH_LIMIT, MapEdgeWrap.MAPTERHORN_SOUTH_LIMIT}) {
         assertEquals(Math.PI, MapEdgeWrap.longitudeAcrossCap(latitude, 0.0), 1.0E-12);
         assertEquals(0.0, MapEdgeWrap.longitudeAcrossCap(latitude, Math.PI / 2.0), 1.0E-12);
         for (int degrees = -89; degrees <= 89; degrees += 7) {
            double heading = Math.toRadians(degrees);
            assertEquals(
               vectorExitLongitude(latitude, heading),
               Math.toDegrees(MapEdgeWrap.longitudeAcrossCap(latitude, heading)),
               1.0E-9,
               "latitude " + latitude + ", heading " + degrees
            );
            assertEquals(180.0 - 2.0 * degrees, Math.toDegrees(MapEdgeWrap.longitudeAcrossCap(latitude, heading)), 0.25);
         }
      }

      assertEquals(90.214, Math.toDegrees(MapEdgeWrap.longitudeAcrossCap(EarthProjection.MAX_MERCATOR_LATITUDE, Math.PI / 4.0)), 1.0E-3);
      // At the pole itself (legacy projection) the cap has no size: the planar rule is exact.
      assertEquals(180.0 - 2.0 * 30.0, Math.toDegrees(MapEdgeWrap.longitudeAcrossCap(90.0, Math.toRadians(30.0))), 1.0E-9);
   }

   /** Exit longitude (degrees, relative to the entry meridian) of the great circle entering the cap at {@code heading}. */
   private static double vectorExitLongitude(double latitude, double heading) {
      double phi = Math.toRadians(latitude);
      double[] position = {Math.cos(phi), 0.0, Math.sin(phi)};
      double[] north = {-Math.sin(phi), 0.0, Math.cos(phi)};
      double[] east = {0.0, 1.0, 0.0};
      double[] direction = new double[3];
      for (int i = 0; i < 3; i++) {
         direction[i] = Math.cos(heading) * north[i] + Math.sin(heading) * east[i];
      }

      // z(s) = p.z cos s + d.z sin s returns to sin(phi) at s = 2 * atan2(d.z, p.z).
      double arc = 2.0 * Math.atan2(direction[2], position[2]);
      double exitX = position[0] * Math.cos(arc) + direction[0] * Math.sin(arc);
      double exitY = position[1] * Math.cos(arc) + direction[1] * Math.sin(arc);
      double longitude = Math.toDegrees(Math.atan2(exitY, exitX));
      return longitude < 0.0 ? longitude + 360.0 : longitude;
   }

   @Test
   void northCrossingComesBackDownOnTheFarSideFacingAwayFromThePole() {
      double blocksPerDegree = EarthProjection.blocksPerDegree(SCALE);
      double startX = 10.0 * blocksPerDegree;
      double atEdge = WRAP.minZ() + PLAYER + 0.2;
      MapEdgeWrap.Crossing straight = WRAP.cross(startX, atEdge, PLAYER, 0.0, -0.2, 180.0F);
      assertNotNull(straight);
      assertEquals(MapEdgeWrap.Crossing.Kind.NORTH, straight.kind());
      assertTrue(straight.mirror());
      assertEquals(-170.0, straight.x() / blocksPerDegree, 1.0E-9);
      assertEquals(WRAP.minZ() + LANDING + PLAYER, straight.z(), 1.0E-9);
      assertEquals(0.0F, straight.yaw(), 1.0E-4F);

      // Heading north-east at 45 degrees comes out about 90 degrees further east, heading south-east.
      MapEdgeWrap.Crossing diagonal = WRAP.cross(startX, atEdge, PLAYER, 0.2, -0.2, -135.0F);
      assertNotNull(diagonal);
      double expected = 10.0 + Math.toDegrees(MapEdgeWrap.longitudeAcrossCap(MapEdgeWrap.OVERTURE_NORTH_LIMIT, Math.PI / 4.0));
      assertEquals(expected, diagonal.x() / blocksPerDegree, 1.0E-6);
      assertEquals(100.21, diagonal.x() / blocksPerDegree, 0.01);
      assertEquals(-45.0F, diagonal.yaw(), 1.0E-4F);
   }

   @Test
   void southCrossingUsesTheSouthernCapAndMirrorsTheFacing() {
      double blocksPerDegree = EarthProjection.blocksPerDegree(SCALE);
      double startX = -40.0 * blocksPerDegree;
      MapEdgeWrap.Crossing south = WRAP.cross(startX, WRAP.maxZExclusive() - PLAYER - 0.1, PLAYER, 0.1, 0.1, -45.0F);
      assertNotNull(south);
      assertEquals(MapEdgeWrap.Crossing.Kind.SOUTH, south.kind());
      double expected = -40.0 + Math.toDegrees(MapEdgeWrap.longitudeAcrossCap(MapEdgeWrap.MAPTERHORN_SOUTH_LIMIT, Math.PI / 4.0));
      assertEquals(expected, south.x() / blocksPerDegree, 1.0E-6);
      assertEquals(WRAP.maxZExclusive() - LANDING - PLAYER, south.z(), 1.0E-9);
      assertEquals(MapEdgeWrap.mirrorYaw(-45.0F), south.yaw(), 1.0E-4F);
   }

   @Test
   void turningStraightBackOverThePoleReturnsToTheStartingMeridian() {
      double blocksPerDegree = EarthProjection.blocksPerDegree(SCALE);
      double startX = 25.0 * blocksPerDegree;
      double motionX = 0.07;
      double motionZ = -0.11;
      double atEdge = WRAP.minZ() + PLAYER + 0.1;
      MapEdgeWrap.Crossing out = WRAP.cross(startX, atEdge, PLAYER, motionX, motionZ, 0.0F);
      assertNotNull(out);
      // After the crossing the entity moves (motionX, -motionZ); turning round reverses that.
      MapEdgeWrap.Crossing back = WRAP.cross(out.x(), atEdge, PLAYER, -motionX, motionZ, 0.0F);
      assertNotNull(back);
      assertEquals(startX, back.x(), 1.0E-6);
   }

   @Test
   void standingStillUsesTheFacingAndMovingAwayFromThePoleGoesStraightOver() {
      double blocksPerDegree = EarthProjection.blocksPerDegree(SCALE);
      double atEdge = WRAP.minZ() + PLAYER + 0.1;
      MapEdgeWrap.Crossing facingNorthEast = WRAP.cross(0.0, atEdge, PLAYER, 0.0, 0.0, -135.0F);
      assertNotNull(facingNorthEast);
      assertEquals(90.21, facingNorthEast.x() / blocksPerDegree, 0.01);

      MapEdgeWrap.Crossing movingSouth = WRAP.cross(30.0 * blocksPerDegree, atEdge, PLAYER, 0.0, 0.5, 0.0F);
      assertNotNull(movingSouth);
      assertEquals(-150.0, movingSouth.x() / blocksPerDegree, 1.0E-9);
   }

   @Test
   void cornerCrossingsLandAwayFromEveryEdge() {
      MapEdgeWrap.Crossing corner = WRAP.cross(WRAP.maxXExclusive() - PLAYER, WRAP.minZ() + PLAYER, PLAYER, 0.2, -0.2, -135.0F);
      assertNotNull(corner);
      assertEquals(MapEdgeWrap.Crossing.Kind.NORTH, corner.kind());
      assertFalse(WRAP.triggers(corner.x(), corner.z(), PLAYER));
   }

   @Test
   void landingNeverTriggersAgainUntilTwoBlocksBack() {
      Random random = new Random(1234L);
      for (int i = 0; i < 20_000; i++) {
         double halfWidth = random.nextBoolean() ? PLAYER : 0.3 + random.nextDouble() * 1.7;
         double x;
         double z;
         if (random.nextBoolean()) {
            x = WRAP.minX() - 40.0 + random.nextDouble() * (WRAP.maxXExclusive() - WRAP.minX() + 80.0);
            z = random.nextBoolean()
               ? WRAP.minZ() - 20.0 + random.nextDouble() * (20.0 + halfWidth + TRIGGER)
               : WRAP.maxZExclusive() + 20.0 - random.nextDouble() * (20.0 + halfWidth + TRIGGER);
         } else {
            z = WRAP.minZ() + 10.0 + random.nextDouble() * (WRAP.maxZExclusive() - WRAP.minZ() - 20.0);
            x = random.nextBoolean()
               ? WRAP.minX() - 20.0 + random.nextDouble() * (20.0 + halfWidth + TRIGGER)
               : WRAP.maxXExclusive() + 20.0 - random.nextDouble() * (20.0 + halfWidth + TRIGGER);
         }

         MapEdgeWrap.Crossing crossing = WRAP.cross(x, z, halfWidth, random.nextGaussian(), random.nextGaussian(), random.nextFloat() * 360.0F - 180.0F);
         assertNotNull(crossing, "position " + x + "," + z);
         assertFalse(WRAP.triggers(crossing.x(), crossing.z(), halfWidth), "landing " + crossing);
         assertFalse(WRAP.isPastCut(crossing.x(), crossing.z()));
         double back = LANDING - TRIGGER - 0.01;
         switch (crossing.kind()) {
            case EAST -> assertFalse(WRAP.triggers(crossing.x() - back, crossing.z(), halfWidth));
            case WEST -> assertFalse(WRAP.triggers(crossing.x() + back, crossing.z(), halfWidth));
            case NORTH -> assertFalse(WRAP.triggers(crossing.x(), crossing.z() - back, halfWidth));
            case SOUTH -> assertFalse(WRAP.triggers(crossing.x(), crossing.z() + back, halfWidth));
         }
      }
   }

   @Test
   void spawnsAndMapTeleportsAreKeptAwayFromTheEdges() {
      double x = WRAP.clampAwayFromEdgeX(EarthProjection.longitudeToBlockX(180.0, SCALE));
      double z = WRAP.clampAwayFromEdgeZ(EarthProjection.latToBlockZ(EarthProjection.MAX_MERCATOR_LATITUDE, SCALE));
      assertEquals(WRAP.maxXExclusive() - MapEdgeWrap.SAFE_MARGIN, x, 0.0);
      assertEquals(WRAP.minZ() + MapEdgeWrap.SAFE_MARGIN, z, 0.0);
      assertFalse(WRAP.triggers(x, z, 2.0));
      assertEquals(123.0, WRAP.clampAwayFromEdgeX(123.0), 0.0);
   }

   @Test
   void arrivalsArePredictedAtTheNearestEdgeWithinTheLookahead() {
      double blocksPerDegree = EarthProjection.blocksPerDegree(SCALE);
      // 80 blocks from the east edge, heading east: lands where the crossing itself would.
      double x = WRAP.maxXExclusive() - PLAYER - 80.0;
      MapEdgeWrap.Crossing predicted = WRAP.predict(x, 4321.0, PLAYER, 0.3, 0.0, -90.0F, 96.0);
      assertNotNull(predicted);
      assertEquals(MapEdgeWrap.Crossing.Kind.EAST, predicted.kind());
      assertEquals(WRAP.minX() + LANDING + PLAYER, predicted.x(), 1.0E-9);
      assertEquals(4321.0, predicted.z(), 0.0);
      assertNull(WRAP.predict(x, 4321.0, PLAYER, 0.3, 0.0, -90.0F, 64.0));
      assertNull(WRAP.predict(0.0, 0.0, PLAYER, 0.3, 0.0, -90.0F, 768.0));

      // Near the north edge the landing depends on the heading, as for the crossing.
      double z = WRAP.minZ() + PLAYER + 50.0;
      MapEdgeWrap.Crossing north = WRAP.predict(10.0 * blocksPerDegree, z, PLAYER, 0.2, -0.2, -135.0F, 96.0);
      assertNotNull(north);
      assertEquals(MapEdgeWrap.Crossing.Kind.NORTH, north.kind());
      assertEquals(
         WRAP.cross(10.0 * blocksPerDegree, WRAP.minZ() + PLAYER, PLAYER, 0.2, -0.2, -135.0F).x(), north.x(), 1.0E-9
      );
      assertNull(MapEdgeWrap.DISABLED.predict(x, 0.0, PLAYER, 0.3, 0.0, -90.0F, 1.0E9));
   }

   @Test
   void mirroredYawReversesOnlyTheNorthSouthComponent() {
      for (float yaw = -180.0F; yaw < 180.0F; yaw += 15.0F) {
         float mirrored = MapEdgeWrap.mirrorYaw(yaw);
         assertEquals(-Math.sin(Math.toRadians(yaw)), -Math.sin(Math.toRadians(mirrored)), 1.0E-6);
         assertEquals(-Math.cos(Math.toRadians(yaw)), Math.cos(Math.toRadians(mirrored)), 1.0E-6);
         assertTrue(mirrored >= -180.0F && mirrored < 180.0F);
      }
   }
}
