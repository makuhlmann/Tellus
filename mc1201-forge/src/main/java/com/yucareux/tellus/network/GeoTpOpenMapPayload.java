package com.yucareux.tellus.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Opens the map on the client at the player's position. The world scale lets the map show block coordinates, and
 * canTeleport only decides whether the map offers teleporting: the server checks the permission again on a request.
 */
public record GeoTpOpenMapPayload(double latitude, double longitude, double worldScale, boolean canTeleport) {
   public GeoTpOpenMapPayload(FriendlyByteBuf buffer) {
      this(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readBoolean());
   }

   public void write(FriendlyByteBuf buffer) {
      buffer.writeDouble(this.latitude());
      buffer.writeDouble(this.longitude());
      buffer.writeDouble(this.worldScale());
      buffer.writeBoolean(this.canTeleport());
   }
}
