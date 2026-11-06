package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.IntBinaryOperator;
import org.junit.jupiter.api.Test;

class TerrainShellBedrockProtectionTest {
   private static final int DEPTH = 256;
   private static final int MAX_Y = 1_023;
   private static final int SEA_LEVEL_FLOOR_Y = 64 - DEPTH;
   private static final int DEEP_FLOOR_Y = -640;

   @Test
   void flatTerrainNeedsOnlyTheBottomBedrockLayer() {
      int bottomY = TerrainShellBedrockProtection.supportBottomY(160, 64, -64);

      assertEquals(96, bottomY);
      assertEquals(96, TerrainShellBedrockProtection.sideSkinTopY(bottomY, 320, bottomY));
   }

   @Test
   void steepNeighborCreatesVerticalBedrockSkinUpToItsBottom() {
      int bottomY = TerrainShellBedrockProtection.supportBottomY(160, 64, -64);
      int neighborBottomY = TerrainShellBedrockProtection.supportBottomY(220, 64, -64);

      assertEquals(155, TerrainShellBedrockProtection.sideSkinTopY(bottomY, 320, neighborBottomY));
   }

   @Test
   void curtainBridgesAirGapWhenNeighborShellFloatsAboveTheColumn() {
      int bottomY = TerrainShellBedrockProtection.supportBottomY(100, 64, -64);
      int neighborBottomY = TerrainShellBedrockProtection.supportBottomY(200, 64, -64);

      assertEquals(100, TerrainShellBedrockProtection.sideSkinTopY(bottomY, 100, neighborBottomY));
      assertEquals(101, TerrainShellBedrockProtection.voidCurtainBottomY(neighborBottomY, -64, 100));
   }

   @Test
   void curtainIsSkippedWhenNeighborTerrainReachesTheShellBottom() {
      assertEquals(136, TerrainShellBedrockProtection.voidCurtainBottomY(136, -64, 160));
   }

   @Test
   void supportBottomNeverFallsBelowDimensionMinimum() {
      assertEquals(-64, TerrainShellBedrockProtection.supportBottomY(-40, 64, -64));
   }

   @Test
   void aFloorAtSeaLevelMinusTheDepthFlattensEveryBottomAtOrBelowSeaLevel() {
      for (int surfaceY = SEA_LEVEL_FLOOR_Y; surfaceY <= 64; surfaceY += 8) {
         assertEquals(SEA_LEVEL_FLOOR_Y, TerrainShellBedrockProtection.supportBottomY(surfaceY, DEPTH, SEA_LEVEL_FLOOR_Y));
      }
      assertEquals(SEA_LEVEL_FLOOR_Y - 1, TerrainShellBedrockProtection.supportBottomY(63, DEPTH, DEEP_FLOOR_Y));
   }

   /**
    * Reproduces the chunk-border seams: an ocean floor fitted to Y -100 while the
    * raw elevation the edge columns used to read for their neighbours says -20.
    */
   @Test
   void rawNeighborSurfacesRaiseBedrockWallsAlongChunkBordersOnlyBelowASeaLevelFloor() {
      IntBinaryOperator built = (x, z) -> -100;
      IntBinaryOperator raw = (x, z) -> -20;

      // The floor flattens every bottom, so the raw surfaces cannot disagree with the fitted floor.
      assertMatchesWholeWorld(built, raw, SEA_LEVEL_FLOOR_Y);

      ChunkBedrock deep = chunk(0, built, raw, DEEP_FLOOR_Y);
      ChunkBedrock whole = chunk(0, built, built, DEEP_FLOOR_Y);
      int bottomY = -100 - DEPTH;
      for (int i = 0; i < 16; i++) {
         // Every edge column turns 79 blocks of its shell into bedrock, on all four sides.
         assertEquals(-20 - DEPTH - 1, deep.skinTopY(0, i));
         assertEquals(-20 - DEPTH - 1, deep.skinTopY(15, i));
         assertEquals(-20 - DEPTH - 1, deep.skinTopY(i, 0));
         assertEquals(-20 - DEPTH - 1, deep.skinTopY(i, 15));
         assertEquals(bottomY, whole.skinTopY(0, i));
      }
      assertEquals(bottomY, deep.skinTopY(8, 8));
   }

   @Test
   void rawNeighborSurfacesLeaveGapsIntoTheVoidWhereTheNeighborIsHigher() {
      // A fitted floor stepping down 200 blocks at the border; the raw elevation of the upper side reads 80 lower.
      IntBinaryOperator built = (x, z) -> x < 16 ? -100 : -300;
      IntBinaryOperator raw = (x, z) -> x < 16 ? -180 : -300;

      ChunkBedrock upper = chunk(0, built, raw, DEEP_FLOOR_Y);
      ChunkBedrock lower = chunk(1, built, raw, DEEP_FLOOR_Y);

      assertFalse(sealed(upper, 15, lower, 0, 0, built, DEEP_FLOOR_Y));
   }

