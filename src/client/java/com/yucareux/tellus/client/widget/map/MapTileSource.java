package com.yucareux.tellus.client.widget.map;

import com.yucareux.tellus.world.data.source.MapTileServerGate;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** A raster tile server the slippy map downloads from. None of them needs an API key. */
public enum MapTileSource {
   // Behind the Fastly CDN.
   OPENSTREETMAP("openstreetmap", "OpenStreetMap", "", SlippyMap.MAX_ZOOM, 4, Duration.ZERO, "https://tile.openstreetmap.org/%d/%d/%d.png"),
   // Above zoom 17 OpenTopoMap answers with an image reading "max zoom layer = 17", which must never be cached.
   OPENTOPOMAP(
      "opentopomap",
      "OpenTopoMap",
      "opentopomap",
      17,
      2,
      Duration.ZERO,
      "https://a.tile.opentopomap.org/%d/%d/%d.png",
      "https://b.tile.opentopomap.org/%d/%d/%d.png",
      "https://c.tile.opentopomap.org/%d/%d/%d.png"
   ),
   OPNVKARTE("opnvkarte", "ÖPNVKarte", "opnvkarte", 18, 2, Duration.ZERO, "https://tile.memomaps.de/tilegen/%d/%d/%d.png"),
   // Transparent 512 px tiles meant to be drawn over another map; the a/b/c hosts are deprecated. The volunteers'
   // own server answers every request without a CDN in front and rate limits busy clients, so requests are spaced.
   OPENRAILWAYMAP(
      "openrailwaymap",
      "OpenRailwayMap",
      "openrailwaymap",
      19,
      2,
      Duration.ofMillis(100L),
      "https://tiles.openrailwaymap.org/standard/%d/%d/%d.png"
   );

   private final String id;
   private final String providerName;
   private final String cacheDirectory;
   private final int maxZoom;
   private final String[] urlTemplates;
   private final MapTileServerGate gate;

   MapTileSource(
      String id, String providerName, String cacheDirectory, int maxZoom, int maxConcurrentRequests, Duration requestSpacing, String... urlTemplates
   ) {
      this.id = id;
      this.providerName = providerName;
      this.cacheDirectory = cacheDirectory;
      this.maxZoom = maxZoom;
      this.urlTemplates = urlTemplates;
      this.gate = new MapTileServerGate(providerName, maxConcurrentRequests, requestSpacing);
   }

   /** Lower-case letters only, so it can be part of a texture identifier. */
   public String id() {
      return this.id;
   }

   public String providerName() {
      return this.providerName;
   }

   public int maxZoom() {
      return this.maxZoom;
   }

   /** The request budget of this server, shared by the map screen and the minimap. */
   public MapTileServerGate gate() {
      return this.gate;
   }

   public String tileUrl(SlippyMapTilePos pos) {
      // A tile always comes from the same host, so the HTTP cache of each host stays useful.
      int host = Math.floorMod(pos.getX() + pos.getY(), this.urlTemplates.length);
      return String.format(Locale.ROOT, this.urlTemplates[host], pos.getZoom(), pos.getX(), pos.getY());
   }

   /** OpenStreetMap tiles stay in the cache root, where earlier versions stored them. */
   public Path cacheDirectory(Path cacheRoot) {
      return this.cacheDirectory.isEmpty() ? cacheRoot : cacheRoot.resolve(this.cacheDirectory);
   }
}
