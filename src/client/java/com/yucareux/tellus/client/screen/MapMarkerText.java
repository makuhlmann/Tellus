package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

/** The line under the map that names the marker's block coordinates. */
final class MapMarkerText {
   private MapMarkerText() {
   }

   static Component describe(SlippyMapPoint marker, double worldScale) {
      // Clamped and rounded the same way as a teleport to the marker, so the numbers match where it would land.
      double latitude = EarthProjection.clampLatitude(marker.getLatitude());
      double longitude = Mth.clamp(marker.getLongitude(), -180.0, 180.0);
      int blockX = Mth.floor(EarthProjection.longitudeToBlockX(longitude, worldScale));
      int blockZ = Mth.floor(EarthProjection.latToBlockZ(latitude, worldScale));
      return Component.translatable(
         "gui.earth.marker_position",
         blockX,
         blockZ,
         String.format(Locale.ROOT, "%.5f", latitude),
         String.format(Locale.ROOT, "%.5f", longitude)
      );
   }
}
