package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.worldgen.caves.TellusCaveBiomeDepthPolicy;
import java.util.function.IntPredicate;

/**
 * Where a chunk's sandy ocean shore takes the Beach biome, the only biome turtles spawn in. The shore is
 * the sand Tellus already lays where the real coast is sand, starting at the ocean's edge and following
 * that sand inland inside the chunk, so lake shores and inland dunes keep their biomes.
 *
 * <p>Columns are indexed row by row, {@code localZ * 16 + localX}; quart columns likewise over the
 * chunk's 4x4 biome quarts.</p>
 */
public final class SandyShorePolicy {
   static final int CHUNK_SIDE = 16;
   static final int CHUNK_AREA = CHUNK_SIDE * CHUNK_SIDE;
   static final int QUART_SIDE = 4;
   /** A quarter of a quart column's ground must be shore before the quart is stored as one. */
   static final int MIN_SHORE_COLUMNS_PER_QUART = 4;
   public static final int NO_SHORE = Integer.MAX_VALUE;

   private SandyShorePolicy() {
   }

   /** Dry sand at the ocean's edge, grown through the dry sand it touches inside the chunk. */
   public static boolean[] shoreColumns(boolean[] oceanEdge, IntPredicate drySand) {
      boolean[] shore = new boolean[CHUNK_AREA];
      // 0 unknown, 1 dry sand, 2 not: each column's sand is looked up at most once.
      byte[] sand = new byte[CHUNK_AREA];
      int[] queue = new int[CHUNK_AREA];
      int tail = 0;
      for (int index = 0; index < CHUNK_AREA; index++) {
         if (oceanEdge[index] && isDrySand(index, sand, drySand)) {
            shore[index] = true;
            queue[tail++] = index;
         }
      }

      for (int head = 0; head < tail; head++) {
         int index = queue[head];
         int x = index % CHUNK_SIDE;
         int z = index / CHUNK_SIDE;
         for (int direction = 0; direction < 4; direction++) {
            int neighborX = x + (direction == 0 ? 1 : direction == 1 ? -1 : 0);
            int neighborZ = z + (direction == 2 ? 1 : direction == 3 ? -1 : 0);
            if (neighborX < 0 || neighborX >= CHUNK_SIDE || neighborZ < 0 || neighborZ >= CHUNK_SIDE) {
               continue;
            }
            int neighbor = neighborZ * CHUNK_SIDE + neighborX;
            if (!shore[neighbor] && isDrySand(neighbor, sand, drySand)) {
               shore[neighbor] = true;
               queue[tail++] = neighbor;
            }
         }
      }
      return shore;
   }

   /**
    * For each quart column, the lowest block Y from which it stores the shore biome, or {@link #NO_SHORE}.
    * The floor stays above cave biome depth under the quart's highest ground, so caves below a beach keep
    * their biomes, while everything above, up to the top of the world where chunk generation looks up the
    * biome it spawns animals for, becomes shore.
    */
   public static int[] quartFloors(boolean[] shore, int[] terrainSurfaces) {
      int[] floors = new int[QUART_SIDE * QUART_SIDE];
      for (int quartZ = 0; quartZ < QUART_SIDE; quartZ++) {
         for (int quartX = 0; quartX < QUART_SIDE; quartX++) {
            int shoreColumns = 0;
            int highest = Integer.MIN_VALUE;
            for (int dz = 0; dz < QUART_SIDE; dz++) {
               for (int dx = 0; dx < QUART_SIDE; dx++) {
                  int index = (quartZ * QUART_SIDE + dz) * CHUNK_SIDE + quartX * QUART_SIDE + dx;
                  shoreColumns += shore[index] ? 1 : 0;
                  highest = Math.max(highest, terrainSurfaces[index]);
               }
            }
            floors[quartZ * QUART_SIDE + quartX] = shoreColumns >= MIN_SHORE_COLUMNS_PER_QUART
               ? highest - TellusCaveBiomeDepthPolicy.MIN_CAVE_BIOME_DEPTH + 1
               : NO_SHORE;
         }
      }
      return floors;
   }

   private static boolean isDrySand(int index, byte[] sand, IntPredicate drySand) {
      if (sand[index] == 0) {
         sand[index] = drySand.test(index) ? (byte)1 : (byte)2;
      }
      return sand[index] == 1;
   }
}
