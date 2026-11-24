package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.fabricmc.fabric.api.networking.v1.FabricPacket;
import net.fabricmc.fabric.api.networking.v1.PacketType;
import net.minecraft.network.FriendlyByteBuf;

/**
 * The map's view of the player's level. With openMap it opens the map at the player's position; without, it only tells
 * the client the world scale (0 outside a Tellus level), which the minimap needs. canTeleport only decides whether the
 * map offers teleporting: the server checks the permission again on a request.
 */
public record GeoTpOpenMapPayload(double latitude, double longitude, double worldScale, boolean canTeleport, boolean openMap)
   implements FabricPacket {
   public static final PacketType<GeoTpOpenMapPayload> TYPE = PacketType.create(Tellus.id("geotp_open_map"), GeoTpOpenMapPayload::new);

   public GeoTpOpenMapPayload(FriendlyByteBuf buffer) {
      this(buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readBoolean(), buffer.readBoolean());
   }

   @Override
   public void write(FriendlyByteBuf buffer) {
      buffer.writeDouble(this.latitude());
      buffer.writeDouble(this.longitude());
      buffer.writeDouble(this.worldScale());
      buffer.writeBoolean(this.canTeleport());
      buffer.writeBoolean(this.openMap());
   }

   @Override
   public PacketType<?> getType() {
      return Objects.requireNonNull(TYPE, "TYPE");
   }
}
