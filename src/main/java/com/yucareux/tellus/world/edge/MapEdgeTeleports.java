package com.yucareux.tellus.world.edge;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.TellusCommon;
import com.yucareux.tellus.compat.MapEdgeTeleportCompat;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.MapEdgeWrap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Moves players who reach a map edge to the other side (see {@link MapEdgeWrap}): across the ±180° meridian, or over
 * the pole. Only the player who crosses moves, together with the vehicle they ride and everyone on it; other players
 * are left alone.
 *
 * <p>While a player approaches an edge, the chunks where they would land are loaded, so they arrive in finished
 * terrain. Animals, items and empty vehicles crossing the edge near such a player, or near where the player would
 * land, are moved too, as long as the chunk they land in is loaded and ticking; elsewhere the barrier stops them.</p>
 *
 * <p>Everyone is set down at the same height above the ground as before the crossing (see {@link MapEdgeLanding}).
 * When the landing chunk is not loaded yet the client waits in place for it, as after a long {@code /tp}, and the
 * height is set once the server has it.</p>
 */
public final class MapEdgeTeleports {
   private static final int LANDING_CHECK_TICKS = 200;
   /** Radius, in chunks, loaded around where a player near an edge would land. */
   private static final int HOLD_RADIUS_CHUNKS = 2;
   /** Loading ahead only starts once a player moves toward the edge at least this fast (blocks a tick). */
   private static final double MIN_APPROACH_SPEED = 0.05;
   /** How far ahead of an edge loading starts: ten seconds at the current speed, within these bounds. */
   private static final double LOOKAHEAD_TICKS = 200.0;
   private static final double LOOKAHEAD_MIN_BLOCKS = 96.0;
   /** Vehicles wait at the edge until the far side is ready, so loading for them starts further out. */
   private static final double VEHICLE_LOOKAHEAD_MIN_BLOCKS = 256.0;
   private static final double LOOKAHEAD_MAX_BLOCKS = 768.0;
   /** Half the length of edge searched for other entities, around each player near it and their landing point. */
   private static final double STRIP_HALF_LENGTH = HOLD_RADIUS_CHUNKS * 16.0;
   /** Depth of that search inside the map: enough for wide hitboxes to reach the trigger gap. */
   private static final double STRIP_DEPTH = 6.0;
   /**
    * How long a vehicle with riders waits at the edge for its landing chunk; after that the riders cross without it
    * (terrain far from any cache can take this long to download and generate).
    */
   private static final int VEHICLE_WAIT_TICKS = 1200;
   private static final int WAIT_NOTICE_TICKS = 20;

   private final MapEdgeCrossingPlanner planner = new MapEdgeCrossingPlanner();
   private final Map<UUID, Landing> landings = new HashMap<>();
   private final Map<UUID, HeldArea> holds = new HashMap<>();
   private final Map<UUID, Long> waitingSince = new HashMap<>();

   public void onServerTick(MinecraftServer server) {
      long tick = server.getTickCount();
      Set<UUID> sampled = new HashSet<>();
      Set<UUID> holding = new HashSet<>();
      for (ServerLevel level : server.getAllLevels()) {
         if (!(level.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator)) {
            continue;
         }

         MapEdgeWrap wrap = generator.mapEdgeWrap();
         if (!wrap.enabled() || !TellusCommon.mapEdgeTeleportEnabled(generator.settings())) {
            continue;
         }

         List<ServerPlayer> players = List.copyOf(level.players());
         Set<Entity> handled = Collections.newSetFromMap(new IdentityHashMap<>());
         List<EdgeStrip> strips = new ArrayList<>();
         for (ServerPlayer player : players) {
            Entity root = player.getRootVehicle();
            if (!handled.add(root) || !canMove(player)) {
               continue;
            }

            UUID rootId = root.getUUID();
            sampled.add(rootId);
            double halfWidth = root.getBbWidth() / 2.0;
            MapEdgeCrossingPlanner.Jump jump = this.planner.plan(
               rootId, level, tick, root.getX(), root.getY(), root.getZ(), halfWidth, root.getYRot(), wrap
            );
            Arrival arrival = jump == null ? Arrival.READY : this.arrival(level, root, players, jump, tick);
            if (arrival == Arrival.WAIT) {
               this.planner.forget(rootId);
               jump = null;
            }
            if (jump != null) {
               this.moveCarryingPlayers(level, root, players, jump, tick, wrap, arrival == Arrival.WITHOUT_VEHICLE);
            } else {
               this.prepareFarSide(level, player, root, halfWidth, wrap, tick, strips, holding);
            }
         }

         this.moveOtherEntities(level, wrap, tick, strips, handled, sampled);
      }

      this.planner.retainOnly(sampled);
      this.waitingSince.keySet().retainAll(sampled);
      this.releaseHoldsExcept(holding);
      this.checkLandings(server, tick);
   }

