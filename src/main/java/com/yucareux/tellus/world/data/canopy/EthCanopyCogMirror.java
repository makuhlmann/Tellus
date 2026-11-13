package com.yucareux.tellus.world.data.canopy;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Builds canopy tiles in the ArcGIS Living Atlas tile grid from a copy of ETH's own Global Canopy Height 2020
 * files: 3-by-3 degree cloud-optimized GeoTIFFs named {@code ETH_GlobalCanopyHeight_10m_2020_<square>_Map.tif},
 * served by any web server with range requests.
 *
 * <p>The service's native level shares the files' pixel grid (1/12000 degree, from 180 W and 84 N), and each
 * coarser level halves it as the files' overviews do, down to the 32-times overview; coarser levels average that
 * overview's pixels under each of theirs. A file the server does not have reads as no data.</p>
 */
final class EthCanopyCogMirror {
   static final int TILE_SIZE = 256;
   static final int NATIVE_LEVEL = 13;
   static final int NO_DATA = 255;
   private static final String FILE_PATTERN = "ETH_GlobalCanopyHeight_10m_2020_%s_Map.tif";
   private static final int FILE_PIXELS = 36_000;
   private static final int FILE_DEGREES = 3;
   private static final int ORIGIN_LONGITUDE = -180;
   private static final int ORIGIN_LATITUDE = 84;
   private static final int MIN_LATITUDE = -60;
   private static final long NATIVE_ROWS = (long)(ORIGIN_LATITUDE - MIN_LATITUDE) / FILE_DEGREES * FILE_PIXELS;
   private static final long NATIVE_COLUMNS = 360L / FILE_DEGREES * FILE_PIXELS;
   // The files hold overviews down to 1/64, but 36000 / 64 is not whole; 1/32 is the last on the service's grid.
   private static final int MAX_OVERVIEW_FACTOR = 32;
   // Coarse levels average at most this many pixels per side, spread evenly, to bound the work per tile.
   private static final int MAX_AVERAGED_SIDE = 8;
   private static final int BOOTSTRAP_BYTES = 64 * 1024;
   private static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;
   private static final int MAX_BLOCK_BYTES = 8 * 1024 * 1024;
   private static final int MAX_BLOCK_PIXELS = 4096 * 4096;
   private static final int COMPRESSION_NONE = 1;
   private static final int COMPRESSION_LZW = 5;
   private static final int COMPRESSION_DEFLATE = 8;
   private static final int COMPRESSION_ADOBE_DEFLATE = 32946;

   private final String folderUrl;
   private final int connectTimeoutMs;
   private final int readTimeoutMs;
   private final LoadingCache<String, CogFile> files;
   private final LoadingCache<BlockKey, byte[]> blocks;

   EthCanopyCogMirror(String folderUrl, int connectTimeoutMs, int readTimeoutMs, long blockCacheBytes) {
      String folder = Objects.requireNonNull(folderUrl, "folderUrl");
      this.folderUrl = folder.endsWith("/") ? folder : folder + "/";
      this.connectTimeoutMs = connectTimeoutMs;
      this.readTimeoutMs = readTimeoutMs;
      this.files = CacheBuilder.newBuilder().maximumSize(512).build(new CacheLoader<>() {
         @Override
         public CogFile load(String square) throws IOException {
            return EthCanopyCogMirror.this.readFile(square);
         }
      });
      this.blocks = CacheBuilder.newBuilder()
         .maximumWeight(Math.max(1L, blockCacheBytes))
         .weigher((BlockKey key, byte[] pixels) -> pixels.length)
         .build(new CacheLoader<>() {
            @Override
            public byte[] load(BlockKey key) throws IOException {
               return EthCanopyCogMirror.this.readBlock(key);
            }
         });
   }

   /** A tile's pixels, row by row, in metres with {@link #NO_DATA} where there are none. */
   record Tile(byte[] pixels, boolean complete) {
   }

