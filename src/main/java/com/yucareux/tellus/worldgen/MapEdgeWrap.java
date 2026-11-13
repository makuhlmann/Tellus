package com.yucareux.tellus.worldgen;

/**
 * Where the generated Earth ends and how a player crossing that edge comes back in on the other side.
 *
 * <p>East and west the map ends at the ±180° meridian. North and south it ends where the map data does, which is short
 * of the projection's edge: Overture's ocean polygons (the only ocean Tellus draws, and the Arctic is all ocean) stop
 * at {@link #OVERTURE_NORTH_LIMIT}, and Mapterhorn's elevation at {@link #MAPTERHORN_SOUTH_LIMIT}. Each cut is snapped
 * inward to whole chunks. Everything past it stays empty, with an invisible barrier in the first empty column or row.</p>
 *
 * <p>An entity whose hitbox comes within {@link #TRIGGER_GAP} of a cut is moved: across the ±180° meridian to the same
 * latitude on the other side, or across a polar edge to where the great circle through the hidden polar cap comes back
 * out, facing away from the pole. It lands {@link #LANDING_GAP} from the far edge. The two sides do not overlap, so a
 * crossing skips the few blocks between the cuts and the true edges.</p>
 */
public final class MapEdgeWrap {
   /** How close a hitbox gets to a cut before it is moved. */
   public static final double TRIGGER_GAP = 0.5;
   /** How far from the far cut a crossing lands: two blocks short of crossing back. */
   public static final double LANDING_GAP = TRIGGER_GAP + 2.0;
   /** How far from the cuts spawns, map teleports and portal destinations are kept. */
   public static final double SAFE_MARGIN = 8.0;
   /**
    * Latitude where Overture's ocean polygons end: the top of the second row of zoom-13 tiles. North of it nothing is
    * ocean, so the Arctic there would be dry land at sea level.
    */
   public static final double OVERTURE_NORTH_LIMIT = Math.toDegrees(Math.atan(Math.sinh(Math.PI * (1.0 - 2.0 / 8192.0))));
   /** Latitude (south) where Mapterhorn's elevation ends; south of it its tiles hold 0 m. */
   public static final double MAPTERHORN_SOUTH_LIMIT = 85.0;
   /**
    * The last elevation pixels before Mapterhorn's edge blend into its zeros: two pixels at its densest global zoom
    * (12), in projected metres.
    */
   static final double MAPTERHORN_EDGE_MARGIN_METERS = 2.0 * EarthProjection.EQUATOR_CIRCUMFERENCE_METERS / (512.0 * 4096.0);
   /** Highest block coordinate the packed coordinate profile can hold (see {@link HighYPackedCoordinateProfile}). */
   static final int PACKED_LIMIT = HighYPackedCoordinateProfile.X_MAX;
   /** Highest block coordinate vanilla keeps inside its world border. */
   static final int VANILLA_LIMIT = 29_999_984;
   private static final double STILL_SPEED = 0.03;

   public static final MapEdgeWrap DISABLED = new MapEdgeWrap(false, 0, 0, 0, 0, 0.0, 0.0, 0.0);

   private final boolean enabled;
   private final int minX;
   private final int maxXExclusive;
   private final int minZ;
   private final int maxZExclusive;
   private final double blocksPerDegree;
   private final double northLatitude;
   private final double southLatitude;

   private MapEdgeWrap(
      boolean enabled,
      int minX,
      int maxXExclusive,
      int minZ,
      int maxZExclusive,
      double blocksPerDegree,
      double northLatitude,
      double southLatitude
   ) {
      this.enabled = enabled;
      this.minX = minX;
      this.maxXExclusive = maxXExclusive;
      this.minZ = minZ;
      this.maxZExclusive = maxZExclusive;
      this.blocksPerDegree = blocksPerDegree;
      this.northLatitude = northLatitude;
      this.southLatitude = southLatitude;
   }

   /** The wrap for a world at {@code worldScale}, or {@link #DISABLED} when turned off or not representable. */
   public static MapEdgeWrap forWorld(double worldScale, boolean enabled) {
      if (!enabled || !(worldScale > 0.0)) {
         return DISABLED;
      }

      double northLatitude = Math.min(EarthProjection.edgeLatitude(), OVERTURE_NORTH_LIMIT);
      double southLatitude = Math.min(EarthProjection.edgeLatitude(), MAPTERHORN_SOUTH_LIMIT);
      return of(
         EarthProjection.halfWidthBlocks(worldScale),
         EarthProjection.latToBlockZ(northLatitude, worldScale),
         EarthProjection.latToBlockZ(-southLatitude, worldScale) - MAPTERHORN_EDGE_MARGIN_METERS / worldScale,
         EarthProjection.blocksPerDegree(worldScale),
         northLatitude,
         southLatitude,
         HighYPackedCoordinateProfile.isEnabled() ? PACKED_LIMIT : VANILLA_LIMIT
      );
   }

