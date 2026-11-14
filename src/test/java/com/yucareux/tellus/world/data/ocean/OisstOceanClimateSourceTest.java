package com.yucareux.tellus.world.data.ocean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;

/** Parses NOAA PSL OPeNDAP ASCII blocks (16x16 fixtures captured from the 1991-2020 OISST climatology). */
class OisstOceanClimateSourceTest {
   @Test
   void parsesMonthlySeaSurfaceTemperatureBlockWithLandGaps() throws IOException {
      double[] values = OisstOceanClimateSource.parseAsciiGrid(fixture("sst-block-27-63.ascii"), "sst", 16);
      assertEquals(12 * 256, values.length);
      int finite = 0;
      int missing = 0;
      for (double value : values) {
         if (Double.isFinite(value)) {
            finite++;
            assertTrue(value > 10.0 && value < 35.0, "tropical Pacific SST out of range: " + value);
         } else {
            missing++;
         }
      }
      // The block off Jalisco contains open ocean and coastline (land cells are fill values).
      assertTrue(finite > 12 * 128, "expected mostly ocean, got " + finite);
      assertTrue(missing > 0, "expected land cells in the coastal block");
      // First value of the first row, as published.
      assertEquals(25.828186, values[0], 1.0E-6);
   }

   @Test
   void parsesIceBlockAsFractions() throws IOException {
      double[] values = OisstOceanClimateSource.parseAsciiGrid(fixture("icec-block-27-63.ascii"), "icec", 16);
      assertEquals(12 * 256, values.length);
      for (double value : values) {
         assertTrue(Double.isNaN(value) || value >= 0.0 && value <= 1.0, "ice fraction out of range: " + value);
      }
   }

   @Test
   void rejectsTruncatedGrid() {
      String truncated = "sst.sst[12][16][16]\n[0][0], 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16\n\n";
      assertThrows(IOException.class, () -> OisstOceanClimateSource.parseAsciiGrid(truncated.getBytes(StandardCharsets.UTF_8), "sst", 16));
   }

   @Test
   void aCoastalCellCountedAsLandTakesTheNearestSeaCell() {
      // The Weser at Bremerhaven (53.5-53.75 N, 8.5-8.75 E) is land in OISST; the Jade cell west of it is sea.
      int latIndex = 574;
      int lonIndex = 34;
      OisstOceanClimateSource.Sample jade = new OisstOceanClimateSource.Sample(10.8, 0.0, true);
      Map<Long, OisstOceanClimateSource.Sample> sea = new HashMap<>();
      sea.put(cell(latIndex, lonIndex - 1), jade);
      sea.put(cell(latIndex + 3, lonIndex), new OisstOceanClimateSource.Sample(10.7, 0.0, true));

      assertSame(jade, OisstOceanClimateSource.nearestSeaSample(latIndex, lonIndex, lookup(sea)));
   }

   @Test
   void eastWestStepsShrinkTowardThePoles() {
      // At 60 N an east-west step is half as long as a north-south one: three steps east are nearer than two north.
      int latIndex = (int)Math.round((60.125 + 89.875) / 0.25);
      OisstOceanClimateSource.Sample east = new OisstOceanClimateSource.Sample(6.0, 0.0, true);
      Map<Long, OisstOceanClimateSource.Sample> sea = new HashMap<>();
      sea.put(cell(latIndex + 2, 100), new OisstOceanClimateSource.Sample(5.0, 0.0, true));
      sea.put(cell(latIndex, 103), east);

      assertSame(east, OisstOceanClimateSource.nearestSeaSample(latIndex, 100, lookup(sea)));
   }

   @Test
   void theSearchWrapsAroundTheAntimeridianAndBreaksTiesTheSameWayEveryTime() {
      // Equally near cells: west before east (across the 180th meridian here), north before south.
      OisstOceanClimateSource.Sample west = new OisstOceanClimateSource.Sample(27.0, 0.0, true);
      Map<Long, OisstOceanClimateSource.Sample> sea = new HashMap<>();
      sea.put(cell(360, 1), new OisstOceanClimateSource.Sample(28.0, 0.0, true));
      sea.put(cell(360, 1439), west);
      assertSame(west, OisstOceanClimateSource.nearestSeaSample(360, 0, lookup(sea)));

      OisstOceanClimateSource.Sample north = new OisstOceanClimateSource.Sample(20.0, 0.0, true);
      Map<Long, OisstOceanClimateSource.Sample> northAndSouth = new HashMap<>();
      northAndSouth.put(cell(359, 0), new OisstOceanClimateSource.Sample(21.0, 0.0, true));
      northAndSouth.put(cell(361, 0), north);
      assertSame(north, OisstOceanClimateSource.nearestSeaSample(360, 0, lookup(northAndSouth)));
   }

