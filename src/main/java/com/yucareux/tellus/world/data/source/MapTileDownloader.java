package com.yucareux.tellus.world.data.source;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Downloads map tiles through their server's {@link MapTileServerGate}, over one HTTP client shared by every map.
 * The client keeps one HTTP/2 connection per server and sends concurrent tiles over it, instead of a TLS handshake
 * for every tile.
 */
public final class MapTileDownloader {
   private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5L);
   /** Until the response headers arrive. */
   private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(7L);
   /** For the whole download, body included. */
   private static final long TOTAL_TIMEOUT_SECONDS = 15L;
   private static final int MAX_DETAIL_LENGTH = 80;
   private static final HttpClient SHARED_CLIENT = HttpClient.newBuilder()
      .connectTimeout(CONNECT_TIMEOUT)
      .followRedirects(HttpClient.Redirect.NORMAL)
      .version(HttpClient.Version.HTTP_2)
      .build();

   private final HttpClient client;
   private final String userAgent;

   public MapTileDownloader(String userAgent) {
      this(SHARED_CLIENT, userAgent);
   }

   MapTileDownloader(HttpClient client, String userAgent) {
      this.client = Objects.requireNonNull(client, "client");
      this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
   }

   /** How one tile request looks to the downloader. */
   public interface Request {
      /** Polled while waiting for the server; true abandons the request before anything is sent. */
      boolean cancelled();

      /** Called once, right before sending; false abandons the request. A request that was sent always finishes. */
      boolean commit();
   }

   /**
    * Waits for the server's gate, downloads the tile and tells the gate how the server responded.
    *
    * @return the body of a 200 response, at most {@code maxBytes} long
    * @throws MapTileServerGate.PausedException while the server is paused; nothing was sent
    * @throws InterruptedIOException when the request was cancelled before it was sent
    * @throws TileResponseException when the server answered with anything but the tile
    * @throws TileTooLargeException when the tile is larger than {@code maxBytes}
    */
   public byte[] download(URI uri, MapTileServerGate gate, int maxBytes, Request request) throws IOException {
      if (maxBytes <= 0) {
         throw new IllegalArgumentException("Map tile byte limit must be positive");
      }
      MapTileServerGate.Permit permit = gate.acquire(request::cancelled);
      try {
         if (!request.commit()) {
            throw new InterruptedIOException("Map tile request was cancelled");
         }
         return this.send(uri, gate, permit, maxBytes);
      } finally {
         permit.close();
      }
   }

   private byte[] send(URI uri, MapTileServerGate gate, MapTileServerGate.Permit permit, int maxBytes) throws IOException {
      HttpRequest httpRequest = HttpRequest.newBuilder(uri)
         .timeout(RESPONSE_TIMEOUT)
         .header("User-Agent", this.userAgent)
         .header("Accept", "image/png")
         .GET()
         .build();
      HttpResponse.BodyHandler<byte[]> bodyHandler = info -> info.statusCode() == 200
         ? new BoundedBodySubscriber(maxBytes, info.headers().firstValueAsLong("content-length").orElse(-1L), uri)
         : HttpResponse.BodySubscribers.replacing((byte[])null);
      CompletableFuture<HttpResponse<byte[]>> future = this.client.sendAsync(httpRequest, bodyHandler);

      HttpResponse<byte[]> response;
      try {
         response = future.get(TOTAL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      } catch (TimeoutException timeout) {
         future.cancel(true);
         permit.failed("no complete answer within " + TOTAL_TIMEOUT_SECONDS + " s");
         throw new HttpTimeoutException(gate.serverName() + " did not deliver " + uri + " within " + TOTAL_TIMEOUT_SECONDS + " s");
      } catch (InterruptedException interrupted) {
         future.cancel(true);
         Thread.currentThread().interrupt();
         throw new InterruptedIOException("Interrupted while downloading " + uri);
      } catch (ExecutionException failure) {
         Throwable cause = unwrap(failure);
         if (cause instanceof TileTooLargeException tooLarge) {
            permit.answered();
            throw tooLarge;
         }
         permit.failed(describe(cause));
         throw cause instanceof IOException io ? io : new IOException("Failed to download " + uri, cause);
      }

      int status = response.statusCode();
      HttpHeaders headers = response.headers();
      // tile.openstreetmap.org answers a blocked client with 200 and an image saying so, which must not be cached.
      String blocked = headers.firstValue("x-blocked").orElse(null);
      if (status == 200 && blocked == null) {
         permit.answered();
         return response.body();
      }

      Duration retryAfter =MapTileServerGate.parseRetryAfter(headers.firstValue("retry-after").orElse(null), Instant.now());
      MapTileServerGate.Response verdict = blocked != null ? MapTileServerGate.Response.REFUSED : MapTileServerGate.classify(status, retryAfter != null);
      String detail = blocked != null ? "HTTP " + status + ", blocked: " + truncate(blocked) : "HTTP " + status;
      switch (verdict) {
         case REFUSED -> permit.refused(retryAfter, detail);
         case FAILED -> permit.failed(detail);
         case ANSWERED -> permit.answered();
      }
      throw new TileResponseException(status, verdict, gate.serverName() + " answered " + detail + " for " + uri);
   }

   private static Throwable unwrap(ExecutionException failure) {
      Throwable cause = failure.getCause() != null ? failure.getCause() : failure;
      while (cause instanceof CompletionException && cause.getCause() != null) {
         cause = cause.getCause();
      }
      return cause;
   }

   private static String describe(Throwable error) {
      String message = error.getMessage();
      return truncate(message == null || message.isBlank() ? error.getClass().getSimpleName() : error.getClass().getSimpleName() + ": " + message);
   }

   private static String truncate(String text) {
      return text.length() <= MAX_DETAIL_LENGTH ? text : text.substring(0, MAX_DETAIL_LENGTH) + "...";
   }

   /** The server answered, but not with the tile. */
   public static final class TileResponseException extends IOException {
      private final int status;
      private final MapTileServerGate.Response verdict;

      TileResponseException(int status, MapTileServerGate.Response verdict, String message) {
         super(message);
         this.status = status;
         this.verdict = verdict;
      }

      public int status() {
         return this.status;
      }

      public MapTileServerGate.Response verdict() {
         return this.verdict;
      }

      /** True for errors about this one tile, such as 404, as opposed to a refusal or failure of the server. */
      public boolean concernsTileOnly() {
         return this.verdict == MapTileServerGate.Response.ANSWERED;
      }
   }

   /** The tile is larger than any tile the map accepts. */
   public static final class TileTooLargeException extends IOException {
      TileTooLargeException(String message) {
         super(message);
      }
   }

   /** Collects a body up to a size limit, and fails instead of buffering more. */
   private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
      private final CompletableFuture<byte[]> body = new CompletableFuture<>();
      private final int maxBytes;
      private final long declaredLength;
      private final URI uri;
      private ByteArrayOutputStream buffer;
      private Flow.Subscription subscription;

      private BoundedBodySubscriber(int maxBytes, long declaredLength, URI uri) {
         this.maxBytes = maxBytes;
         this.declaredLength = declaredLength;
         this.uri = uri;
      }

      @Override
      public CompletionStage<byte[]> getBody() {
         return this.body;
      }

      @Override
      public void onSubscribe(Flow.Subscription subscription) {
         this.subscription = subscription;
         if (this.declaredLength > this.maxBytes) {
            subscription.cancel();
            this.body.completeExceptionally(this.tooLarge());
            return;
         }
         this.buffer = new ByteArrayOutputStream(this.declaredLength > 0L ? (int)this.declaredLength : 64 * 1024);
         subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(List<ByteBuffer> items) {
         if (this.body.isDone()) {
            return;
         }
         for (ByteBuffer item : items) {
            int length = item.remaining();
            if (length > this.maxBytes - this.buffer.size()) {
               this.subscription.cancel();
               this.body.completeExceptionally(this.tooLarge());
               return;
            }
            byte[] chunk = new byte[length];
            item.get(chunk);
            this.buffer.write(chunk, 0, length);
         }
      }

      @Override
      public void onError(Throwable error) {
         this.body.completeExceptionally(error);
      }

      @Override
      public void onComplete() {
         if (!this.body.isDone()) {
            this.body.complete(this.buffer.toByteArray());
         }
      }

      private TileTooLargeException tooLarge() {
         return new TileTooLargeException("Map tile " + this.uri + " exceeds the safety limit of " + this.maxBytes + " bytes");
      }
   }
}
