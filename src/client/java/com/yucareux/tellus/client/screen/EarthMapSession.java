package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.widget.map.MapTileLayer;
import java.util.Objects;

/**
 * What the in-game map keeps between openings: its zoom and which map it shows. It is not saved anywhere and is
 * forgotten when the player leaves the world or server.
 */
public final class EarthMapSession {
   private static final int NO_ZOOM = -1;
   private static int zoom = NO_ZOOM;
   private static MapTileLayer layer = MapTileLayer.STANDARD;

   private EarthMapSession() {
   }

   public static int zoom(int fallback) {
      return zoom == NO_ZOOM ? fallback : zoom;
   }

   public static void rememberZoom(int value) {
      zoom = value;
   }

   public static MapTileLayer layer() {
      return layer;
   }

   public static void rememberLayer(MapTileLayer value) {
      layer = Objects.requireNonNull(value, "layer");
   }

   public static void reset() {
      zoom = NO_ZOOM;
      layer = MapTileLayer.STANDARD;
   }
}
