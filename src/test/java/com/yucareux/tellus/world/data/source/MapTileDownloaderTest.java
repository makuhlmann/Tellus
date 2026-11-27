package com.yucareux.tellus.world.data.source;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MapTileDownloaderTest {
   private static final String USER_AGENT = "Tellus-Test (+https://example.org)";
   private static final byte[] TILE = new byte[]{1, 2, 3, 4, 5, 6, 7, 8};
   private final AtomicInteger requests = new AtomicInteger();
   private final AtomicReference<String> userAgent = new AtomicReference<>();
   private final AtomicReference<Responder> responder = new AtomicReference<>();
   private final MapTileDownloader downloader = new MapTileDownloader(USER_AGENT);
   private final MapTileServerGate gate = new MapTileServerGate("Test server", 2, Duration.ZERO);
   private HttpServer server;

   private interface Responder {
      void respond(HttpExchange exchange) throws IOException;
   }

   @BeforeEach
   void startServer() throws IOException {
      this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      this.server.createContext("/", exchange -> {
         this.requests.incrementAndGet();
         this.userAgent.set(exchange.getRequestHeaders().getFirst("User-Agent"));
         try {
            this.responder.get().respond(exchange);
         } finally {
            exchange.close();
         }
      });
      this.server.start();
   }

   @AfterEach
   void stopServer() {
      this.server.stop(0);
   }

   private URI tileUri() throws URISyntaxException {
      InetSocketAddress address = this.server.getAddress();
      return new URI("http", null, address.getAddress().getHostAddress(), address.getPort(), "/10/550/335.png", null, null);
   }

   private byte[] download(int maxBytes) throws Exception {
      return this.downloader.download(this.tileUri(), this.gate, maxBytes, new MapTileDownloader.Request() {
         @Override
         public boolean cancelled() {
            return false;
         }

         @Override
         public boolean commit() {
            return true;
         }
      });
   }

   private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
      exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
      if (body.length > 0) {
         try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
         }
      }
   }

   @Test
   void aTileIsDownloadedWithTheUserAgent() throws Exception {
      this.responder.set(exchange -> send(exchange, 200, TILE));
      assertArrayEquals(TILE, this.download(1024));
      assertEquals(USER_AGENT, this.userAgent.get());
      assertNull(this.gate.status());
   }

   @Test
   void aRateLimitPausesTheServerForItsRetryAfter() throws Exception {
      this.responder.set(exchange -> {
         exchange.getResponseHeaders().set("Retry-After", "7");
         send(exchange, 429, new byte[0]);
      });
      MapTileDownloader.TileResponseException refused = assertThrows(MapTileDownloader.TileResponseException.class, () -> this.download(1024));
      assertEquals(429, refused.status());
      assertFalse(refused.concernsTileOnly());

      MapTileServerGate.Status status = this.gate.status();
      assertNotNull(status);
      assertEquals(MapTileServerGate.PauseReason.REFUSED, status.reason());
      assertTrue(status.remainingNanos() > TimeUnit.SECONDS.toNanos(5L) && status.remainingNanos() <= TimeUnit.SECONDS.toNanos(7L));

      assertThrows(MapTileServerGate.PausedException.class, () -> this.download(1024));
      assertEquals(1, this.requests.get(), "a paused server is not asked again");
   }

   @Test
   void aBlockTileIsARefusalNotATile() throws Exception {
      this.responder.set(exchange -> {
         exchange.getResponseHeaders().set("x-blocked", "unidentified client");
         send(exchange, 200, TILE);
      });
      MapTileDownloader.TileResponseException blocked = assertThrows(MapTileDownloader.TileResponseException.class, () -> this.download(1024));
      assertEquals(200, blocked.status());
      assertFalse(blocked.concernsTileOnly());
      assertEquals(MapTileServerGate.PauseReason.REFUSED, this.gate.status().reason());
   }

   @Test
   void aMissingTileConcernsOnlyThatTile() throws Exception {
      this.responder.set(exchange -> send(exchange, 404, new byte[0]));
      for (int attempt = 0; attempt < 5; attempt++) {
         MapTileDownloader.TileResponseException missing = assertThrows(MapTileDownloader.TileResponseException.class, () -> this.download(1024));
         assertTrue(missing.concernsTileOnly());
      }
      assertNull(this.gate.status());
   }

   @Test
   void serverErrorsInARowPauseTheServer() throws Exception {
      this.responder.set(exchange -> send(exchange, 500, new byte[0]));
      for (int attempt = 0; attempt < MapTileServerGate.FAILURES_BEFORE_PAUSE; attempt++) {
         assertThrows(MapTileDownloader.TileResponseException.class, () -> this.download(1024));
      }
      assertEquals(MapTileServerGate.PauseReason.UNREACHABLE, this.gate.status().reason());
   }

   @Test
   void aTileOverTheLimitIsRejected() throws Exception {
      byte[] large = new byte[4096];
      this.responder.set(exchange -> send(exchange, 200, large));
      assertThrows(MapTileDownloader.TileTooLargeException.class, () -> this.download(1024));

      this.responder.set(exchange -> {
         // Chunked, so the size shows only while reading.
         exchange.sendResponseHeaders(200, 0);
         try (OutputStream output = exchange.getResponseBody()) {
            output.write(large);
         } catch (IOException clientGaveUp) {
            // The client stops reading once the limit is passed.
         }
      });
      assertThrows(MapTileDownloader.TileTooLargeException.class, () -> this.download(1024));
      assertNull(this.gate.status());
   }

   @Test
   void aRequestCancelledBeforeSendingNeverReachesTheServer() throws Exception {
      this.responder.set(exchange -> send(exchange, 200, TILE));
      MapTileServerGate singleRequestGate = new MapTileServerGate("Single", 1, Duration.ZERO);
      assertThrows(InterruptedIOException.class, () -> this.downloader.download(this.tileUri(), singleRequestGate, 1024, new MapTileDownloader.Request() {
         @Override
         public boolean cancelled() {
            return false;
         }

         @Override
         public boolean commit() {
            return false;
         }
      }));
      assertEquals(0, this.requests.get());

      // The request slot went back to the gate; if it had not, this would have to wait and then give up.
      AtomicInteger checks = new AtomicInteger();
      singleRequestGate.acquire(() -> checks.incrementAndGet() > 1).close();
   }
}
