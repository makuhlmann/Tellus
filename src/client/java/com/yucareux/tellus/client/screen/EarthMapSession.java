package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.widget.map.MapTileLayer;
import java.util.Objects;

/**
 * What the in-game map keeps between openings: its zoom, which map it shows, and the world scale of the player's
 * level. It is not saved anywhere and is forgotten when the player leaves the world or server.
 */
public final class EarthMapSession {
   private static final int NO_ZOOM = -1;
   private static int zoom = NO_ZOOM;
   private static MapTileLayer layer = MapTileLayer.STANDARD;
   private static volatile double worldScale;

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

   /** Metres per block of the player's level, or 0 when it is not a Tellus level. */
   public static double worldScale() {
      return worldScale;
   }

   public static void setWorldScale(double value) {
      worldScale = value > 0.0 && Double.isFinite(value) ? value : 0.0;
   }

   public static void reset() {
      zoom = NO_ZOOM;
      layer = MapTileLayer.STANDARD;
      worldScale = 0.0;
   }
}
