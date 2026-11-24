package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/**
 * The marker's block coordinates, for the line under the map and for waypoints. They are clamped and rounded the same
 * way as a teleport to the marker, so the numbers match where it would land.
 */
final class MapMarkerText {
   private MapMarkerText() {
   }

   static Component describe(SlippyMapPoint marker, double worldScale) {
      return Component.translatable(
         "gui.earth.marker_position",
         blockX(marker, worldScale),
         blockZ(marker, worldScale),
         String.format(Locale.ROOT, "%.5f", latitude(marker)),
         String.format(Locale.ROOT, "%.5f", longitude(marker))
      );
   }

   static int blockX(SlippyMapPoint marker, double worldScale) {
      return Mth.floor(EarthProjection.longitudeToBlockX(longitude(marker), worldScale));
   }

   static int blockZ(SlippyMapPoint marker, double worldScale) {
      return Mth.floor(EarthProjection.latToBlockZ(latitude(marker), worldScale));
   }

   private static double latitude(SlippyMapPoint marker) {
      return EarthProjection.clampLatitude(marker.getLatitude());
   }

   private static double longitude(SlippyMapPoint marker) {
      return Mth.clamp(marker.getLongitude(), -180.0, 180.0);
   }
}
