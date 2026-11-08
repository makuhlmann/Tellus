package com.yucareux.tellus.world.data.elevation;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.world.data.osm.PmTilesRangeReader;
import java.io.IOException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads Mapterhorn elevation tiles out of a copy of its downloadable archives that any web server with range
 * requests serves from one folder, named as on {@code download.mapterhorn.com}: {@code planet.pmtiles} for the
 * global zooms and one {@code 6-x-y.pmtiles} per zoom-6 square for the high-resolution zooms below it. The
 * archives hold the bytes the tile server sends, so tiles read here match tiles already cached from it.
 */
final class MapterhornArchiveMirror {
   /**
    * Returned for a tile whose high-resolution archive is not in the mirror. The tile server answers 404 there
    * too, but the archive may simply not be copied yet, so callers keep no marker of the miss on disk.
    */
   static final byte[] NOT_MIRRORED = new byte[0];
   static final String PLANET_ARCHIVE = "planet.pmtiles";
   // Mapterhorn splits its high-resolution data by the zoom-6 tile that contains it.
   private static final int REGION_ZOOM = 6;
   private static final int DIRECTORY_CACHE_ENTRIES = 64;

   private final String folderUrl;
   private final int connectTimeoutMs;
   private final int readTimeoutMs;
   private final ConcurrentHashMap<String, PmTilesRangeReader> readers = new ConcurrentHashMap<>();
   private final Set<String> absentArchives = ConcurrentHashMap.newKeySet();
   private volatile int planetMaxZoom = -1;

   MapterhornArchiveMirror(String folderUrl, int connectTimeoutMs, int readTimeoutMs) {
      String folder = Objects.requireNonNull(folderUrl, "folderUrl");
      this.folderUrl = folder.endsWith("/") ? folder : folder + "/";
      this.connectTimeoutMs = connectTimeoutMs;
      this.readTimeoutMs = readTimeoutMs;
   }

   /**
    * The tile's bytes; null when its archive has no such tile, which the tile server answers with 404; or
    * {@link #NOT_MIRRORED} when the high-resolution archive of its square is not in the mirror.
    */
   byte[] tile(int zoom, int x, int y) throws IOException {
      String archive = archiveFor(zoom, x, y, this.planetMaxZoom());
      if (this.absentArchives.contains(archive)) {
         return NOT_MIRRORED;
      }

      try {
         return this.reader(archive).getTileBytes(zoom, x, y);
      } catch (PmTilesRangeReader.ArchiveNotFoundException missing) {
         if (PLANET_ARCHIVE.equals(archive)) {
            throw missing;
         }
         // Most of the world has no high-resolution archive; one miss per archive and session is enough.
         if (this.absentArchives.add(archive)) {
            Tellus.LOGGER.debug("Mapterhorn archive {} is not in the mirror at {}", archive, this.folderUrl);
         }
         return NOT_MIRRORED;
      }
   }

   /** The archive that holds tile {@code zoom/x/y}: the planet archive up to its deepest zoom, else its square's. */
   static String archiveFor(int zoom, int x, int y, int planetMaxZoom) {
      if (zoom <= planetMaxZoom || zoom < REGION_ZOOM) {
         return PLANET_ARCHIVE;
      }
      int shift = zoom - REGION_ZOOM;
      return REGION_ZOOM + "-" + (x >> shift) + "-" + (y >> shift) + ".pmtiles";
   }

   private int planetMaxZoom() throws IOException {
      int known = this.planetMaxZoom;
      if (known >= 0) {
         return known;
      }

      int maxZoom;
      try {
         maxZoom = this.reader(PLANET_ARCHIVE).header().maxZoom();
      } catch (PmTilesRangeReader.ArchiveNotFoundException missing) {
         throw new IOException(PLANET_ARCHIVE + " is missing from the Mapterhorn mirror at " + this.folderUrl, missing);
      }
      this.planetMaxZoom = maxZoom;
      return maxZoom;
   }

   private PmTilesRangeReader reader(String archive) {
      // The elevation source caches the tiles it reads, so the readers keep only the archives' directories.
      return this.readers.computeIfAbsent(
         archive,
         name -> new PmTilesRangeReader(this.folderUrl + name, this.connectTimeoutMs, this.readTimeoutMs, DIRECTORY_CACHE_ENTRIES, 0L)
      );
   }
}