   /**
    * Builds the wrap from explicit extents. {@code northEdgeZ} (negative) and {@code southEdgeZ} are where the data
    * ends; {@code coordinateLimit} is the largest block coordinate the world can store, and when a barrier would lie
    * beyond it the wrap is disabled.
    */
   static MapEdgeWrap of(
      double halfWidth,
      double northEdgeZ,
      double southEdgeZ,
      double blocksPerDegree,
      double northLatitude,
      double southLatitude,
      int coordinateLimit
   ) {
      if (!(halfWidth > 0.0) || !(northEdgeZ < 0.0) || !(southEdgeZ > 0.0) || !(blocksPerDegree > 0.0)) {
         return DISABLED;
      }

      long halfX = (long)Math.floor(halfWidth / 16.0) * 16L;
      long minZ = (long)Math.ceil(northEdgeZ / 16.0) * 16L;
      long maxZ = (long)Math.floor(southEdgeZ / 16.0) * 16L;
      // Barriers sit at the first column or row past each cut; all must be representable, and the map must be wide.
      if (halfX + 1 > coordinateLimit || -minZ + 1 > coordinateLimit || maxZ + 1 > coordinateLimit || halfX < 64L || -minZ < 64L || maxZ < 64L) {
         return DISABLED;
      }

      return new MapEdgeWrap(
         true, (int)-halfX, (int)halfX, (int)minZ, (int)maxZ, blocksPerDegree, Math.abs(northLatitude), Math.abs(southLatitude)
      );
   }

   public boolean enabled() {
      return this.enabled;
   }

   /** First generated block X. */
   public int minX() {
      return this.minX;
   }

   /** First block X past the east cut. */
   public int maxXExclusive() {
      return this.maxXExclusive;
   }

   /** First generated block Z (the north cut). */
   public int minZ() {
      return this.minZ;
   }

   /** First block Z past the south cut. */
   public int maxZExclusive() {
      return this.maxZExclusive;
   }

   public boolean isPastCut(int blockX, int blockZ) {
      return this.enabled
         && (blockX < this.minX || blockX >= this.maxXExclusive || blockZ < this.minZ || blockZ >= this.maxZExclusive);
   }

   public boolean isPastCut(double blockX, double blockZ) {
      return this.enabled
         && (blockX < this.minX || blockX >= this.maxXExclusive || blockZ < this.minZ || blockZ >= this.maxZExclusive);
   }

   /** Whether the chunk lies entirely past a cut. The cuts are chunk-aligned, so a chunk is either in or out. */
   public boolean isChunkPastCut(int chunkX, int chunkZ) {
      return this.enabled && this.isPastCut(chunkX << 4, chunkZ << 4);
   }

   /** Whether the block range {@code [minBlockX, maxBlockX] × [minBlockZ, maxBlockZ]} (inclusive) lies entirely past the cut. */
   public boolean isAreaPastCut(int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ) {
      return this.enabled
         && (maxBlockX < this.minX || minBlockX >= this.maxXExclusive || maxBlockZ < this.minZ || minBlockZ >= this.maxZExclusive);
   }

   /** Whether the inclusive block range reaches past the cut on any side. */
   public boolean areaReachesPastCut(int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ) {
      return this.enabled
         && (minBlockX < this.minX || maxBlockX >= this.maxXExclusive || minBlockZ < this.minZ || maxBlockZ >= this.maxZExclusive);
   }

   /** Clamps a block X into the generated region. */
   public int clampX(int blockX) {
      return this.enabled ? Math.max(this.minX, Math.min(this.maxXExclusive - 1, blockX)) : blockX;
   }

   /** Clamps a block Z into the generated region. */
   public int clampZ(int blockZ) {
      return this.enabled ? Math.max(this.minZ, Math.min(this.maxZExclusive - 1, blockZ)) : blockZ;
   }

   /** Whether the column belongs to the barrier wall: the first column or row past a cut, corners included. */
   public boolean isBarrier(int blockX, int blockZ) {
      if (!this.isPastCut(blockX, blockZ)) {
         return false;
      }

      boolean xInWall = blockX >= this.minX - 1 && blockX <= this.maxXExclusive;
      boolean zInWall = blockZ >= this.minZ - 1 && blockZ <= this.maxZExclusive;
      return xInWall && zInWall;
   }

