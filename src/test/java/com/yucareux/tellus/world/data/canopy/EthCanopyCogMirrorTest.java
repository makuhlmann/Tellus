package com.yucareux.tellus.world.data.canopy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EthCanopyCogMirrorTest {
   private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");

   @Test
   void namesFilesByTheirSouthWestCorner() {
      assertEquals("N81W180", EthCanopyCogMirror.squareName(0, 0));
      assertEquals("N48E006", EthCanopyCogMirror.squareName(11, 62));
      assertEquals("N00E018", EthCanopyCogMirror.squareName(27, 66));
      assertEquals("S06W063", EthCanopyCogMirror.squareName(29, 39));
   }

   @Test
   void decodesTiffLzwThroughEveryCodeWidthAndATableReset() throws IOException {
      // Repetitive data with noise grows the table past 4094 entries, so the encoder resets it midway.
      Random random = new Random(42L);
      byte[] input = new byte[300_000];
      for (int index = 0; index < input.length; index++) {
         input[index] = (byte)(random.nextInt(8) == 0 ? random.nextInt(256) : (index / 7) % 40);
      }
      byte[] encoded = encodeLzw(input);

      assertArrayEquals(input, EthCanopyCogMirror.decodeLzw(encoded, input.length));
   }

   @Test
   void undoesHorizontalDifferencingRowByRow() {
      byte[] pixels = {5, 1, 1, (byte)250, 7, 2, (byte)255, 0};

      EthCanopyCogMirror.undoHorizontalDifferencing(pixels, 4);

      assertArrayEquals(new byte[]{5, 6, 7, 1, 7, 9, 8, 8}, pixels);
   }

   @Test
   void buildsTilesAcrossFilesAndAveragesTheCoarseLevels() throws IOException {
      // One file, N45E006, whose first block holds height x % 50 in every row (except 255 at x = 3), at
      // full resolution and in the 1/32 overview. Its neighbour N48E006 to the north is not on the server.
      byte[] block = new byte[1024 * 1024];
      for (int y = 0; y < 1024; y++) {
         for (int x = 0; x < 1024; x++) {
            block[y * 1024 + x] = (byte)(x == 3 ? 255 : x % 50);
         }
      }
      byte[] tiff = tiff(block);
      HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/", exchange -> {
         if (!exchange.getRequestURI().getPath().endsWith("_N45E006_Map.tif")) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
         }
         Matcher range = RANGE.matcher(String.valueOf(exchange.getRequestHeaders().getFirst("Range")));
         if (!range.matches()) {
            exchange.sendResponseHeaders(416, -1);
            exchange.close();
            return;
         }
         int start = Integer.parseInt(range.group(1));
         int end = Math.min(tiff.length - 1, Integer.parseInt(range.group(2)));
         exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + tiff.length);
         exchange.sendResponseHeaders(206, end - start + 1L);
         try (OutputStream body = exchange.getResponseBody()) {
            body.write(tiff, start, end - start + 1);
         }
      });
      server.start();
      try {
         String folder = "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/mirror";
         EthCanopyCogMirror mirror = new EthCanopyCogMirror(folder, 2000, 2000, 16L << 20);

         // N45E006 starts at native row 432000 and column 2232000, inside tile 1687/8718 at (192, 128).
         EthCanopyCogMirror.Tile straddling = mirror.tile(13, 1687, 8718);
         assertFalse(straddling.complete(), "the northern half needs the missing N48E006");
         assertEquals(255, straddling.pixels()[0] & 255);
         assertEquals(0, straddling.pixels()[128 * 256 + 192] & 255);
         assertEquals(49, straddling.pixels()[128 * 256 + 192 + 49] & 255);
         assertEquals(255, straddling.pixels()[128 * 256 + 192 + 3] & 255, "255 stays no data");

         // Level 7 averages 2 x 2 pixels of the 1/32 overview: pixel (6750, 34875) covers its x = 0..1.
         EthCanopyCogMirror.Tile coarse = mirror.tile(7, 26, 136);
         assertEquals(1, coarse.pixels()[94 * 256 + 59] & 255, "the mean of 0 and 1, rounded up");
         assertEquals(255, coarse.pixels()[0] & 255);

         // Wholly inside the file, a tile is complete.
         EthCanopyCogMirror.Tile inside = mirror.tile(13, 1689, 8720);
         assertTrue(inside.complete());
      } finally {
         server.stop(0);
      }
   }

   /** A 36000-pixel-square file with overviews down to 1/32, all blocks empty but the first of two images. */
   private static byte[] tiff(byte[] firstBlock) {
      int images = 6;
      ByteArrayOutputStream file = new ByteArrayOutputStream();
      int headerSize = 8;
      int[] widths = new int[images];
      int[] blocks = new int[images];
      for (int image = 0; image < images; image++) {
         widths[image] = 36000 >> image;
         int across = (widths[image] + 1023) / 1024;
         blocks[image] = across * across;
      }
      int entries = 12;
      int[] ifdOffsets = new int[images];
      int offset = headerSize;
      for (int image = 0; image < images; image++) {
         ifdOffsets[image] = offset;
         offset += 2 + entries * 12 + 4 + blocks[image] * 8;
      }
      int dataOffset = offset;

      ByteBuffer out = ByteBuffer.allocate(dataOffset).order(ByteOrder.LITTLE_ENDIAN);
      out.put((byte)'I').put((byte)'I').putShort((short)42).putInt(ifdOffsets[0]);
      for (int image = 0; image < images; image++) {
         int arrays = ifdOffsets[image] + 2 + entries * 12 + 4;
         boolean hasBlock = image == 0 || image == 5;
         out.position(ifdOffsets[image]);
         out.putShort((short)entries);
         entry(out, 254, 4, 1, image == 0 ? 0 : 1);
         entry(out, 256, 4, 1, widths[image]);
         entry(out, 257, 4, 1, widths[image]);
         entry(out, 258, 3, 1, 8);
         entry(out, 259, 3, 1, 1);
         entry(out, 277, 3, 1, 1);
         entry(out, 317, 3, 1, 1);
         entry(out, 322, 3, 1, 1024);
         entry(out, 323, 3, 1, 1024);
         entry(out, 324, 4, blocks[image], arrays);
         entry(out, 325, 4, blocks[image], arrays + blocks[image] * 4);
         entry(out, 339, 3, 1, 1);
         out.putInt(image + 1 < images ? ifdOffsets[image + 1] : 0);
         for (int index = 0; index < blocks[image]; index++) {
            out.putInt(hasBlock && index == 0 ? dataOffset + (image == 5 ? firstBlock.length : 0) : 0);
         }
         for (int index = 0; index < blocks[image]; index++) {
            out.putInt(hasBlock && index == 0 ? firstBlock.length : 0);
         }
      }
      file.writeBytes(out.array());
      file.writeBytes(firstBlock);
      file.writeBytes(firstBlock);
      return file.toByteArray();
   }

   private static void entry(ByteBuffer out, int tag, int type, int count, int value) {
      out.putShort((short)tag).putShort((short)type).putInt(count);
      if (type == 3 && count == 1) {
         out.putShort((short)value).putShort((short)0);
      } else {
         out.putInt(value);
      }
   }

   /** TIFF's LZW, as libtiff writes it: codes widen when the next code would not fit, reset at 4094. */
   private static byte[] encodeLzw(byte[] input) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      long[] bits = new long[2]; // buffer, count
      Map<Integer, Integer> table = new HashMap<>();
      int width = 9;
      int next = 258;
      write(output, bits, 256, width);
      int prefix = input[0] & 0xFF;
      for (int index = 1; index < input.length; index++) {
         int value = input[index] & 0xFF;
         Integer code = table.get(prefix << 8 | value);
         if (code != null) {
            prefix = code;
            continue;
         }
         write(output, bits, prefix, width);
         table.put(prefix << 8 | value, next++);
         if (next == 4094) {
            write(output, bits, 256, width);
            table.clear();
            next = 258;
            width = 9;
         } else if (next > (1 << width) - 1) {
            width++;
         }
         prefix = value;
      }
      write(output, bits, prefix, width);
      next++;
      if (next > (1 << width) - 1 && width < 12) {
         width++;
      }
      write(output, bits, 257, width);
      if (bits[1] > 0) {
         output.write((int)(bits[0] << (8 - bits[1])) & 0xFF);
      }
      return output.toByteArray();
   }

   private static void write(ByteArrayOutputStream output, long[] bits, int code, int width) {
      bits[0] = (bits[0] << width) | code;
      bits[1] += width;
      while (bits[1] >= 8) {
         output.write((int)(bits[0] >>> (bits[1] - 8)) & 0xFF);
         bits[1] -= 8;
      }
      bits[0] &= (1L << bits[1]) - 1;
   }
}
