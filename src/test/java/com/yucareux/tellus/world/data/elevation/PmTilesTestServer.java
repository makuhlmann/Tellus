package com.yucareux.tellus.world.data.elevation;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A loopback web server with range requests, serving small PMTiles v3 archives built in memory. */
final class PmTilesTestServer implements AutoCloseable {
   static final int COMPRESSION_NONE = 1;
   static final int COMPRESSION_GZIP = 2;
   private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d+)");

   private final HttpServer server;
   private final Map<String, byte[]> files = new ConcurrentHashMap<>();
   private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();

   PmTilesTestServer() throws IOException {
      this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      this.server.createContext("/", exchange -> {
         String name = exchange.getRequestURI().getPath().substring(1);
         this.requests.computeIfAbsent(name, ignored -> new AtomicInteger()).incrementAndGet();
         byte[] file = this.files.get(name);
         if (file == null) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
         }
         Matcher range = RANGE.matcher(String.valueOf(exchange.getRequestHeaders().getFirst("Range")));
         int start = 0;
         int end = file.length - 1;
         if (range.matches()) {
            start = Integer.parseInt(range.group(1));
            end = Math.min(end, Integer.parseInt(range.group(2)));
            exchange.getResponseHeaders().add("Content-Range", "bytes " + start + "-" + end + "/" + file.length);
         }
         exchange.sendResponseHeaders(range.matches() ? 206 : 200, end - start + 1L);
         try (OutputStream body = exchange.getResponseBody()) {
            body.write(file, start, end - start + 1);
         }
      });
      this.server.start();
   }

   /** The folder URL the archives are served from, with a trailing slash. */
   String folderUrl() {
      return "http://" + this.server.getAddress().getHostString() + ":" + this.server.getAddress().getPort() + "/";
   }

   void serve(String name, byte[] archive) {
      this.files.put(name, archive);
   }

   int requestCount(String name) {
      AtomicInteger count = this.requests.get(name);
      return count == null ? 0 : count.get();
   }

   @Override
   public void close() {
      this.server.stop(0);
   }

   /** A PMTiles v3 archive whose root directory lists {@code tiles}, keyed by {@code {zoom, x, y}}. */
   static byte[] archive(int minZoom, int maxZoom, int tileCompression, Map<int[], byte[]> tiles) {
      TreeMap<Long, byte[]> byId = new TreeMap<>();
      tiles.forEach((zxy, bytes) -> byId.put(tileId(zxy[0], zxy[1], zxy[2]), bytes));

      ByteArrayOutputStream data = new ByteArrayOutputStream();
      ByteArrayOutputStream directory = new ByteArrayOutputStream();
      writeVarint(directory, byId.size());
      long lastId = 0L;
      for (long id : byId.keySet()) {
         writeVarint(directory, id - lastId);
         lastId = id;
      }
      byId.values().forEach(ignored -> writeVarint(directory, 1L));
      byId.values().forEach(bytes -> writeVarint(directory, bytes.length));
      for (byte[] bytes : byId.values()) {
         writeVarint(directory, data.size() + 1L);
         data.writeBytes(bytes);
      }

      byte[] root = directory.toByteArray();
      byte[] tileData = data.toByteArray();
      ByteBuffer header = ByteBuffer.allocate(127).order(ByteOrder.LITTLE_ENDIAN);
      header.put("PMTiles".getBytes(StandardCharsets.US_ASCII)).put((byte)3);
      header.putLong(127L).putLong(root.length);
      header.putLong(127L + root.length).putLong(0L);
      header.putLong(127L + root.length).putLong(0L);
      header.putLong(127L + root.length).putLong(tileData.length);
      header.putLong(byId.size()).putLong(byId.size()).putLong(byId.size());
      header.put((byte)1).put((byte)COMPRESSION_NONE).put((byte)tileCompression).put((byte)4);
      header.put((byte)minZoom).put((byte)maxZoom);

      ByteArrayOutputStream archive = new ByteArrayOutputStream();
      archive.writeBytes(header.array());
      archive.writeBytes(root);
      archive.writeBytes(tileData);
      return archive.toByteArray();
   }

   /** The PMTiles tile id of {@code zoom/x/y}: tiles of lower zooms first, then along the Hilbert curve. */
   static long tileId(int zoom, int x, int y) {
      long id = ((1L << (zoom * 2)) - 1L) / 3L;
      for (int level = zoom - 1; level >= 0; level--) {
         int scale = 1 << level;
         int rx = (x & scale) != 0 ? 1 : 0;
         int ry = (y & scale) != 0 ? 1 : 0;
         id += (long)scale * scale * ((3 * rx) ^ ry);
         if (ry == 0) {
            if (rx == 1) {
               x = scale - 1 - (x & (scale - 1));
               y = scale - 1 - (y & (scale - 1));
            }
            int swapped = x;
            x = y;
            y = swapped;
         }
      }
      return id;
   }

   private static void writeVarint(ByteArrayOutputStream out, long value) {
      long remaining = value;
      while (remaining >= 0x80) {
         out.write((int)(remaining & 0x7F) | 0x80);
         remaining >>>= 7;
      }
      out.write((int)remaining);
   }
}
