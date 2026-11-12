package com.yucareux.tellus.client.widget.map;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;

/** The raster maps the slippy map can show. None of these tile servers needs an API key. */
public enum MapTileLayer {
   STANDARD(
      "standard",
      "OpenStreetMap",
      "",
      SlippyMap.MAX_ZOOM,
      "(c) OpenStreetMap Contributors",
      "https://tile.openstreetmap.org/%d/%d/%d.png"
   ),
   // Above zoom 17 OpenTopoMap answers with an image reading "max zoom layer = 17", which must never be cached.
   TOPOGRAPHIC(
      "topographic",
      "OpenTopoMap",
      "opentopomap",
      17,
      "Map data: (c) OpenStreetMap Contributors, SRTM | Map style: (c) OpenTopoMap (CC-BY-SA)",
      "https://a.tile.opentopomap.org/%d/%d/%d.png",
      "https://b.tile.opentopomap.org/%d/%d/%d.png",
      "https://c.tile.opentopomap.org/%d/%d/%d.png"
   ),
   TRANSPORT(
      "transport",
      "\u00d6PNVKarte",
      "opnvkarte",
      18,
      "(c) OpenStreetMap Contributors | Map: (c) MeMoMaps (CC-BY-SA)",
      "https://tile.memomaps.de/tilegen/%d/%d/%d.png"
   );

   private static final String ATTRIBUTION_SEPARATOR = " | ";
   private final String id;
   private final String providerName;
   private final String cacheDirectory;
   private final int maxZoom;
   private final String attribution;
   private final String[] urlTemplates;

   MapTileLayer(String id, String providerName, String cacheDirectory, int maxZoom, String attribution, String... urlTemplates) {
      this.id = id;
      this.providerName = providerName;
      this.cacheDirectory = cacheDirectory;
      this.maxZoom = maxZoom;
      this.attribution = attribution;
      this.urlTemplates = urlTemplates;
   }

   public String translationKey() {
      return "gui.earth.map_layer." + this.id;
   }

   public String providerName() {
      return this.providerName;
   }

   public int maxZoom() {
      return this.maxZoom;
   }

   public MapTileLayer next() {
      MapTileLayer[] layers = values();
      return layers[(this.ordinal() + 1) % layers.length];
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

   /** The attribution on one line, or split into its parts when the line is wider than the map. */
   public List<String> attributionLines(Font font, int maxWidth) {
      if (font.width(this.attribution) <= maxWidth || !this.attribution.contains(ATTRIBUTION_SEPARATOR)) {
         return List.of(this.attribution);
      }

      List<String> lines = new ArrayList<>();
      int start = 0;
      int separator;
      while ((separator = this.attribution.indexOf(ATTRIBUTION_SEPARATOR, start)) >= 0) {
         lines.add(this.attribution.substring(start, separator));
         start = separator + ATTRIBUTION_SEPARATOR.length();
      }
      lines.add(this.attribution.substring(start));
      return lines;
   }
}
