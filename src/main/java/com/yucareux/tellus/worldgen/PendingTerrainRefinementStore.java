package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.compat.MinecraftVersionCompat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.world.level.ChunkPos;

/**
 * Keeps the shell of each chunk still waiting for its terrain refinement on disk.
 *
 * <p>A chunk built while elevation or coastline data was unavailable is saved as a shell and refined once the
 * data arrives. Refinements only run while the server does, so without these records a shell whose chunk
 * unloaded before its refinement, or whose data was still missing when the server stopped, stayed in the world
 * for good. One small file per chunk lets the next session refine the chunk once it is loaded again.</p>
 */
final class PendingTerrainRefinementStore {
   static final String RELATIVE_PATH = "tellus/pending-terrain-refinements";
   private static final int MAGIC = 0x544C5246;
   private static final int FORMAT_VERSION = 1;
   private static final int COLUMNS = 16 * 16;
   private static final long MAX_RECORD_BYTES = 64L * 1024L;
   private static final Pattern FILE_NAME = Pattern.compile("(-?\\d+)\\.(-?\\d+)\\.shell");
   private static final Pattern PATH_SEGMENT = Pattern.compile("[a-z0-9_.-]+");
   private final Path directory;
   private final Set<Long> chunkKeys = ConcurrentHashMap.newKeySet();

   private PendingTerrainRefinementStore(Path directory) {
      this.directory = Objects.requireNonNull(directory, "directory");
   }

