package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.worldgen.caves.TellusCaveBiomeDepthPolicy;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class SandyShorePolicyTest {
   private static int index(int x, int z) {
      return z * 16 + x;
   }

   @Test
   void shoreFollowsSandInlandFromTheOceanEdgeOnly() {
      // The ocean lies north of row 0. A beach runs six rows inland on the west half; a separate
      // dune field sits inland in the south-east corner, and the east half of the coast is rock.
      boolean[] oceanEdge = new boolean[256];
      boolean[] sand = new boolean[256];
      for (int x = 0; x < 16; x++) {
         oceanEdge[index(x, 0)] = true;
      }
      for (int z = 0; z < 6; z++) {
         for (int x = 0; x < 8; x++) {
            sand[index(x, z)] = true;
         }
      }
      for (int z = 10; z < 16; z++) {
         for (int x = 12; x < 16; x++) {
            sand[index(x, z)] = true;
         }
      }
      int[] lookups = new int[256];

      boolean[] shore = SandyShorePolicy.shoreColumns(oceanEdge, index -> {
         lookups[index]++;
         return sand[index];
      });

      for (int z = 0; z < 16; z++) {
         for (int x = 0; x < 16; x++) {
            assertEquals(x < 8 && z < 6, shore[index(x, z)], "column " + x + ", " + z);
         }
      }
      assertTrue(Arrays.stream(lookups).allMatch(count -> count <= 1), "each column's sand is looked up once at most");
      assertEquals(0, lookups[index(14, 14)], "sand nowhere near the shore is never looked up");
   }

   @Test
   void aQuartStoresTheShoreFromAboveCaveDepthOnceAQuarterIsShore() {
      boolean[] shore = new boolean[256];
      int[] surfaces = new int[256];
      Arrays.fill(surfaces, 64);
      // Quart (0, 0): all shore, ground rising to 66 at one corner.
      for (int z = 0; z < 4; z++) {
         for (int x = 0; x < 4; x++) {
            shore[index(x, z)] = true;
         }
      }
      surfaces[index(3, 3)] = 66;
      // Quart (1, 0): four shore columns; quart (2, 0): three.
      for (int x = 4; x < 8; x++) {
         shore[index(x, 0)] = true;
      }
      for (int x = 8; x < 11; x++) {
         shore[index(x, 0)] = true;
      }

      int[] floors = SandyShorePolicy.quartFloors(shore, surfaces);

      assertEquals(66 - TellusCaveBiomeDepthPolicy.MIN_CAVE_BIOME_DEPTH + 1, floors[0]);
      assertEquals(64 - TellusCaveBiomeDepthPolicy.MIN_CAVE_BIOME_DEPTH + 1, floors[1]);
      assertEquals(SandyShorePolicy.NO_SHORE, floors[2]);
      for (int quart = 3; quart < 16; quart++) {
         assertEquals(SandyShorePolicy.NO_SHORE, floors[quart]);
      }
      // The block above the sand, where turtles spawn, lies in a stored quart.
      assertFalse(Math.floorDiv(65, 4) * 4 < floors[0]);
   }
}