   public void onPlayerDisconnect(ServerPlayer player) {
      this.planner.forget(player.getUUID());
      this.landings.remove(player.getUUID());
      HeldArea held = this.holds.remove(player.getUUID());
      if (held != null) {
         release(held);
      }
   }

   public void reset() {
      this.planner.clear();
      this.landings.clear();
      this.holds.clear();
      this.waitingSince.clear();
   }

   /**
    * Whether a crossing can go ahead. A vehicle carrying players only crosses into a ticking chunk: only there can
    * clients be given it afresh (see {@link #resendToClients}); anywhere else they would slide their copy across the
    * map and stall with the riders in it. So it waits at the edge, held by the barrier, while the far side loads
    * (usually not at all, since it is loaded ahead), and after {@link #VEHICLE_WAIT_TICKS} the riders cross without it.
    */
   private Arrival arrival(ServerLevel level, Entity root, List<ServerPlayer> players, MapEdgeCrossingPlanner.Jump jump, long tick) {
      if (root instanceof ServerPlayer || jump.repeat()) {
         return Arrival.READY;
      }

      UUID id = root.getUUID();
      MapEdgeWrap.Crossing crossing = jump.crossing();
      if (level.isPositionEntityTicking(BlockPos.containing(crossing.x(), root.getY(), crossing.z()))) {
         this.waitingSince.remove(id);
         return Arrival.READY;
      }

      long waited = tick - this.waitingSince.computeIfAbsent(id, ignored -> tick);
      if (waited >= VEHICLE_WAIT_TICKS) {
         this.waitingSince.remove(id);
         return Arrival.WITHOUT_VEHICLE;
      }
      if (waited % WAIT_NOTICE_TICKS == 0) {
         for (ServerPlayer player : players) {
            if (player.getRootVehicle() == root) {
               player.sendSystemMessage(Component.translatable("tellus.map_edge.waiting"), true);
            }
         }
      }
      return Arrival.WAIT;
   }

   private static boolean canMove(ServerPlayer player) {
      return player.isAlive() && !player.isRemoved() && !player.isSleeping() && player.getCamera() == player;
   }