   /** Opens the records of one dimension, listing the chunks that still wait for a refinement. */
   static PendingTerrainRefinementStore open(Path directory) {
      PendingTerrainRefinementStore store = new PendingTerrainRefinementStore(directory);
      if (!Files.isDirectory(directory)) {
         return store;
      }

      try (Stream<Path> files = Files.list(directory)) {
         files.forEach(file -> {
            Matcher matcher = FILE_NAME.matcher(file.getFileName().toString());
            if (matcher.matches()) {
               try {
                  store.chunkKeys.add(
                     MinecraftVersionCompat.packChunkPos(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)))
                  );
               } catch (NumberFormatException ignored) {
                  // Coordinates beyond int range: not a record this store wrote.
               }
            }
         });
      } catch (IOException error) {
         Tellus.LOGGER.warn("Failed to list pending terrain refinements in {}", directory, error);
      }

      if (!store.chunkKeys.isEmpty()) {
         Tellus.LOGGER.info("{} chunks still wait for a terrain refinement; they are refined when next loaded", store.chunkKeys.size());
      }
      return store;
   }

   /** The folder holding one dimension's records; namespace and path become separate folders. */
   static Path directoryFor(Path worldRoot, String dimensionId) {
      Path root = Objects.requireNonNull(worldRoot, "worldRoot").resolve(RELATIVE_PATH);
      String id = Objects.requireNonNull(dimensionId, "dimensionId").toLowerCase(Locale.ROOT);
      int colon = id.indexOf(':');
      List<String> segments = new ArrayList<>();
      segments.add(colon < 0 ? "minecraft" : id.substring(0, colon));
      segments.addAll(List.of((colon < 0 ? id : id.substring(colon + 1)).split("/", -1)));
      for (String segment : segments) {
         if (!PATH_SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
            // Not a folder-safe identifier: name the folder after it instead.
            return root.resolve("id-" + HexFormat.of().formatHex(id.getBytes(StandardCharsets.UTF_8)));
         }
      }

      Path directory = root;
      for (String segment : segments) {
         directory = directory.resolve(segment);
      }
      return directory;
   }

   Path directory() {
      return this.directory;
   }

   boolean isEmpty() {
      return this.chunkKeys.isEmpty();
   }

   boolean contains(long chunkKey) {
      return this.chunkKeys.contains(chunkKey);
   }

   /** The chunks with a record, as they are now; records added or removed meanwhile may be missing or remain. */
   Set<Long> chunkKeys() {
      return this.chunkKeys;
   }

   void write(PendingTerrainRefinementStore.Record record) {
      long chunkKey = MinecraftVersionCompat.packChunkPos(record.chunkX(), record.chunkZ());
      try {
         TellusCacheFiles.writeBytes(this.pathFor(record.chunkX(), record.chunkZ()), encode(record));
         this.chunkKeys.add(chunkKey);
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.warn("Failed to record the pending terrain refinement of chunk {},{}", record.chunkX(), record.chunkZ(), error);
      }
   }

   /** The chunk's record, or null when it has none or it cannot be read; an unreadable record is deleted. */
   PendingTerrainRefinementStore.Record read(int chunkX, int chunkZ) {
      Path path = this.pathFor(chunkX, chunkZ);
      try {
         if (!Files.isRegularFile(path)) {
            this.chunkKeys.remove(MinecraftVersionCompat.packChunkPos(chunkX, chunkZ));
            return null;
         }
         if (Files.size(path) > MAX_RECORD_BYTES) {
            throw new IOException("Pending terrain refinement record exceeds " + MAX_RECORD_BYTES + " bytes");
         }

         PendingTerrainRefinementStore.Record record = decode(Files.readAllBytes(path));
         if (record.chunkX() != chunkX || record.chunkZ() != chunkZ) {
            throw new IOException("Pending terrain refinement record holds chunk " + record.chunkX() + "," + record.chunkZ());
         }
         return record;
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.warn("Discarding unreadable pending terrain refinement of chunk {},{}", chunkX, chunkZ, error);
         this.delete(chunkX, chunkZ);
         return null;
      }
   }

   /** Deletes the chunk's record, if it has one; cheap for chunks without one, so it runs for every generated chunk. */
   void discard(long chunkKey) {
      if (this.chunkKeys.contains(chunkKey)) {
         this.delete(ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
      }
   }

   private void delete(int chunkX, int chunkZ) {
      this.chunkKeys.remove(MinecraftVersionCompat.packChunkPos(chunkX, chunkZ));
      Path path = this.pathFor(chunkX, chunkZ);
      try {
         Files.deleteIfExists(path);
      } catch (IOException error) {
         Tellus.LOGGER.warn("Failed to delete the pending terrain refinement {}", path, error);
      }
   }

   private Path pathFor(int chunkX, int chunkZ) {
      return this.directory.resolve(chunkX + "." + chunkZ + ".shell");
   }

   static byte[] encode(PendingTerrainRefinementStore.Record record) throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
      try (DataOutputStream output = new DataOutputStream(new GZIPOutputStream(bytes))) {
         output.writeInt(MAGIC);
         output.writeInt(FORMAT_VERSION);
         output.writeInt(record.chunkX());
         output.writeInt(record.chunkZ());
         output.writeInt(record.minY());
         output.writeInt(record.maxY());
         for (int index = 0; index < COLUMNS; index++) {
            output.writeInt(record.terrainSurfaces()[index]);
            output.writeInt(record.waterSurfaces()[index]);
            output.writeByte((record.waterFlags()[index] ? 1 : 0) | (record.oceanFlags()[index] ? 2 : 0));
         }
      }
      return bytes.toByteArray();
   }

   static PendingTerrainRefinementStore.Record decode(byte[] data) throws IOException {
      try (DataInputStream input = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
         if (input.readInt() != MAGIC) {
            throw new IOException("Not a pending terrain refinement record");
         }
         int version = input.readInt();
         if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported pending terrain refinement format " + version);
         }

         int chunkX = input.readInt();
         int chunkZ = input.readInt();
         int minY = input.readInt();
         int maxY = input.readInt();
         int[] terrainSurfaces = new int[COLUMNS];
         int[] waterSurfaces = new int[COLUMNS];
         boolean[] waterFlags = new boolean[COLUMNS];
         boolean[] oceanFlags = new boolean[COLUMNS];
         for (int index = 0; index < COLUMNS; index++) {
            terrainSurfaces[index] = input.readInt();
            waterSurfaces[index] = input.readInt();
            int flags = input.readUnsignedByte();
            waterFlags[index] = (flags & 1) != 0;
            oceanFlags[index] = (flags & 2) != 0;
         }
         if (input.read() != -1) {
            throw new IOException("Trailing bytes after the pending terrain refinement record");
         }
         return new PendingTerrainRefinementStore.Record(chunkX, chunkZ, minY, maxY, terrainSurfaces, waterSurfaces, waterFlags, oceanFlags);
      }
   }

   /** The parts of a shell its refinement compares against: what the shell placed in each column. */
   record Record(
      int chunkX, int chunkZ, int minY, int maxY, int[] terrainSurfaces, int[] waterSurfaces, boolean[] waterFlags, boolean[] oceanFlags
   ) {
      Record {
         if (minY >= maxY) {
            throw new IllegalArgumentException("Pending terrain refinement spans no height: " + minY + ".." + maxY);
         }
         requireColumns(terrainSurfaces.length, "terrainSurfaces");
         requireColumns(waterSurfaces.length, "waterSurfaces");
         requireColumns(waterFlags.length, "waterFlags");
         requireColumns(oceanFlags.length, "oceanFlags");
      }

      private static void requireColumns(int length, String name) {
         if (length != COLUMNS) {
            throw new IllegalArgumentException(name + " holds " + length + " columns, not " + COLUMNS);
         }
      }
   }
}