   /** Whether any column of the chunk belongs to the barrier wall. */
   public boolean chunkHasBarrier(int chunkX, int chunkZ) {
      if (!this.isChunkPastCut(chunkX, chunkZ)) {
         return false;
      }

      int minBlockX = chunkX << 4;
      int minBlockZ = chunkZ << 4;
      return minBlockX + 15 >= this.minX - 1
         && minBlockX <= this.maxXExclusive
         && minBlockZ + 15 >= this.minZ - 1
         && minBlockZ <= this.maxZExclusive;
   }

   /**
    * Keeps a position {@link #SAFE_MARGIN} away from every cut, for spawns, map teleports and portal destinations that
    * would otherwise put a player at the edge, about to be moved on.
    */
   public double clampAwayFromEdgeX(double blockX) {
      return this.enabled ? clamp(blockX, this.minX + SAFE_MARGIN, this.maxXExclusive - SAFE_MARGIN) : blockX;
   }

   public double clampAwayFromEdgeZ(double blockZ) {
      return this.enabled ? clamp(blockZ, this.minZ + SAFE_MARGIN, this.maxZExclusive - SAFE_MARGIN) : blockZ;
   }

   /**
    * Whether an entity at {@code (blockX, blockZ)} whose hitbox reaches {@code halfWidth} to each side is close enough
    * to a cut, or past it, for {@link #cross} to move it.
    */
   public boolean triggers(double blockX, double blockZ, double halfWidth) {
      return this.enabled
         && (blockX + halfWidth > this.maxXExclusive - TRIGGER_GAP
            || blockX - halfWidth < this.minX + TRIGGER_GAP
            || blockZ + halfWidth > this.maxZExclusive - TRIGGER_GAP
            || blockZ - halfWidth < this.minZ + TRIGGER_GAP);
   }

   /**
    * Where an entity at {@code (blockX, blockZ)} comes back in, or {@code null} when it is not at a cut.
    *
    * @param halfWidth half the width of the entity's hitbox
    * @param motionX block X travelled over the last tick
    * @param motionZ block Z travelled over the last tick
    * @param yaw Minecraft yaw in degrees (0 faces +Z / south, 90 faces -X / west); used for the heading when still
    */
   public Crossing cross(double blockX, double blockZ, double halfWidth, double motionX, double motionZ, float yaw) {
      if (!this.triggers(blockX, blockZ, halfWidth)) {
         return null;
      }

      double east;
      double south;
      if (Math.hypot(motionX, motionZ) >= STILL_SPEED) {
         east = motionX;
         south = motionZ;
      } else {
         double yawRadians = Math.toRadians(yaw);
         east = -Math.sin(yawRadians);
         south = Math.cos(yawRadians);
      }

      double landingMinX = this.minX + LANDING_GAP + halfWidth;
      double landingMaxX = this.maxXExclusive - LANDING_GAP - halfWidth;
      // The polar rule wins at the corners; its landing point is then kept clear of the east and west cuts.
      if (blockZ - halfWidth < this.minZ + TRIGGER_GAP) {
         return this.crossPole(Crossing.Kind.NORTH, blockX, halfWidth, east, -south, yaw, this.northLatitude);
      } else if (blockZ + halfWidth > this.maxZExclusive - TRIGGER_GAP) {
         return this.crossPole(Crossing.Kind.SOUTH, blockX, halfWidth, east, south, yaw, this.southLatitude);
      } else if (blockX + halfWidth > this.maxXExclusive - TRIGGER_GAP) {
         return new Crossing(Crossing.Kind.EAST, clamp(blockX - this.worldWidth(), landingMinX, landingMaxX), blockZ, yaw, false);
      } else {
         return new Crossing(Crossing.Kind.WEST, clamp(blockX + this.worldWidth(), landingMinX, landingMaxX), blockZ, yaw, false);
      }
   }

   /** Blocks between an entity's hitbox and the nearest cut ({@code Double.MAX_VALUE} when disabled). */
   public double distanceToEdge(double blockX, double blockZ, double halfWidth) {
      if (!this.enabled) {
         return Double.MAX_VALUE;
      }

      double east = this.maxXExclusive - (blockX + halfWidth);
      double west = blockX - halfWidth - this.minX;
      double south = this.maxZExclusive - (blockZ + halfWidth);
      double north = blockZ - halfWidth - this.minZ;
      return Math.min(Math.min(east, west), Math.min(south, north));
   }