   /**
    * Loads where a player approaching an edge would land, at most one area per player (see {@link MapEdgeHoldPolicy}),
    * and notes the stretches of edge where other entities may cross: beside the player, and around that area.
    */
   private void prepareFarSide(
      ServerLevel level,
      ServerPlayer player,
      Entity root,
      double halfWidth,
      MapEdgeWrap wrap,
      long tick,
      List<EdgeStrip> strips,
      Set<UUID> holding
   ) {
      MapEdgeCrossingPlanner.Motion motion = this.planner.motion(root.getUUID());
      double minimum = root == player ? LOOKAHEAD_MIN_BLOCKS : VEHICLE_LOOKAHEAD_MIN_BLOCKS;
      double lookahead = Mth.clamp(Math.hypot(motion.x(), motion.z()) * LOOKAHEAD_TICKS, minimum, LOOKAHEAD_MAX_BLOCKS);
      if (wrap.distanceToEdge(root.getX(), root.getZ(), halfWidth) > lookahead) {
         return;
      }

      UUID playerId = player.getUUID();
      holding.add(playerId);
      strips.add(EdgeStrip.nearest(wrap, root.getX(), root.getZ()));
      HeldArea held = this.holds.get(playerId);
      if (held != null && held.level() != level) {
         release(held);
         this.holds.remove(playerId);
         held = null;
      }

      MapEdgeWrap.Crossing landing = wrap.predict(
         root.getX(), root.getZ(), halfWidth, motion.x(), motion.z(), lookahead, MIN_APPROACH_SPEED
      );
      if (landing != null) {
         int chunkX = Mth.floor(landing.x()) >> 4;
         int chunkZ = Mth.floor(landing.z()) >> 4;
         MapEdgeHoldPolicy.Hold hold = held == null ? null : held.hold();
         boolean loaded = held != null && level.getChunkSource().getChunkNow(held.hold().chunkX(), held.hold().chunkZ()) != null;
         switch (MapEdgeHoldPolicy.decide(hold, chunkX, chunkZ, tick, loaded)) {
            case PLACE -> held = place(level, chunkX, chunkZ, tick);
            case MOVE -> {
               release(held);
               held = place(level, chunkX, chunkZ, tick);
            }
            case REFRESH -> held = refresh(held, tick);
            case KEEP -> {
            }
         }
      } else if (held != null && tick - held.hold().refreshedTick() >= MapEdgeHoldPolicy.REFRESH_TICKS) {
         // Standing still or turning at the edge: keep what is loaded, load nothing new.
         held = refresh(held, tick);
      }

      if (held != null) {
         this.holds.put(playerId, held);
         double centreX = (held.hold().chunkX() << 4) + 8.0;
         double centreZ = (held.hold().chunkZ() << 4) + 8.0;
         strips.add(EdgeStrip.nearest(wrap, centreX, centreZ));
      }
   }

   private static HeldArea place(ServerLevel level, int chunkX, int chunkZ, long tick) {
      MapEdgeTeleportCompat.holdArea(level, chunkX, chunkZ, HOLD_RADIUS_CHUNKS);
      return new HeldArea(level, new MapEdgeHoldPolicy.Hold(chunkX, chunkZ, tick, tick));
   }

   private static HeldArea refresh(HeldArea held, long tick) {
      MapEdgeTeleportCompat.holdArea(held.level(), held.hold().chunkX(), held.hold().chunkZ(), HOLD_RADIUS_CHUNKS);
      return new HeldArea(held.level(), held.hold().refreshed(tick));
   }

   private static void release(HeldArea held) {
      MapEdgeTeleportCompat.releaseArea(held.level(), held.hold().chunkX(), held.hold().chunkZ(), HOLD_RADIUS_CHUNKS);
   }