   /**
    * Tile {@code row}/{@code column} of {@code level} in the service's grid. It is incomplete when a file it
    * needs is not on the server, which may only mean the file is not copied yet.
    */
   Tile tile(int level, int row, int column) throws IOException {
      if (level < 0 || level > NATIVE_LEVEL) {
         throw new IllegalArgumentException("Canopy level " + level + " is outside 0-" + NATIVE_LEVEL);
      }
      long factor = 1L << (NATIVE_LEVEL - level);
      // Up to 1/32 a level is one of the files' overviews, pixel for pixel; coarser levels average the 1/32
      // overview's pixels under each of theirs, as the service's own coarse levels average.
      int overview = Long.numberOfTrailingZeros(Math.min(factor, MAX_OVERVIEW_FACTOR));
      int window = (int)Math.max(1L, factor / MAX_OVERVIEW_FACTOR);
      int step = Math.max(1, window / MAX_AVERAGED_SIDE);
      Reader reader = new Reader(overview);
      byte[] pixels = new byte[TILE_SIZE * TILE_SIZE];
      for (int y = 0; y < TILE_SIZE; y++) {
         long overviewRow = ((long)row * TILE_SIZE + y) * window;
         for (int x = 0; x < TILE_SIZE; x++) {
            long overviewColumn = ((long)column * TILE_SIZE + x) * window;
            int value;
            if (window == 1) {
               value = reader.pixel(overviewRow, overviewColumn);
            } else {
               long sum = 0L;
               int count = 0;
               for (int dy = step / 2; dy < window; dy += step) {
                  for (int dx = step / 2; dx < window; dx += step) {
                     int sample = reader.pixel(overviewRow + dy, overviewColumn + dx);
                     if (sample != NO_DATA) {
                        sum += sample;
                        count++;
                     }
                  }
               }
               value = count == 0 ? NO_DATA : (int)((sum * 2L + count) / (count * 2L));
            }
            pixels[y * TILE_SIZE + x] = (byte)value;
         }
      }
      return new Tile(pixels, reader.complete);
   }

   /** Reads pixels of one overview level across files, remembering the last file and block it used. */
   private final class Reader {
      private final int overview;
      private final int filePixels;
      private final long rows;
      private final long columns;
      private boolean complete = true;
      private String lastSquare;
      private CogFile lastFile;
      private BlockKey lastBlockKey;
      private byte[] lastBlock;

      private Reader(int overview) {
         this.overview = overview;
         this.filePixels = FILE_PIXELS >> overview;
         this.rows = NATIVE_ROWS >> overview;
         this.columns = NATIVE_COLUMNS >> overview;
      }

      /** The pixel at a global position in this overview's grid, or {@link #NO_DATA}. */
      private int pixel(long row, long column) throws IOException {
         if (row < 0L || column < 0L || row >= this.rows || column >= this.columns) {
            return NO_DATA;
         }
         int fileRow = (int)(row / this.filePixels);
         int fileColumn = (int)(column / this.filePixels);
         String square = squareName(fileRow, fileColumn);
         if (!square.equals(this.lastSquare)) {
            this.lastSquare = square;
            this.lastFile = EthCanopyCogMirror.this.file(square);
         }
         if (this.lastFile == null) {
            this.complete = false;
            return NO_DATA;
         }
         Overview image = this.lastFile.overview(this.overview);
         int localX = (int)(column - (long)fileColumn * this.filePixels);
         int localY = (int)(row - (long)fileRow * this.filePixels);
         if (localX >= image.width() || localY >= image.height()) {
            return NO_DATA;
         }
         int blockColumn = localX / image.tileWidth();
         int blockRow = localY / image.tileHeight();
         BlockKey blockKey = new BlockKey(square, this.overview, blockRow, blockColumn);
         if (!blockKey.equals(this.lastBlockKey)) {
            this.lastBlockKey = blockKey;
            this.lastBlock = EthCanopyCogMirror.this.block(blockKey);
         }
         int localIndex = (localY - blockRow * image.tileHeight()) * image.tileWidth() + localX - blockColumn * image.tileWidth();
         return this.lastBlock[localIndex] & 0xFF;
      }
   }

