package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Opens the map on the client at the player's position. The world scale lets the map show block coordinates, and
 * canTeleport only decides whether the map offers teleporting: the server checks the permission again on a request.
 */
public record GeoTpOpenMapPayload(double latitude, double longitude, double worldScale, boolean canTeleport) implements FabricPacket {
   public static final PacketType<GeoTpOpenMapPayload> TYPE = PacketType.create(Tellus.id("geotp_open_map"), GeoTpOpenMapPayload::new);

   public GeoTpOpenMapPayload(FriendlyByteBuf buffer) {
      this(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readBoolean());
   }

   @Override
   public void write(FriendlyByteBuf buffer) {
      buffer.writeDouble(this.latitude());
      buffer.writeDouble(this.longitude());
      buffer.writeDouble(this.worldScale());
      buffer.writeBoolean(this.canTeleport());
   }

   @Override
   public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
