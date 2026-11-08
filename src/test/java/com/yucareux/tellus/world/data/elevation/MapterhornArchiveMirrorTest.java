package com.yucareux.tellus.world.data.elevation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yucareux.tellus.world.data.osm.PmTilesRangeReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class MapterhornArchiveMirrorTest {
   private static final byte[] PLANET_TILE = "planet 6/10/20".getBytes(StandardCharsets.US_ASCII);
   private static final byte[] REGION_TILE = "region 7/10/20".getBytes(StandardCharsets.US_ASCII);

   @Test
   void routesEachTileToThePlanetOrItsSquaresArchive() {
      assertEquals("planet.pmtiles", MapterhornArchiveMirror.archiveFor(12, 4095, 4095, 12));
      assertEquals("6-62-23.pmtiles", MapterhornArchiveMirror.archiveFor(13, 8000, 3000, 12));
      assertEquals("6-62-23.pmtiles", MapterhornArchiveMirror.archiveFor(18, 8000 << 5, 3000 << 5, 12));
      // Squares start at zoom 6, so a planet archive ending sooner still serves the zooms above them.
      assertEquals("planet.pmtiles", MapterhornArchiveMirror.archiveFor(5, 3, 3, 3));
   }

   @Test
   void readsTilesFromThePlanetAndTheHighResolutionArchives() throws IOException {
      try (PmTilesTestServer server = new PmTilesTestServer()) {
         server.serve("planet.pmtiles", PmTilesTestServer.archive(0, 6, PmTilesTestServer.COMPRESSION_NONE, Map.of(new int[]{6, 10, 20}, PLANET_TILE)));
         server.serve("6-5-10.pmtiles", PmTilesTestServer.archive(7, 7, PmTilesTestServer.COMPRESSION_NONE, Map.of(new int[]{7, 10, 20}, REGION_TILE)));
         MapterhornArchiveMirror mirror = new MapterhornArchiveMirror(server.folderUrl(), 2000, 2000);

         assertArrayEquals(PLANET_TILE, mirror.tile(6, 10, 20));
         assertNull(mirror.tile(6, 11, 20), "a tile the planet lacks is missing, as the tile server's 404");
         assertArrayEquals(REGION_TILE, mirror.tile(7, 10, 20));
         assertNull(mirror.tile(7, 11, 21), "a tile its square's archive lacks is missing too");
      }
   }

   @Test
   void aSquareWithoutAnArchiveIsNotMirroredAndAskedForOnce() throws IOException {
      try (PmTilesTestServer server = new PmTilesTestServer()) {
         server.serve("planet.pmtiles", PmTilesTestServer.archive(0, 6, PmTilesTestServer.COMPRESSION_NONE, Map.of(new int[]{6, 10, 20}, PLANET_TILE)));
         MapterhornArchiveMirror mirror = new MapterhornArchiveMirror(server.folderUrl(), 2000, 2000);

         assertSame(MapterhornArchiveMirror.NOT_MIRRORED, mirror.tile(7, 40, 40));
         assertSame(MapterhornArchiveMirror.NOT_MIRRORED, mirror.tile(7, 41, 41));
         assertEquals(1, server.requestCount("6-20-20.pmtiles"));
      }
   }

   @Test
   void aMissingPlanetArchiveIsAnErrorRatherThanMissingTiles() throws IOException {
      try (PmTilesTestServer server = new PmTilesTestServer()) {
         MapterhornArchiveMirror mirror = new MapterhornArchiveMirror(server.folderUrl(), 2000, 2000);

         assertThrows(IOException.class, () -> mirror.tile(6, 10, 20));
         assertThrows(IOException.class, () -> mirror.tile(13, 4000, 3000));
      }
   }

   @Test
   void coverageTilesComeOutOfTheArchiveDecompressed() throws IOException {
      byte[] plain = "coverage tile".getBytes(StandardCharsets.US_ASCII);
      try (PmTilesTestServer server = new PmTilesTestServer()) {
         server.serve("coverage.pmtiles", PmTilesTestServer.archive(0, 3, PmTilesTestServer.COMPRESSION_GZIP, Map.of(new int[]{3, 4, 2}, gzip(plain))));
         PmTilesRangeReader archive = new PmTilesRangeReader(server.folderUrl() + "coverage.pmtiles", 2000, 2000, 8, 0L);

         assertArrayEquals(plain, MapterhornCoverageResolutionSource.archiveTile(archive, new MapterhornCoverageResolutionSource.TileKey(3, 4, 2)));
         assertEquals(0, MapterhornCoverageResolutionSource.archiveTile(archive, new MapterhornCoverageResolutionSource.TileKey(3, 5, 2)).length);

         PmTilesRangeReader absent = new PmTilesRangeReader(server.folderUrl() + "absent.pmtiles", 2000, 2000, 8, 0L);
         assertThrows(
            IOException.class,
            () -> MapterhornCoverageResolutionSource.archiveTile(absent, new MapterhornCoverageResolutionSource.TileKey(3, 4, 2)),
            "a missing archive must not read as empty coverage, which would be cached"
         );
      }
   }

   private static byte[] gzip(byte[] bytes) throws IOException {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
         gzip.write(bytes);
      }
      return out.toByteArray();
   }
}