   /** The file name's square: its south-west corner, as in {@code N45E006}. */
   static String squareName(int fileRow, int fileColumn) {
      int south = ORIGIN_LATITUDE - (fileRow + 1) * FILE_DEGREES;
      int west = ORIGIN_LONGITUDE + fileColumn * FILE_DEGREES;
      return String.format(Locale.ROOT, "%s%02d%s%03d", south >= 0 ? "N" : "S", Math.abs(south), west >= 0 ? "E" : "W", Math.abs(west));
   }

   private CogFile file(String square) throws IOException {
      CogFile file = this.get(this.files, square);
      return file.absent() ? null : file;
   }

   private byte[] block(BlockKey key) throws IOException {
      return this.get(this.blocks, key);
   }

   private <K, V> V get(LoadingCache<K, V> cache, K key) throws IOException {
      try {
         return cache.get(key);
      } catch (ExecutionException | UncheckedExecutionException error) {
         if (error.getCause() instanceof IOException io) {
            throw io;
         }
         throw new IOException("Failed to read ETH canopy data for " + key, error.getCause());
      }
   }

   private String fileUrl(String square) {
      return this.folderUrl + String.format(Locale.ROOT, FILE_PATTERN, square);
   }

   private CogFile readFile(String square) throws IOException {
      String url = this.fileUrl(square);
      byte[] bootstrap;
      try {
         bootstrap = this.readRange(url, 0L, BOOTSTRAP_BYTES, true);
      } catch (FileMissingException missing) {
         return CogFile.missing(square);
      }
      return CogFile.parse(square, bootstrap, (offset, length) -> this.readRange(url, offset, length, false));
   }

   private byte[] readBlock(BlockKey key) throws IOException {
      CogFile file = Objects.requireNonNull(this.file(key.square()), "file");
      Overview image = file.overview(key.overview());
      int index = key.blockRow() * image.blocksAcross() + key.blockColumn();
      long offset = image.offsets()[index];
      long length = image.byteCounts()[index];
      int pixels = image.tileWidth() * image.tileHeight();
      if (length <= 0L) {
         byte[] empty = new byte[pixels];
         Arrays.fill(empty, (byte)NO_DATA);
         return empty;
      }
      if (length > MAX_BLOCK_BYTES) {
         throw new IOException("ETH canopy block is larger than expected: " + length + " bytes");
      }
      byte[] compressed = this.readRange(this.fileUrl(key.square()), offset, (int)length, false);
      byte[] decoded = decompress(compressed, image.compression(), pixels);
      if (image.predictor() == 2) {
         undoHorizontalDifferencing(decoded, image.tileWidth());
      }
      return decoded;
   }

   static byte[] decompress(byte[] data, int compression, int expectedBytes) throws IOException {
      byte[] output = switch (compression) {
         case COMPRESSION_NONE -> data;
         case COMPRESSION_LZW -> decodeLzw(data, expectedBytes);
         case COMPRESSION_DEFLATE, COMPRESSION_ADOBE_DEFLATE -> inflate(data, expectedBytes);
         default -> throw new IOException("Unsupported ETH canopy compression " + compression);
      };
      if (output.length < expectedBytes) {
         throw new IOException("ETH canopy block decoded to " + output.length + " of " + expectedBytes + " bytes");
      }
      return output.length == expectedBytes ? output : Arrays.copyOf(output, expectedBytes);
   }