   /** Lets go of the areas of players no longer near an edge. */
   private void releaseHoldsExcept(Set<UUID> holding) {
      Iterator<Map.Entry<UUID, HeldArea>> iterator = this.holds.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, HeldArea> entry = iterator.next();
         if (!holding.contains(entry.getKey())) {
            release(entry.getValue());
            iterator.remove();
         }
      }
   }

   private void moveCarryingPlayers(
      ServerLevel level,
      Entity root,
      List<ServerPlayer> players,
      MapEdgeCrossingPlanner.Jump jump,
      long tick,
      MapEdgeWrap wrap,
      boolean leaveVehicle
   ) {
      MapEdgeWrap.Crossing crossing = jump.crossing();
      // A repeat of a move the entity was dragged back from measures nothing: it keeps the first measurement.
      double clearance = jump.repeat() ? Double.NaN : groundClearance(level, root, wrap);
      double y = landingY(level, root, clearance, crossing.x(), crossing.z());
      List<ServerPlayer> moved = new ArrayList<>();
      if (root instanceof ServerPlayer player) {
         // Over the pole the yaw is mirrored and the north/south motion reverses; across 180° nothing turns.
         Vec3 velocity = crossing.mirror() ? new Vec3(jump.motionX(), jump.motionY(), -jump.motionZ()) : null;
         MapEdgeTeleportCompat.movePlayer(player, crossing.x(), y, crossing.z(), crossing.mirror() ? crossing.yaw() : Float.NaN, velocity);
         moved.add(player);
      } else {
         this.moveVehicleWithRiders(level, root, players, jump, y, moved, leaveVehicle);
      }

      for (ServerPlayer player : moved) {
         Landing previous = this.landings.get(player.getUUID());
         double kept = jump.repeat() && previous != null ? previous.clearance() : clearance;
         this.landings.put(player.getUUID(), new Landing(level, tick + LANDING_CHECK_TICKS, kept));
      }
      if (Tellus.LOGGER.isDebugEnabled()) {
         Tellus.LOGGER.debug(
            "Moved {} across the {} map edge to {}, {}, {}{}",
            root.getName().getString(),
            crossing.kind(),
            Math.round(crossing.x()),
            Math.round(y),
            Math.round(crossing.z()),
            jump.repeat() ? " again" : ""
         );
      }
   }

   /**
    * Moves a vehicle carrying players. A client slides an entity it sees moved toward the new position over a few
    * ticks, so across the map a vehicle would stall in the first chunk the client does not have, its riders stuck with
    * it. The riders therefore get off, travel as players (which clients apply at once), and get back on; the vehicle
    * itself is sent to clients afresh at the landing point. With {@code leaveVehicle} only the riders cross.
    */
   private void moveVehicleWithRiders(
      ServerLevel level,
      Entity vehicle,
      List<ServerPlayer> players,
      MapEdgeCrossingPlanner.Jump jump,
      double y,
      List<ServerPlayer> moved,
      boolean leaveVehicle
   ) {
      MapEdgeWrap.Crossing crossing = jump.crossing();
      List<Rider> riders = new ArrayList<>();
      for (ServerPlayer player : players) {
         if (player != vehicle && player.getRootVehicle() == vehicle && player.getVehicle() != null) {
            riders.add(new Rider(player, player.getVehicle(), player.position().subtract(vehicle.position()), player.getYRot()));
         }
      }

      // Steered vehicles report their motion through their driver, whose server-side velocity is stale; others are
      // simulated on the server.
      boolean steered = vehicle.getControllingPassenger() instanceof Player;
      Vec3 motion = steered ? new Vec3(jump.motionX(), jump.motionY(), jump.motionZ()) : vehicle.getDeltaMovement();
      Vec3 velocity = crossing.mirror() ? new Vec3(motion.x, motion.y, -motion.z) : motion;
      for (Rider rider : riders) {
         rider.player().stopRiding();
         // Seated, a client takes its own position packets as acknowledgements only: get it off first.
         rider.player().connection.send(new ClientboundSetPassengersPacket(rider.vehicle()));
      }

      if (!leaveVehicle) {
         MapEdgeTeleportCompat.moveVehicle(vehicle, null, crossing.x(), y, crossing.z(), crossing.mirror() ? crossing.yaw() : Float.NaN, null);
         vehicle.setDeltaMovement(velocity);
         resendToClients(level, vehicle, crossing.x(), y, crossing.z());
      }
      for (Rider rider : riders) {
         Vec3 offset = crossing.mirror() ? new Vec3(rider.offset().x, rider.offset().y, -rider.offset().z) : rider.offset();
         MapEdgeTeleportCompat.movePlayer(
            rider.player(),
            crossing.x() + offset.x,
            y + offset.y,
            crossing.z() + offset.z,
            crossing.mirror() ? MapEdgeWrap.mirrorYaw(rider.yaw()) : Float.NaN,
            null
         );
         if (!leaveVehicle) {
            MapEdgeTeleportCompat.remount(rider.player(), rider.vehicle());
         }
         moved.add(rider.player());
      }
   }

   /**
    * Makes clients drop their copy of a moved entity and receive it again where it is now, instead of sliding it there.
    * Only while its landing chunk is tracked: otherwise the server stops tracking it as it moves, and starts again
    * (sending it afresh) when that chunk loads.
    */
   private static void resendToClients(ServerLevel level, Entity entity, double x, double y, double z) {
      if (!level.isPositionEntityTicking(BlockPos.containing(x, y, z))) {
         return;
      }

      ServerChunkCache chunks = level.getChunkSource();
      chunks.removeEntity(entity);
      chunks.addEntity(entity);
   }

   /** Moves animals, items and empty vehicles that reach the edge where players are, into loaded chunks only. */
   private void moveOtherEntities(
      ServerLevel level, MapEdgeWrap wrap, long tick, List<EdgeStrip> strips, Set<Entity> handled, Set<UUID> sampled
   ) {
      if (strips.isEmpty()) {
         return;
      }

      int minY = MinecraftVersionCompat.minY(level);
      int maxY = minY + level.getHeight();
      for (EdgeStrip strip : new LinkedHashSet<>(strips)) {
         for (Entity entity : level.getEntities((Entity)null, strip.box(wrap, minY, maxY), MapEdgeTeleports::crossesOnItsOwn)) {
            if (!handled.add(entity)) {
               continue;
            }

            UUID id = entity.getUUID();
            sampled.add(id);
            MapEdgeCrossingPlanner.Jump jump = this.planner.plan(
               id, level, tick, entity.getX(), entity.getY(), entity.getZ(), entity.getBbWidth() / 2.0, entity.getYRot(), wrap
            );
            if (jump == null || jump.repeat()) {
               continue;
            }

            MapEdgeWrap.Crossing crossing = jump.crossing();
            if (!level.isPositionEntityTicking(BlockPos.containing(crossing.x(), entity.getY(), crossing.z()))) {
               // Nowhere loaded to land yet: the barrier holds it until there is.
               this.planner.forget(id);
               continue;
            }

            double y = landingY(level, entity, groundClearance(level, entity, wrap), crossing.x(), crossing.z());
            Vec3 motion = entity.getDeltaMovement();
            MapEdgeTeleportCompat.moveVehicle(
               entity,
               null,
               crossing.x(),
               y,
               crossing.z(),
               crossing.mirror() ? crossing.yaw() : Float.NaN,
               crossing.mirror() ? new Vec3(motion.x, motion.y, -motion.z) : null
            );
         }
      }
   }

   /** Entities that cross on their own: anything that walks, drops or drifts, but no players, riders or projectiles. */
   private static boolean crossesOnItsOwn(Entity entity) {
      if (entity instanceof Player || entity.isPassenger() || !entity.isAlive() || entity instanceof Projectile || entity instanceof HangingEntity) {
         return false;
      }
      for (Entity passenger : entity.getIndirectPassengers()) {
         if (passenger instanceof Player) {
            // Crosses with its rider.
            return false;
         }
      }
      return entity instanceof LivingEntity
         || entity instanceof ItemEntity
         || entity instanceof ExperienceOrb
         || entity.getDeltaMovement().lengthSqr() > 1.0E-4;
   }

   /**
    * Height above the ground before a crossing, or {@code NaN} to keep the absolute height: for spectators, and where
    * there is no ground to measure (past the cut, or a chunk the server does not have).
    */
   private static double groundClearance(ServerLevel level, Entity entity, MapEdgeWrap wrap) {
      if (entity instanceof ServerPlayer player && player.isSpectator()) {
         return Double.NaN;
      }

      int blockX = Mth.floor(entity.getX());
      int blockZ = Mth.floor(entity.getZ());
      if (wrap.isPastCut(blockX, blockZ) || level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) {
         return Double.NaN;
      }

      return entity.getY() - level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ);
   }

   /**
    * The height to land at when the landing chunk is already loaded (the usual case, since it is loaded ahead of a
    * player): the same height above the ground, lifted onto blocks it would be inside. Otherwise the current height,
    * corrected once the chunk is there.
    */
   private static double landingY(ServerLevel level, Entity entity, double clearance, double x, double z) {
      int blockX = Mth.floor(x);
      int blockZ = Mth.floor(z);
      if (level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) {
         return entity.getY();
      }

      double targetY = MapEdgeLanding.targetY(
         entity.getY(), clearance, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, blockX, blockZ), MinecraftVersionCompat.minY(level)
      );
      AABB box = entity.getBoundingBox().move(x - entity.getX(), targetY - entity.getY(), z - entity.getZ());
      if (!level.noCollision(entity, box)) {
         targetY = MapEdgeLanding.liftedY(targetY, level.getHeight(Heightmap.Types.MOTION_BLOCKING, blockX, blockZ));
      }
      return targetY;
   }

   /**
    * Sets players down at their height above the ground once the server has the chunk they landed in, when that was
    * not loaded at the crossing, and lifts anyone who would end up inside blocks onto them.
    */
   private void checkLandings(MinecraftServer server, long tick) {
      Set<Entity> settledRoots = Collections.newSetFromMap(new IdentityHashMap<>());
      Iterator<Map.Entry<UUID, Landing>> iterator = this.landings.entrySet().iterator();
      while (iterator.hasNext()) {
         Map.Entry<UUID, Landing> entry = iterator.next();
         Landing landing = entry.getValue();
         ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
         if (player == null || player.level() != landing.level() || tick > landing.untilTick()) {
            iterator.remove();
            continue;
         }

         Entity root = player.getRootVehicle();
         if (this.planner.isPending(root.getUUID())) {
            continue;
         }

         int blockX = Mth.floor(root.getX());
         int blockZ = Mth.floor(root.getZ());
         ServerLevel level = landing.level();
         if (level.getChunkSource().getChunkNow(blockX >> 4, blockZ >> 4) == null) {
            continue;
         }

         iterator.remove();
         player.resetFallDistance();
         if (!settledRoots.add(root)) {
            // Another rider of the same vehicle already set it down.
            continue;
         }

         root.resetFallDistance();
         double currentY = root.getY();
         double targetY = landingY(level, root, landing.clearance(), root.getX(), root.getZ());
         if (Math.abs(targetY - currentY) < 1.0E-3) {
            continue;
         }

         if (root == player) {
            MapEdgeTeleportCompat.movePlayer(player, root.getX(), targetY, root.getZ(), Float.NaN, null);
         } else {
            ServerPlayer controller = root.getControllingPassenger() instanceof ServerPlayer driver ? driver : null;
            MapEdgeTeleportCompat.moveVehicle(root, controller, root.getX(), targetY, root.getZ(), Float.NaN, null);
         }
      }
   }

   /** @param clearance height above the ground before the crossing, {@code NaN} to keep the absolute height */
   private record Landing(ServerLevel level, long untilTick, double clearance) {
   }

   private record HeldArea(ServerLevel level, MapEdgeHoldPolicy.Hold hold) {
   }

   private enum Arrival {
      READY,
      WAIT,
      WITHOUT_VEHICLE
   }

   private record Rider(ServerPlayer player, Entity vehicle, Vec3 offset, float yaw) {
   }

   /** A stretch of one map edge, searched for entities that reach it. */
   private record EdgeStrip(MapEdgeWrap.Crossing.Kind edge, double along) {
      static EdgeStrip nearest(MapEdgeWrap wrap, double x, double z) {
         double east = wrap.maxXExclusive() - x;
         double west = x - wrap.minX();
         double south = wrap.maxZExclusive() - z;
         double north = z - wrap.minZ();
         double nearest = Math.min(Math.min(east, west), Math.min(south, north));
         // Rounded to whole chunks, so the strips of nearby players coincide and are searched once.
         if (nearest == north) {
            return new EdgeStrip(MapEdgeWrap.Crossing.Kind.NORTH, Math.floor(x / 16.0) * 16.0);
         } else if (nearest == south) {
            return new EdgeStrip(MapEdgeWrap.Crossing.Kind.SOUTH, Math.floor(x / 16.0) * 16.0);
         } else if (nearest == east) {
            return new EdgeStrip(MapEdgeWrap.Crossing.Kind.EAST, Math.floor(z / 16.0) * 16.0);
         } else {
            return new EdgeStrip(MapEdgeWrap.Crossing.Kind.WEST, Math.floor(z / 16.0) * 16.0);
         }
      }

      AABB box(MapEdgeWrap wrap, int minY, int maxY) {
         double from = this.along - STRIP_HALF_LENGTH;
         double to = this.along + 16.0 + STRIP_HALF_LENGTH;
         return switch (this.edge) {
            case EAST -> new AABB(wrap.maxXExclusive() - STRIP_DEPTH, minY, from, wrap.maxXExclusive() + 1.0, maxY, to);
            case WEST -> new AABB(wrap.minX() - 1.0, minY, from, wrap.minX() + STRIP_DEPTH, maxY, to);
            case NORTH -> new AABB(from, minY, wrap.minZ() - 1.0, to, maxY, wrap.minZ() + STRIP_DEPTH);
            case SOUTH -> new AABB(from, minY, wrap.maxZExclusive() - STRIP_DEPTH, to, maxY, wrap.maxZExclusive() + 1.0);
         };
      }
   }
}