   /**
    * Where an entity moving as now would come back in when it reaches the nearest cut, or {@code null} while every cut
    * is more than {@code lookahead} blocks from its hitbox or it moves toward the nearest one slower than
    * {@code minimumApproach} blocks a tick. Used to load the far side before the entity arrives. The heading comes
    * from the motion only, since over the pole the landing point swings across the map as the heading turns.
    */
   public Crossing predict(
      double blockX, double blockZ, double halfWidth, double motionX, double motionZ, double lookahead, double minimumApproach
   ) {
      if (!this.enabled) {
         return null;
      }

      double east = this.maxXExclusive - (blockX + halfWidth);
      double west = blockX - halfWidth - this.minX;
      double south = this.maxZExclusive - (blockZ + halfWidth);
      double north = blockZ - halfWidth - this.minZ;
      double nearest = Math.min(Math.min(east, west), Math.min(south, north));
      if (nearest > lookahead) {
         return null;
      }

      double x = blockX;
      double z = blockZ;
      double approach;
      if (nearest == north) {
         z = this.minZ + halfWidth;
         approach = -motionZ;
      } else if (nearest == south) {
         z = this.maxZExclusive - halfWidth;
         approach = motionZ;
      } else if (nearest == east) {
         x = this.maxXExclusive - halfWidth;
         approach = motionX;
      } else {
         x = this.minX + halfWidth;
         approach = -motionX;
      }
      if (!(approach >= minimumApproach) || Math.hypot(motionX, motionZ) < STILL_SPEED) {
         return null;
      }
      return this.cross(x, z, halfWidth, motionX, motionZ, 0.0F);
   }

   /** Blocks in 360° of longitude. */
   double worldWidth() {
      return 360.0 * this.blocksPerDegree;
   }

   private Crossing crossPole(
      Crossing.Kind kind, double blockX, double halfWidth, double east, double towardPole, float yaw, double edgeLatitude
   ) {
      // Heading from the pole direction toward east; an entity moving away from the pole goes straight over it.
      double heading = towardPole > 0.0 ? Math.atan2(east, towardPole) : 0.0;
      double longitude = blockX / this.blocksPerDegree;
      double exitLongitude = normalizeLongitude(longitude + Math.toDegrees(longitudeAcrossCap(edgeLatitude, heading)));
      double landingX = clamp(
         exitLongitude * this.blocksPerDegree, this.minX + LANDING_GAP + halfWidth, this.maxXExclusive - LANDING_GAP - halfWidth
      );
      double landingZ = kind == Crossing.Kind.NORTH
         ? this.minZ + LANDING_GAP + halfWidth
         : this.maxZExclusive - LANDING_GAP - halfWidth;
      return new Crossing(kind, landingX, landingZ, mirrorYaw(yaw), true);
   }

   /**
    * Longitude, in radians, a great circle gains between entering and leaving the polar cap bounded by
    * {@code edgeLatitude} (degrees), entering with {@code heading} radians from the pole direction toward east.
    * The exit heading is the mirror of the entry heading: east stays east, the pole-ward component reverses.
    */
   static double longitudeAcrossCap(double edgeLatitude, double heading) {
      double sinLatitude = Math.sin(Math.toRadians(Math.abs(edgeLatitude)));
      return 2.0 * Math.atan2(Math.cos(heading), sinLatitude * Math.sin(heading));
   }

   /** The yaw after a polar crossing: the heading's north/south component reverses. */
   public static float mirrorYaw(float yaw) {
      return wrapDegrees(180.0F - yaw);
   }

   static double normalizeLongitude(double longitude) {
      double wrapped = (longitude + 180.0) % 360.0;
      if (wrapped < 0.0) {
         wrapped += 360.0;
      }

      return wrapped - 180.0;
   }

   private static float wrapDegrees(float degrees) {
      float wrapped = degrees % 360.0F;
      if (wrapped >= 180.0F) {
         wrapped -= 360.0F;
      }

      if (wrapped < -180.0F) {
         wrapped += 360.0F;
      }

      return wrapped;
   }

   private static double clamp(double value, double min, double max) {
      return Math.max(min, Math.min(max, value));
   }

   /**
    * A crossing's landing point.
    *
    * @param mirror whether the north/south axis is reflected (polar crossings): the yaw is already mirrored, and the
    *     Z component of the velocity must be negated
    */
   public record Crossing(Crossing.Kind kind, double x, double z, float yaw, boolean mirror) {
      public enum Kind {
         EAST,
         WEST,
         NORTH,
         SOUTH
      }
   }
}
