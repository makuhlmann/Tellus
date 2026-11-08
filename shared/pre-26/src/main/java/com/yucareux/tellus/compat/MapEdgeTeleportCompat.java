package com.yucareux.tellus.compat;

import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/**
 * Moves an entity across a map edge within its level (Minecraft 1.20.1 and 1.21.1).
 *
 * <p>Positions are absolute. A {@code null} velocity keeps the client's own velocity exactly (relative axes in the
 * position packet); otherwise the velocity is sent after the move. A {@code NaN} yaw keeps the current yaw.</p>
 */
public final class MapEdgeTeleportCompat {
   private MapEdgeTeleportCompat() {
   }

   public static void movePlayer(ServerPlayer player, double x, double y, double z, float yaw, Vec3 velocity) {
      // On relative axes the client keeps its velocity; the server still sends the target as an absolute position.
      EnumSet<RelativeMovement> relative = EnumSet.of(RelativeMovement.X_ROT);
      if (Float.isNaN(yaw)) {
         relative.add(RelativeMovement.Y_ROT);
      }
      if (velocity == null) {
         relative.add(RelativeMovement.X);
         relative.add(RelativeMovement.Y);
         relative.add(RelativeMovement.Z);
      }

      player.connection.teleport(x, y, z, Float.isNaN(yaw) ? player.getYRot() : yaw, player.getXRot(), relative);
      if (velocity != null) {
         player.setDeltaMovement(velocity);
         player.connection.send(new ClientboundSetEntityMotionPacket(player.getId(), velocity));
      }
      player.resetFallDistance();
   }

   /**
    * Moves a vehicle and everything riding it. {@code controller} is the player steering it, or {@code null} when the
    * server moves it (a minecart, say).
    */
   public static void moveVehicle(Entity vehicle, ServerPlayer controller, double x, double y, double z, float yaw, Vec3 velocity) {
      if (!Float.isNaN(yaw)) {
         vehicle.setYRot(yaw);
         vehicle.setYHeadRot(yaw);
      }

      // Moves the passengers with the vehicle on the server; their clients follow the vehicle.
      vehicle.teleportTo(x, y, z);
      if (velocity != null) {
         vehicle.setDeltaMovement(velocity);
      }
      vehicle.resetFallDistance();
      if (controller != null) {
         // The steering client ignores entity teleports for the vehicle it controls; this one it applies.
         controller.connection.send(new ClientboundMoveVehiclePacket(vehicle));
         if (velocity != null) {
            controller.connection.send(new ClientboundSetEntityMotionPacket(vehicle.getId(), velocity));
         }
      }
   }

   /**
    * Loads (and generates) the chunks within {@code radius} of a chunk for a while: the portal ticket lasts 300 ticks
    * and is renewed by calling again.
    */
   public static void holdArea(ServerLevel level, int chunkX, int chunkZ, int radius) {
      level.getChunkSource().addRegionTicket(
         TicketType.PORTAL, new ChunkPos(chunkX, chunkZ), radius, new BlockPos(chunkX << 4, 0, chunkZ << 4)
      );
   }

   /** Puts a passenger back on its vehicle. */
   public static void remount(Entity passenger, Entity vehicle) {
      passenger.startRiding(vehicle, true);
   }

   /** Turns a riding player without moving them. */
   public static void setRiderYaw(ServerPlayer rider, float yaw) {
      rider.connection.teleport(
         rider.getX(),
         rider.getY(),
         rider.getZ(),
         yaw,
         rider.getXRot(),
         EnumSet.of(RelativeMovement.X, RelativeMovement.Y, RelativeMovement.Z, RelativeMovement.X_ROT)
      );
   }
}