   @Test
   void neighborSurfacesAsTheNeighborBuildsThemSealBordersLikeTheInterior() {
      IntBinaryOperator[] worlds = {
         (x, z) -> -100,
         (x, z) -> x < 16 ? -100 : -300,
         // A cliff deeper than the shell across the border, and an uneven floor along it.
         (x, z) -> x < 16 ? 400 + 7 * z : -200 - 11 * z,
         (x, z) -> x < 16 ? -150 + (x * 37 + z * 53) % 90 : 120 - (x * 29 + z * 41) % 400,
      };

      for (IntBinaryOperator built : worlds) {
         for (int minimumY : new int[]{SEA_LEVEL_FLOOR_Y, DEEP_FLOOR_Y}) {
            assertMatchesWholeWorld(built, built, minimumY);
            ChunkBedrock west = chunk(0, built, built, minimumY);
            ChunkBedrock east = chunk(1, built, built, minimumY);
            for (int z = 0; z < 16; z++) {
               assertTrue(sealed(west, 15, east, 0, z, built, minimumY), "row " + z);
            }
         }
      }
   }

   private static void assertMatchesWholeWorld(IntBinaryOperator built, IntBinaryOperator ring, int minimumY) {
      for (int chunkX = 0; chunkX < 2; chunkX++) {
         ChunkBedrock chunk = chunk(chunkX, built, ring, minimumY);
         int[] skinTopYs = new int[256];
         int[] curtainBottomYs = new int[256];
         for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
               int worldX = chunkX * 16 + x;
               int surfaceY = surface(built, worldX, z, minimumY);
               int bottomY = TerrainShellBedrockProtection.supportBottomY(surfaceY, DEPTH, minimumY);
               int highestNeighborBottomY = Integer.MIN_VALUE;
               int lowestNeighborSurfaceY = Integer.MAX_VALUE;
               for (int[] offset : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                  int neighborSurfaceY = surface(built, worldX + offset[0], z + offset[1], minimumY);
                  highestNeighborBottomY = Math.max(
                     highestNeighborBottomY, TerrainShellBedrockProtection.supportBottomY(neighborSurfaceY, DEPTH, minimumY)
                  );
                  lowestNeighborSurfaceY = Math.min(lowestNeighborSurfaceY, neighborSurfaceY);
               }
               skinTopYs[z * 16 + x] = TerrainShellBedrockProtection.sideSkinTopY(
                  bottomY, surfaceY, highestNeighborBottomY
               );
               curtainBottomYs[z * 16 + x] = TerrainShellBedrockProtection.voidCurtainBottomY(bottomY, minimumY, lowestNeighborSurfaceY);
            }
         }
         assertArrayEquals(skinTopYs, chunk.skinTopYs(), "skins of chunk " + chunkX);
         assertArrayEquals(curtainBottomYs, chunk.curtainBottomYs(), "curtains of chunk " + chunkX);
      }
   }

   /**
    * Whether two columns facing each other across the border between chunks 0
    * and 1 leave no opening into the void: wherever one column is void, the
    * other must be void or bedrock.
    */
   private static boolean sealed(
      ChunkBedrock west, int westX, ChunkBedrock east, int eastX, int z, IntBinaryOperator built, int minimumY
   ) {
      int westBottomY = TerrainShellBedrockProtection.supportBottomY(surface(built, westX, z, minimumY), DEPTH, minimumY);
      int eastBottomY = TerrainShellBedrockProtection.supportBottomY(surface(built, 16 + eastX, z, minimumY), DEPTH, minimumY);
      int westLowestY = Math.min(westBottomY, west.curtainBottomY(westX, z));
      int eastLowestY = Math.min(eastBottomY, east.curtainBottomY(eastX, z));
      int westBedrockTopY = Math.max(westBottomY, west.skinTopY(westX, z));
      int eastBedrockTopY = Math.max(eastBottomY, east.skinTopY(eastX, z));
      return eastBedrockTopY >= westLowestY - 1 && westBedrockTopY >= eastLowestY - 1;
   }

   private static ChunkBedrock chunk(int chunkX, IntBinaryOperator built, IntBinaryOperator ring, int minimumY) {
      int minX = chunkX * 16;
      int[] surfaces = new int[256];
      int[] neighbors = new int[TerrainShellBedrockProtection.NEIGHBOR_RING_SIZE];
      for (int z = 0; z < 16; z++) {
         for (int x = 0; x < 16; x++) {
            surfaces[z * 16 + x] = surface(built, minX + x, z, minimumY);
         }
      }
      for (int i = 0; i < 16; i++) {
         neighbors[TerrainShellBedrockProtection.westRingIndex(i)] = ring.applyAsInt(minX - 1, i);
         neighbors[TerrainShellBedrockProtection.eastRingIndex(i)] = ring.applyAsInt(minX + 16, i);
         neighbors[TerrainShellBedrockProtection.northRingIndex(i)] = ring.applyAsInt(minX + i, -1);
         neighbors[TerrainShellBedrockProtection.southRingIndex(i)] = ring.applyAsInt(minX + i, 16);
      }
      return new ChunkBedrock(
         TerrainShellBedrockProtection.sideSkinTopYs(surfaces, neighbors, DEPTH, minimumY, MAX_Y),
         TerrainShellBedrockProtection.voidCurtainBottomYs(surfaces, neighbors, DEPTH, minimumY, MAX_Y)
      );
   }

   /** A built surface, which the generator keeps within the world's height. */
   private static int surface(IntBinaryOperator built, int x, int z, int minimumY) {
      return Math.max(minimumY, Math.min(MAX_Y, built.applyAsInt(x, z)));
   }

   private record ChunkBedrock(int[] skinTopYs, int[] curtainBottomYs) {
      int skinTopY(int localX, int localZ) {
         return this.skinTopYs[localZ * 16 + localX];
      }

      int curtainBottomY(int localX, int localZ) {
         return this.curtainBottomYs[localZ * 16 + localX];
      }
   }
}
