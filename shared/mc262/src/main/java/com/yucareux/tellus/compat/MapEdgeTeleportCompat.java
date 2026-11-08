package com.yucareux.tellus.compat;

import java.util.EnumSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Moves an entity across a map edge within its level (Minecraft 1.21.11, 26.2 and 26.3).
 *
 * <p>Positions are absolute. A {@code null} velocity keeps the client's own velocity exactly (relative delta axes);
 * otherwise X and Y stay the client's and only Z is set, since a crossing over the pole reverses just that axis.
 * A {@code NaN} yaw keeps the current yaw. On relative axes the values sent are offsets, here always zero.</p>
 */
public final class MapEdgeTeleportCompat {
   private MapEdgeTeleportCompat() {
   }

   public static void movePlayer(ServerPlayer player, double x, double y, double z, float yaw, Vec3 velocity) {
      EnumSet<Relative> relative = relatives(yaw, velocity);
      Vec3 delta = velocity == null ? Vec3.ZERO : new Vec3(0.0, 0.0, velocity.z);
      player.connection.teleport(new PositionMoveRotation(new Vec3(x, y, z), delta, Float.isNaN(yaw) ? 0.0F : yaw, 0.0F), relative);
      player.resetFallDistance();
   }

   /**
    * Moves a vehicle and everything riding it. {@code controller} is the player steering it, or {@code null} when the
    * server moves it; passengers follow as passengers and steering clients apply the move themselves.
    */
   public static void moveVehicle(Entity vehicle, ServerPlayer controller, double x, double y, double z, float yaw, Vec3 velocity) {
      if (!(vehicle.level() instanceof ServerLevel level)) {
         return;
      }

      EnumSet<Relative> relative = relatives(yaw, velocity);
      Vec3 delta = velocity == null ? Vec3.ZERO : new Vec3(0.0, 0.0, velocity.z);
      vehicle.teleport(
         new TeleportTransition(level, new Vec3(x, y, z), delta, Float.isNaN(yaw) ? 0.0F : yaw, 0.0F, relative, TeleportTransition.DO_NOTHING)
      );
      vehicle.resetFallDistance();
   }

   /**
    * Loads (and simulates) the chunks within {@code radius} of a chunk for a while: the ender pearl ticket lasts 40
    * ticks, is not saved with the world, and is renewed by calling again.
    */
   public static void holdArea(ServerLevel level, int chunkX, int chunkZ, int radius) {
      level.getChunkSource().addTicketWithRadius(TicketType.ENDER_PEARL, new ChunkPos(chunkX, chunkZ), radius);
   }

   /** Puts a passenger back on its vehicle. */
   public static void remount(Entity passenger, Entity vehicle) {
      passenger.startRiding(vehicle, true, true);
   }

   /**
    * Nothing to do here: the vehicle's transition turns its passengers with it, keeping each one's offset to the
    * vehicle, and a riding client only acknowledges its own position packets.
    */
   public static void setRiderYaw(ServerPlayer rider, float yaw) {
   }

   private static EnumSet<Relative> relatives(float yaw, Vec3 velocity) {
      EnumSet<Relative> relative = EnumSet.of(Relative.X_ROT, Relative.DELTA_X, Relative.DELTA_Y);
      if (Float.isNaN(yaw)) {
         relative.add(Relative.Y_ROT);
      }
      if (velocity == null) {
         relative.add(Relative.DELTA_Z);
      }
      return relative;
   }
}
