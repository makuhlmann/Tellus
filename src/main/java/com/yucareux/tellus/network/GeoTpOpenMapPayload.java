package com.yucareux.tellus.network;

import com.yucareux.tellus.Tellus;
import java.util.Objects;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The map's view of the player's level. With openMap it opens the map at the player's position; without, it only tells
 * the client the world scale (0 outside a Tellus level), which the minimap needs. canTeleport only decides whether the
 * map offers teleporting: the server checks the permission again on a request.
 */
public record GeoTpOpenMapPayload(double latitude, double longitude, double worldScale, boolean canTeleport, boolean openMap)
   implements CustomPacketPayload {

   public static final CustomPacketPayload.Type<GeoTpOpenMapPayload> TYPE = new CustomPacketPayload.Type<>(Tellus.id("geotp_open_map"));
   public static final StreamCodec<FriendlyByteBuf, GeoTpOpenMapPayload> CODEC = StreamCodec.composite(
      ByteBufCodecs.DOUBLE,
      GeoTpOpenMapPayload::latitude,
      ByteBufCodecs.DOUBLE,
      GeoTpOpenMapPayload::longitude,
      ByteBufCodecs.DOUBLE,
      GeoTpOpenMapPayload::worldScale,
      ByteBufCodecs.BOOL,
      GeoTpOpenMapPayload::canTeleport,
      ByteBufCodecs.BOOL,
      GeoTpOpenMapPayload::openMap,
      GeoTpOpenMapPayload::fromBoxed
   );


   public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   private static GeoTpOpenMapPayload fromBoxed(Double latitude, Double longitude, Double worldScale, Boolean canTeleport, Boolean openMap) {
      return new GeoTpOpenMapPayload(
         Objects.requireNonNull(latitude, "latitude"),
         Objects.requireNonNull(longitude, "longitude"),
         Objects.requireNonNull(worldScale, "worldScale"),
         Objects.requireNonNull(canTeleport, "canTeleport"),
         Objects.requireNonNull(openMap, "openMap")
      );
   }
}
