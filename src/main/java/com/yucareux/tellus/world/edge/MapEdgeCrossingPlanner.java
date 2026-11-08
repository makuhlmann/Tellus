package com.yucareux.tellus.world.edge;

import com.yucareux.tellus.worldgen.MapEdgeWrap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides, tick by tick, when an entity carrying players crosses a map edge. Pure bookkeeping: the caller samples
 * each moving root entity (a player on foot, or the vehicle players ride) once per tick and performs the moves.
 *
 * <p>After a move the entity is left alone until it has arrived. A singleplayer host's vehicle can be dragged back by
 * movement packets its client sent before it saw the move; the same move is then repeated a few times.</p>
 */
public final class MapEdgeCrossingPlanner {
   static final double ARRIVED_DISTANCE = 16.0;
   static final double BOUNCED_DISTANCE = 256.0;
   static final int ARRIVED_TICKS = 2;
   static final int PENDING_TIMEOUT_TICKS = 40;
   static final int MAX_REPEATS = 3;
   /** The most a client can report as one tick of motion (the velocity packet's range). */
   static final double MAX_MOTION_PER_TICK = 3.9;

   private final Map<UUID, Track> tracks = new HashMap<>();
   private final Map<UUID, Pending> pending = new HashMap<>();

   /**
    * Samples {@code entity} at {@code tick}. Returns the move to make now, or {@code null}.
    *
    * @param level identity of the entity's level; samples from another level do not count as motion
    * @param halfWidth half the width of the entity's hitbox
    */
   public Jump plan(UUID entity, Object level, long tick, double x, double y, double z, double halfWidth, float yaw, MapEdgeWrap wrap) {
      Track previous = this.tracks.get(entity);
      double motionX = 0.0;
      double motionY = 0.0;
      double motionZ = 0.0;
      if (previous != null && previous.level() == level && previous.tick() == tick - 1) {
         motionX = clampMotion(x - previous.x());
         motionY = clampMotion(y - previous.y());
         motionZ = clampMotion(z - previous.z());
      }
      this.tracks.put(entity, new Track(level, tick, x, y, z, motionX, motionZ));

      Pending waiting = this.pending.get(entity);
      if (waiting != null) {
         if (waiting.level != level || tick - waiting.issuedTick > PENDING_TIMEOUT_TICKS) {
            this.pending.remove(entity);
         } else {
            double distance = Math.hypot(x - waiting.jump.crossing().x(), z - waiting.jump.crossing().z());
            if (distance <= ARRIVED_DISTANCE) {
               if (++waiting.ticksNear >= ARRIVED_TICKS) {
                  this.pending.remove(entity);
               }
               return null;
            }

            waiting.ticksNear = 0;
            if (distance > BOUNCED_DISTANCE && waiting.repeats < MAX_REPEATS) {
               waiting.repeats++;
               this.tracks.put(
                  entity, new Track(level, tick, waiting.jump.crossing().x(), y, waiting.jump.crossing().z(), motionX, motionZ)
               );
               return waiting.jump.repeated();
            }
            return null;
         }
      }

      MapEdgeWrap.Crossing crossing = wrap.cross(x, z, halfWidth, motionX, motionZ, yaw);
      if (crossing == null) {
         return null;
      }

      Jump jump = new Jump(crossing, motionX, motionY, motionZ, false);
      this.pending.put(entity, new Pending(level, tick, jump));
      // Motion after the move is measured from the landing point, not across the map.
      this.tracks.put(entity, new Track(level, tick, crossing.x(), y, crossing.z(), motionX, motionZ));
      return jump;
   }

   /** What {@code entity} travelled over the tick it was last sampled, or nothing when that is unknown. */
   public Motion motion(UUID entity) {
      Track track = this.tracks.get(entity);
      return track == null ? Motion.NONE : new Motion(track.motionX(), track.motionZ());
   }

   /** Whether a move for {@code entity} is still waiting for it to arrive. */
   public boolean isPending(UUID entity) {
      return this.pending.containsKey(entity);
   }

   public void forget(UUID entity) {
      this.tracks.remove(entity);
      this.pending.remove(entity);
   }

   /** Drops everything about entities not sampled in the last pass. */
   public void retainOnly(Set<UUID> sampled) {
      this.tracks.keySet().retainAll(sampled);
      this.pending.keySet().retainAll(sampled);
   }

   public void clear() {
      this.tracks.clear();
      this.pending.clear();
   }

   private static double clampMotion(double motion) {
      return Math.max(-MAX_MOTION_PER_TICK, Math.min(MAX_MOTION_PER_TICK, motion));
   }

   /**
    * A move across an edge. The motion is what the entity travelled over the last tick; a crossing over the pole
    * keeps it but reverses its Z component.
    *
    * @param repeat whether this repeats an earlier move the entity was dragged back from
    */
   public record Jump(MapEdgeWrap.Crossing crossing, double motionX, double motionY, double motionZ, boolean repeat) {
      Jump repeated() {
         return new Jump(this.crossing, this.motionX, this.motionY, this.motionZ, true);
      }
   }

   /** Horizontal blocks travelled in one tick. */
   public record Motion(double x, double z) {
      static final Motion NONE = new Motion(0.0, 0.0);
   }

   private record Track(Object level, long tick, double x, double y, double z, double motionX, double motionZ) {
   }

   private static final class Pending {
      private final Object level;
      private final long issuedTick;
      private final Jump jump;
      private int ticksNear;
      private int repeats;

      private Pending(Object level, long issuedTick, Jump jump) {
         this.level = level;
         this.issuedTick = issuedTick;
         this.jump = jump;
      }
   }
}