   @Test
   void landCellsAndUnreadableBlocksAreSkippedAndTheSearchStopsAtItsRadius() {
      int radius = OisstOceanClimateSource.COASTAL_FILL_RADIUS_CELLS;
      Map<Long, OisstOceanClimateSource.Sample> sea = new HashMap<>();
      sea.put(cell(300, 501), new OisstOceanClimateSource.Sample(16.0, 0.0, false));
      sea.put(cell(300, 500 + radius + 1), new OisstOceanClimateSource.Sample(15.0, 0.0, true));
      assertNull(OisstOceanClimateSource.nearestSeaSample(300, 500, lookup(sea)));

      OisstOceanClimateSource.Sample edge = new OisstOceanClimateSource.Sample(14.0, 0.0, true);
      sea.put(cell(300 - radius, 500), edge);
      OisstOceanClimateSource.CellLookup missingBlockNearby = (latIndex, lonIndex) ->
         lonIndex == 499 ? null : sea.get(cell(latIndex, lonIndex));
      assertSame(edge, OisstOceanClimateSource.nearestSeaSample(300, 500, missingBlockNearby));
   }

   @Test
   void iceCountsOnlyInMonthsColdEnoughForItToBeReal() {
      double nan = Double.NaN;
      // 1991-2020 monthly means, January to December, as NOAA PSL publishes them.
      // Kiel Bay: over 40% "ice" at 2 to 5 C, picked up from the land around it; it freezes only in hard winters.
      double[] kielSst = {2.67, 2.06, 2.94, 5.18, 10.96, 15.14, 17.92, 18.54, 15.95, 12.35, 8.6, 4.36};
      double[] kielIce = {0.42, 0.44, 0.43, 0.47, nan, nan, nan, nan, nan, nan, nan, 0.42};
      assertEquals(0.0, OisstOceanClimateSource.peakFreezingIce(kielSst, kielIce, 0, 1), 0.0);
      // The Gulf of Riga freezes over most winters, with the water at about 1 C.
      double[] rigaSst = {2.28, 0.98, 0.92, 2.8, 7.85, 13.23, 17.51, 18.4, 15.46, 11.28, 7.64, 4.71};
      double[] rigaIce = {0.16, 0.31, 0.34, 0.22, 0.03, nan, nan, nan, nan, nan, nan, 0.04};
      assertEquals(0.34, OisstOceanClimateSource.peakFreezingIce(rigaSst, rigaIce, 0, 1), 1.0E-9);

      // Month-major blocks: the second cell of a two-cell block reads every other value.
      double[] sst = new double[24];
      double[] ice = new double[24];
      for (int month = 0; month < 12; month++) {
         sst[month * 2] = kielSst[month];
         ice[month * 2] = kielIce[month];
         sst[month * 2 + 1] = rigaSst[month];
         ice[month * 2 + 1] = rigaIce[month];
      }
      assertEquals(0.0, OisstOceanClimateSource.peakFreezingIce(sst, ice, 0, 2), 0.0);
      assertEquals(0.34, OisstOceanClimateSource.peakFreezingIce(sst, ice, 1, 2), 1.0E-9);
   }

   @Test
   void downloadedValuesAreKeptExactlyAsTheDiskCacheStoresThem() {
      // A download and a later session's read of the cache must agree, also for a sea right at a biome threshold.
      assertEquals(13.0, OisstOceanClimateSource.stored(12.99996), 0.0);
      assertEquals(12.9999, OisstOceanClimateSource.stored(12.99994), 0.0);
      double value = OisstOceanClimateSource.stored(10.784512);
      assertEquals(value, Double.parseDouble(String.format(java.util.Locale.ROOT, "%.4f", value)), 0.0);
   }

   private static long cell(int latIndex, int lonIndex) {
      return (long)latIndex << 32 | lonIndex;
   }

   private static OisstOceanClimateSource.CellLookup lookup(Map<Long, OisstOceanClimateSource.Sample> sea) {
      return (latIndex, lonIndex) -> sea.getOrDefault(cell(latIndex, lonIndex), new OisstOceanClimateSource.Sample(16.0, 0.0, false));
   }

   private static byte[] fixture(String name) throws IOException {
      try (InputStream input = Objects.requireNonNull(
         OisstOceanClimateSourceTest.class.getResourceAsStream("/oisst/" + name), "missing fixture " + name)) {
         return input.readAllBytes();
      }
   }
}
