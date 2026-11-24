package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.integration.xaero.XaeroWaypointCommand;
import com.yucareux.tellus.platform.TellusPlatform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

/**
 * Hands the map's marker to Xaero's Minimap, whose "New Waypoint" screen then opens with its coordinates. Waypoints
 * belong to the minimap; Xaero's World Map only shows them, so it alone cannot take one.
 */
final class XaeroWaypoints {
   /** The regular and the Fair-Play edition share the waypoint code. */
   private static final String[] MINIMAP_MOD_IDS = {"xaerominimap", "xaerominimapfair"};

   private XaeroWaypoints() {
   }

   static boolean available() {
      for (String modId : MINIMAP_MOD_IDS) {
         if (TellusPlatform.isModLoaded(modId)) {
            return true;
         }
      }
      return false;
   }

   /**
    * Sends the command Xaero's Minimap catches before it leaves the client; it replaces the current screen with its
    * own. Returns false when there is no connection to send it on.
    */
   static boolean openNewWaypoint(Minecraft minecraft, SlippyMapPoint marker, double worldScale) {
      ClientPacketListener connection = minecraft.getConnection();
      if (connection == null) {
         return false;
      }

      String name = Component.translatable("gui.earth.waypoint.name").getString();
      connection.sendCommand(
         XaeroWaypointCommand.build(
            name, MapMarkerText.blockX(marker, worldScale), MapMarkerText.blockZ(marker, worldScale), XaeroWaypointCommand.RED
         )
      );
      return true;
   }
}
