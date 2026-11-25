package com.yucareux.tellus.worldgen.caves;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import org.junit.jupiter.api.Test;

class TellusCaveEntranceMaskTest {
   private static final long SEED = 5_705_906_339_978_404_084L;

   @Test
   void opensAboutFivePercentOfTheLand() {
      Random random = new Random(42);
      int open = 0;
      int samples = 200_000;
      for (int i = 0; i < samples; i++) {
         int x = random.nextInt(4_000_000) - 2_000_000;
         int z = random.nextInt(4_000_000) - 2_000_000;
         if (TellusCaveEntranceMask.allowsOpening(SEED, x, z)) {
            open++;
         }
      }
      double share = open / (double)samples;
      assertTrue(Math.abs(share - TellusCaveEntranceMask.OPEN_SHARE) < 0.01, "open share was " + share);
      assertTrue(share <= 0.10, "open share was " + share);
   }

   @Test
   void changesSmoothlyBetweenNeighbouringBlocks() {
      for (int x = -1_000; x <= 1_000; x += 7) {
         for (int z = -300; z <= 300; z += 11) {
            double here = TellusCaveEntranceMask.value(SEED, x, z);
            assertTrue(here >= 0.0 && here < 1.0);
            assertTrue(Math.abs(TellusCaveEntranceMask.value(SEED, x + 1, z) - here) < 0.03);
            assertTrue(Math.abs(TellusCaveEntranceMask.value(SEED, x, z + 1) - here) < 0.03);
         }
      }
   }

   @Test
   void isFixedPerSeedAndDiffersBetweenSeeds() {
      assertEquals(TellusCaveEntranceMask.value(SEED, 109_628, -680_789), TellusCaveEntranceMask.value(SEED, 109_628, -680_789));
      int differences = 0;
      for (int i = 0; i < 100; i++) {
         int x = i * 997;
         if (Math.abs(TellusCaveEntranceMask.value(SEED, x, -x) - TellusCaveEntranceMask.value(SEED + 1, x, -x)) > 0.1) {
            differences++;
         }
      }
      assertNotEquals(0, differences);
   }
}
