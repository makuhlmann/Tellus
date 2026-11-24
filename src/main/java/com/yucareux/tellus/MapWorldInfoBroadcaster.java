package com.yucareux.tellus;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.compat.TellusMinecraftCompat;
import com.yucareux.tellus.network.GeoTpOpenMapPayload;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;

/**
 * Tells each client the world scale of the level its player is in, so its minimap can place the player without the
 * map open. It sends again whenever the player enters another level: after joining, through a portal, or on respawn.
 */
final class MapWorldInfoBroadcaster {
   private final Map<UUID, MapWorldInfoBroadcaster.Sent> sent = new HashMap<>();

   /** The map's packet for the player's current level; outside a Tellus level it carries a world scale of 0. */
   static GeoTpOpenMapPayload payloadFor(ServerPlayer player, boolean openMap) {
      ServerLevel level = MinecraftVersionCompat.serverLevel(player);
      if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator earthGenerator)) {
         return new GeoTpOpenMapPayload(0.0, 0.0, 0.0, false, openMap);
      }

      double latitude = Mth.clamp(earthGenerator.latitudeFromBlock(player.getZ()), -85.05112878, 85.05112878);
      double longitude = Mth.clamp(earthGenerator.longitudeFromBlock(player.getX()), -180.0, 180.0);
      // The map only uses this to offer the teleport button; handleGeoTeleport checks the permission itself.
      boolean canTeleport = TellusMinecraftCompat.hasGamemasterPermission(player.createCommandSourceStack());
      return new GeoTpOpenMapPayload(latitude, longitude, earthGenerator.settings().worldScale(), canTeleport, openMap);
   }

   void onServerTick(MinecraftServer server) {
      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         ServerLevel level = MinecraftVersionCompat.serverLevel(player);
         MapWorldInfoBroadcaster.Sent previous = this.sent.get(player.getUUID());
         // A respawn replaces the player object, and its client may have reset, so compare the object too.
         if (previous == null || previous.player() != player || previous.level() != level) {
            TellusPlatform.sendGeoTpOpenMapPayload(player, payloadFor(player, false));
            this.sent.put(player.getUUID(), new MapWorldInfoBroadcaster.Sent(player, level));
         }
      }
   }

   void onPlayerDisconnect(ServerPlayer player) {
      this.sent.remove(player.getUUID());
   }

   void reset() {
      this.sent.clear();
   }

   private record Sent(ServerPlayer player, ServerLevel level) {
   }
}