   /** TIFF's LZW: codes of 9 to 12 bits, most significant bit first, widening one code early. */
   static byte[] decodeLzw(byte[] data, int expectedBytes) throws IOException {
      final int clear = 256;
      final int end = 257;
      byte[][] table = new byte[4096][];
      for (int code = 0; code < 256; code++) {
         table[code] = new byte[]{(byte)code};
      }
      byte[] output = new byte[expectedBytes];
      int written = 0;
      int next = 258;
      int width = 9;
      long bitBuffer = 0L;
      int bitCount = 0;
      int position = 0;
      byte[] previous = null;
      while (written < expectedBytes) {
         while (bitCount < width) {
            if (position >= data.length) {
               throw new EOFException("ETH canopy LZW data ended after " + written + " of " + expectedBytes + " bytes");
            }
            bitBuffer = (bitBuffer << 8) | (data[position++] & 0xFF);
            bitCount += 8;
         }
         int code = (int)((bitBuffer >>> (bitCount - width)) & ((1 << width) - 1));
         bitCount -= width;
         if (code == end) {
            break;
         }
         if (code == clear) {
            next = 258;
            width = 9;
            previous = null;
            continue;
         }

         byte[] entry;
         if (code < next && table[code] != null) {
            entry = table[code];
         } else if (code == next && previous != null) {
            entry = Arrays.copyOf(previous, previous.length + 1);
            entry[previous.length] = previous[0];
         } else {
            throw new IOException("Corrupt ETH canopy LZW code " + code);
         }
         int count = Math.min(entry.length, expectedBytes - written);
         System.arraycopy(entry, 0, output, written, count);
         written += count;

         if (previous != null && next < table.length) {
            byte[] added = Arrays.copyOf(previous, previous.length + 1);
            added[previous.length] = entry[0];
            table[next++] = added;
         }
         previous = entry;
         if (next + 1 >= (1 << width) && width < 12) {
            width++;
         }
      }
      return written == expectedBytes ? output : Arrays.copyOf(output, written);
   }

