package com.yucareux.tellus.client.widget.map;

import com.yucareux.tellus.world.data.source.MapTileServerGate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

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

   /** The attribution word-wrapped to the given width, each of its parts starting on a new line. */
   public List<String> wrappedAttributionLines(Font font, int maxWidth) {
      List<String> lines = new ArrayList<>();
      for (String part : this.attribution.split(java.util.regex.Pattern.quote(ATTRIBUTION_SEPARATOR))) {
         wrap(lines, part, font, maxWidth);
      }
      return lines;
   }

   /**
    * For each of this map's tile servers that is paused after refusing or failing requests, a notice saying so and
    * when it is asked again, word-wrapped to the given width. Empty while every server takes requests.
    */
   public List<String> serverNoticeLines(Font font, int maxWidth) {
      List<String> lines = new ArrayList<>(0);
      addServerNotice(lines, this.base, font, maxWidth);
      if (this.overlay != null) {
         addServerNotice(lines, this.overlay, font, maxWidth);
      }
      return lines;
   }

   private static void addServerNotice(List<String> lines, MapTileSource source, Font font, int maxWidth) {
      MapTileServerGate.Status status = source.gate().status();
      if (status == null) {
         return;
      }
      long seconds = Math.max(1L, (status.remainingNanos() + 999_999_999L) / 1_000_000_000L);
      String remaining = String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
      String key = status.reason() == MapTileServerGate.PauseReason.REFUSED ? "gui.earth.map_server.refused" : "gui.earth.map_server.unreachable";
      wrap(lines, Component.translatable(key, source.providerName(), remaining).getString(), font, maxWidth);
   }

   private static void wrap(List<String> lines, String text, Font font, int maxWidth) {
      String line = "";
      for (String word : text.split(" ")) {
         String candidate = line.isEmpty() ? word : line + " " + word;
         if (!line.isEmpty() && font.width(candidate) > maxWidth) {
            lines.add(line);
            line = word;
         } else {
            line = candidate;
         }
      }
      if (!line.isEmpty()) {
         lines.add(line);
      }
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
