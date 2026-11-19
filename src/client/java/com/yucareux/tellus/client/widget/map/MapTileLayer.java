package com.yucareux.tellus.client.widget.map;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;

/** The maps a player can pick: one tile source, or one with transparent tiles from another drawn over it. */
public enum MapTileLayer {
   STANDARD("standard", MapTileSource.OPENSTREETMAP, null, "(c) OpenStreetMap Contributors"),
   TOPOGRAPHIC(
      "topographic",
      MapTileSource.OPENTOPOMAP,
      null,
      "Map data: (c) OpenStreetMap Contributors, SRTM | Map style: (c) OpenTopoMap (CC-BY-SA)"
   ),
   TRANSPORT("transport", MapTileSource.OPNVKARTE, null, "(c) OpenStreetMap Contributors | Map: (c) MeMoMaps (CC-BY-SA)"),
   RAILWAY(
      "railway",
      MapTileSource.OPENSTREETMAP,
      MapTileSource.OPENRAILWAYMAP,
      "Data (c) OpenStreetMap Contributors | Style: CC-BY-SA 2.0 OpenRailwayMap and OpenStreetMap"
   );

   private static final String ATTRIBUTION_SEPARATOR = " | ";
   private final String id;
   private final MapTileSource base;
   private final MapTileSource overlay;
   private final String attribution;

   MapTileLayer(String id, MapTileSource base, MapTileSource overlay, String attribution) {
      this.id = id;
      this.base = base;
      this.overlay = overlay;
      this.attribution = attribution;
   }

   public String translationKey() {
      return "gui.earth.map_layer." + this.id;
   }

   public MapTileSource base() {
      return this.base;
   }

   /** The source drawn over the base, or null when the map has none. */
   public MapTileSource overlay() {
      return this.overlay;
   }

   public int maxZoom() {
      return this.overlay == null ? this.base.maxZoom() : Math.min(this.base.maxZoom(), this.overlay.maxZoom());
   }

   public MapTileLayer next() {
      MapTileLayer[] layers = values();
      return layers[(this.ordinal() + 1) % layers.length];
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
