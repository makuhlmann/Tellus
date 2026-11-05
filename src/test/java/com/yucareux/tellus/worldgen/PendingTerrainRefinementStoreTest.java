package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingTerrainRefinementStoreTest {
   @TempDir
   Path worldRoot;

   @Test
   void recordsSurviveReopeningTheStore() {
      Path directory = PendingTerrainRefinementStore.directoryFor(this.worldRoot, "minecraft:overworld");
      PendingTerrainRefinementStore store = PendingTerrainRefinementStore.open(directory);
      PendingTerrainRefinementStore.Record record = sampleRecord(-12, 345);
      store.write(record);

      PendingTerrainRefinementStore reopened = PendingTerrainRefinementStore.open(directory);
      long chunkKey = MinecraftVersionCompat.packChunkPos(-12, 345);
      assertTrue(reopened.contains(chunkKey));
      PendingTerrainRefinementStore.Record read = reopened.read(-12, 345);
      assertNotNull(read);
      assertEquals(record.minY(), read.minY());
      assertEquals(record.maxY(), read.maxY());
      assertArrayEquals(record.terrainSurfaces(), read.terrainSurfaces());
      assertArrayEquals(record.waterSurfaces(), read.waterSurfaces());
      assertArrayEquals(record.waterFlags(), read.waterFlags());
      assertArrayEquals(record.oceanFlags(), read.oceanFlags());

      reopened.discard(chunkKey);
      assertFalse(reopened.contains(chunkKey));
      assertTrue(PendingTerrainRefinementStore.open(directory).isEmpty());
   }

   @Test
   void deletesUnreadableRecords() throws Exception {
      Path directory = PendingTerrainRefinementStore.directoryFor(this.worldRoot, "minecraft:overworld");
      Files.createDirectories(directory);
      Path corrupt = directory.resolve("3.4.shell");
      Files.write(corrupt, new byte[]{1, 2, 3});
      // A record whose content names another chunk than its file is not trusted either.
      Files.write(directory.resolve("5.6.shell"), PendingTerrainRefinementStore.encode(sampleRecord(7, 8)));

      PendingTerrainRefinementStore store = PendingTerrainRefinementStore.open(directory);
      assertNull(store.read(3, 4));
      assertFalse(Files.exists(corrupt));
      assertNull(store.read(5, 6));
      assertTrue(store.isEmpty());
   }

   @Test
   void keepsDimensionsApartInFolderSafeNames() {
      Path root = this.worldRoot.resolve(PendingTerrainRefinementStore.RELATIVE_PATH);
      assertEquals(root.resolve("minecraft").resolve("overworld"), PendingTerrainRefinementStore.directoryFor(this.worldRoot, "minecraft:overworld"));
      assertEquals(root.resolve("tellus").resolve("earth").resolve("moon"), PendingTerrainRefinementStore.directoryFor(this.worldRoot, "tellus:earth/moon"));
      Path unsafe = PendingTerrainRefinementStore.directoryFor(this.worldRoot, "tellus:../outside");
      assertEquals(root, unsafe.getParent());
      assertTrue(unsafe.getFileName().toString().startsWith("id-"));
   }

   @Test
   void rejectsRecordsOfTheWrongShape() {
      assertThrows(
         IllegalArgumentException.class,
         () -> new PendingTerrainRefinementStore.Record(0, 0, 64, 64, new int[256], new int[256], new boolean[256], new boolean[256])
      );
      assertThrows(
         IllegalArgumentException.class,
         () -> new PendingTerrainRefinementStore.Record(0, 0, -64, 319, new int[255], new int[256], new boolean[256], new boolean[256])
      );
   }

   private static PendingTerrainRefinementStore.Record sampleRecord(int chunkX, int chunkZ) {
      int[] terrain = new int[256];
      int[] water = new int[256];
      boolean[] waterFlags = new boolean[256];
      boolean[] oceanFlags = new boolean[256];
      for (int index = 0; index < 256; index++) {
         terrain[index] = -40 + index * 3;
         water[index] = 62 + index % 5;
         waterFlags[index] = index % 3 == 0;
         oceanFlags[index] = index % 7 == 0;
      }
      return new PendingTerrainRefinementStore.Record(chunkX, chunkZ, -64, 319, terrain, water, waterFlags, oceanFlags);
   }
}