   private static byte[] inflate(byte[] data, int expectedBytes) throws IOException {
      Inflater inflater = new Inflater();
      try {
         inflater.setInput(data);
         byte[] output = new byte[expectedBytes];
         int written = 0;
         while (written < expectedBytes && !inflater.finished()) {
            int count = inflater.inflate(output, written, expectedBytes - written);
            if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
               break;
            }
            written += count;
         }
         return written == expectedBytes ? output : Arrays.copyOf(output, written);
      } catch (DataFormatException error) {
         throw new IOException("Corrupt ETH canopy deflate block", error);
      } finally {
         inflater.end();
      }
   }

   /** Predictor 2 for single bytes: each stored pixel is the difference from the one to its left. */
   static void undoHorizontalDifferencing(byte[] pixels, int rowWidth) {
      for (int rowStart = 0; rowStart + rowWidth <= pixels.length; rowStart += rowWidth) {
         for (int index = rowStart + 1; index < rowStart + rowWidth; index++) {
            pixels[index] = (byte)(pixels[index] + pixels[index - 1]);
         }
      }
   }

   private byte[] readRange(String url, long offset, int length, boolean missingAllowed) throws IOException {
      long endInclusive = Math.addExact(offset, length - 1L);
      HttpURLConnection connection = (HttpURLConnection)URI.create(url).toURL().openConnection();
      connection.setRequestProperty("Range", "bytes=" + offset + "-" + endInclusive);
      connection.setRequestProperty("User-Agent", "Tellus/1.0 (Minecraft Mod)");
      connection.setConnectTimeout(this.connectTimeoutMs);
      connection.setReadTimeout(this.readTimeoutMs);
      boolean reusable = false;
      try {
         int status = connection.getResponseCode();
         if (status == HttpURLConnection.HTTP_NOT_FOUND && missingAllowed) {
            throw new FileMissingException(url);
         }
         if (status != HttpURLConnection.HTTP_PARTIAL && !(status == HttpURLConnection.HTTP_OK && offset == 0L)) {
            throw new IOException("ETH canopy mirror answered HTTP " + status + " for " + url);
         }
         try (InputStream input = connection.getInputStream()) {
            // A short file answers a bootstrap range with fewer bytes; anything else must be complete.
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length && !(offset == 0L && missingAllowed)) {
               throw new EOFException("ETH canopy mirror sent " + bytes.length + " of " + length + " bytes from " + url);
            }
            reusable = status == HttpURLConnection.HTTP_PARTIAL && bytes.length == length;
            return bytes;
         }
      } finally {
         if (!reusable) {
            connection.disconnect();
         }
      }
   }

   private static final class FileMissingException extends IOException {
      private FileMissingException(String url) {
         super("ETH canopy file is not on the mirror: " + url);
      }
   }

   @FunctionalInterface
   private interface RangeReader {
      byte[] read(long offset, int length) throws IOException;
   }

   private record BlockKey(String square, int overview, int blockRow, int blockColumn) {
   }

   private record Overview(
      int width, int height, int tileWidth, int tileHeight, int compression, int predictor, long[] offsets, long[] byteCounts
   ) {
      int blocksAcross() {
         return (this.width + this.tileWidth - 1) / this.tileWidth;
      }
   }

   /** One file's full-resolution image and its overviews, finest first; or a file the mirror does not have. */
   private record CogFile(String square, Overview[] overviews, boolean absent) {
      static CogFile missing(String square) {
         return new CogFile(square, new Overview[0], true);
      }

      Overview overview(int index) throws IOException {
         if (index >= this.overviews.length) {
            throw new IOException("ETH canopy file " + this.square + " has no overview " + index);
         }
         return this.overviews[index];
      }

      static CogFile parse(String square, byte[] bootstrap, RangeReader reader) throws IOException {
         if (bootstrap.length < 16) {
            throw new IOException("ETH canopy file " + square + " is too short");
         }
         ByteOrder order;
         if (bootstrap[0] == 'I' && bootstrap[1] == 'I') {
            order = ByteOrder.LITTLE_ENDIAN;
         } else if (bootstrap[0] == 'M' && bootstrap[1] == 'M') {
            order = ByteOrder.BIG_ENDIAN;
         } else {
            throw new IOException("ETH canopy file " + square + " is not a TIFF");
         }
         Bytes bytes = new Bytes(bootstrap, order, reader);
         int magic = bytes.u16(2);
         boolean big = magic == 43;
         if (magic != 42 && !big) {
            throw new IOException("ETH canopy file " + square + " has an unknown TIFF version " + magic);
         }
         long ifdOffset = big ? bytes.u64(8) : bytes.u32(4);
         java.util.List<Overview> images = new java.util.ArrayList<>();
         while (ifdOffset != 0L && images.size() < 16) {
            long count = big ? bytes.u64(ifdOffset) : bytes.u16(ifdOffset);
            long entries = ifdOffset + (big ? 8 : 2);
            int entrySize = big ? 20 : 12;
            java.util.Map<Integer, long[]> tags = new java.util.HashMap<>();
            for (long index = 0; index < count && index < 512; index++) {
               long entry = entries + index * entrySize;
               int tag = bytes.u16(entry);
               int type = bytes.u16(entry + 2);
               long values = big ? bytes.u64(entry + 4) : bytes.u32(entry + 4);
               int typeSize = switch (type) {
                  case 1, 2, 6, 7 -> 1;
                  case 3, 8 -> 2;
                  case 4, 9 -> 4;
                  case 16, 17 -> 8;
                  default -> 0;
               };
               if (typeSize == 0 || values > MAX_METADATA_BYTES / typeSize) {
                  continue;
               }
               long size = typeSize * values;
               long dataOffset = size <= (big ? 8 : 4) ? entry + (big ? 12 : 8) : (big ? bytes.u64(entry + 12) : bytes.u32(entry + 8));
               if (tag == 42113) {
                  continue; // GDAL's no-data text; the canopy files use 255, as this reader assumes.
               }
               long[] read = new long[(int)values];
               for (int value = 0; value < values; value++) {
                  long at = dataOffset + (long)value * typeSize;
                  read[value] = switch (typeSize) {
                     case 1 -> bytes.u8(at);
                     case 2 -> bytes.u16(at);
                     case 4 -> bytes.u32(at);
                     default -> bytes.u64(at);
                  };
               }
               tags.put(tag, read);
            }
            ifdOffset = big ? bytes.u64(entries + count * entrySize) : bytes.u32(entries + count * entrySize);

            long[] subfile = tags.get(254);
            if (subfile != null && (subfile[0] & 4L) != 0L) {
               continue; // A transparency mask; the canopy files mark missing pixels with 255 instead.
            }
            images.add(overview(square, tags));
         }
         if (images.isEmpty()) {
            throw new IOException("ETH canopy file " + square + " has no images");
         }
         return new CogFile(square, images.toArray(new Overview[0]), false);
      }

      private static Overview overview(String square, java.util.Map<Integer, long[]> tags) throws IOException {
         int width = (int)required(square, tags, 256);
         int height = (int)required(square, tags, 257);
         int tileWidth = (int)required(square, tags, 322);
         int tileHeight = (int)required(square, tags, 323);
         if (optional(tags, 258, 8) != 8 || optional(tags, 277, 1) != 1 || optional(tags, 339, 1) != 1) {
            throw new IOException("ETH canopy file " + square + " is not single-band 8-bit");
         }
         if (tileWidth <= 0 || tileHeight <= 0 || (long)tileWidth * tileHeight > MAX_BLOCK_PIXELS) {
            throw new IOException("ETH canopy file " + square + " has unusable tiles");
         }
         long[] offsets = tags.get(324);
         long[] byteCounts = tags.get(325);
         long blocks = (long)((width + tileWidth - 1) / tileWidth) * ((height + tileHeight - 1) / tileHeight);
         if (offsets == null || byteCounts == null || offsets.length != blocks || byteCounts.length != blocks) {
            throw new IOException("ETH canopy file " + square + " lists the wrong number of blocks");
         }
         return new Overview(
            width, height, tileWidth, tileHeight, (int)optional(tags, 259, 1), (int)optional(tags, 317, 1), offsets, byteCounts
         );
      }

      private static long required(String square, java.util.Map<Integer, long[]> tags, int tag) throws IOException {
         long[] values = tags.get(tag);
         if (values == null || values.length == 0) {
            throw new IOException("ETH canopy file " + square + " lacks TIFF tag " + tag);
         }
         return values[0];
      }

      private static long optional(java.util.Map<Integer, long[]> tags, int tag, long fallback) {
         long[] values = tags.get(tag);
         return values == null || values.length == 0 ? fallback : values[0];
      }
   }

   /** The start of a file in memory; reads past it fetch the missing range. */
   private static final class Bytes {
      private final ByteOrder order;
      private final RangeReader reader;
      private byte[] data;

      private Bytes(byte[] data, ByteOrder order, RangeReader reader) {
         this.data = data;
         this.order = order;
         this.reader = reader;
      }

      private ByteBuffer at(long offset, int size) throws IOException {
         if (offset < 0L || offset + size > MAX_METADATA_BYTES) {
            throw new IOException("ETH canopy file metadata lies beyond " + MAX_METADATA_BYTES + " bytes");
         }
         if (offset + size > this.data.length) {
            int wanted = (int)Math.min(MAX_METADATA_BYTES, Math.max(offset + size, (long)this.data.length * 2));
            byte[] more = this.reader.read(this.data.length, wanted - this.data.length);
            ByteArrayOutputStream grown = new ByteArrayOutputStream(wanted);
            grown.writeBytes(this.data);
            grown.writeBytes(more);
            this.data = grown.toByteArray();
         }
         return ByteBuffer.wrap(this.data, (int)offset, size).order(this.order);
      }

      int u8(long offset) throws IOException {
         return this.at(offset, 1).get() & 0xFF;
      }

      int u16(long offset) throws IOException {
         return this.at(offset, 2).getShort() & 0xFFFF;
      }

      long u32(long offset) throws IOException {
         return this.at(offset, 4).getInt() & 0xFFFFFFFFL;
      }

      long u64(long offset) throws IOException {
         return this.at(offset, 8).getLong();
      }
   }
}
