package com.yucareux.tellus.client.widget.map;

import java.nio.file.Path;
import java.util.Locale;

/** A raster tile server the slippy map downloads from. None of them needs an API key. */
public enum MapTileSource {
   OPENSTREETMAP("openstreetmap", "OpenStreetMap", "", SlippyMap.MAX_ZOOM, "https://tile.openstreetmap.org/%d/%d/%d.png"),
   // Above zoom 17 OpenTopoMap answers with an image reading "max zoom layer = 17", which must never be cached.
   OPENTOPOMAP(
      "opentopomap",
      "OpenTopoMap",
      "opentopomap",
      17,
      "https://a.tile.opentopomap.org/%d/%d/%d.png",
      "https://b.tile.opentopomap.org/%d/%d/%d.png",
      "https://c.tile.opentopomap.org/%d/%d/%d.png"
   ),
   OPNVKARTE("opnvkarte", "\u00d6PNVKarte", "opnvkarte", 18, "https://tile.memomaps.de/tilegen/%d/%d/%d.png"),
   // Transparent 512 px tiles meant to be drawn over another map; the a/b/c hosts are deprecated.
   OPENRAILWAYMAP("openrailwaymap", "OpenRailwayMap", "openrailwaymap", 19, "https://tiles.openrailwaymap.org/standard/%d/%d/%d.png");

   private final String id;
   private final String providerName;
   private final String cacheDirectory;
   private final int maxZoom;
   private final String[] urlTemplates;

   MapTileSource(String id, String providerName, String cacheDirectory, int maxZoom, String... urlTemplates) {
      this.id = id;
      this.providerName = providerName;
      this.cacheDirectory = cacheDirectory;
      this.maxZoom = maxZoom;
      this.urlTemplates = urlTemplates;
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
