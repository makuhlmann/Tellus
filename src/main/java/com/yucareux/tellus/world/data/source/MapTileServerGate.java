package com.yucareux.tellus.world.data.source;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/**
 * The request budget of one tile server, shared by every map that shows its tiles. It caps the requests in flight,
 * spaces their starts, and pauses the whole server when it refuses a request or stops answering, so no map keeps
 * asking a server that has just turned another tile down. After a pause one request at a time goes through until the
 * server answers again.
 */
public final class MapTileServerGate {
   /** The pause after a refusal; each further refusal in a row doubles it. */
   static final long REFUSED_PAUSE_NANOS = TimeUnit.SECONDS.toNanos(60L);
   static final long MAX_REFUSED_PAUSE_NANOS = TimeUnit.MINUTES.toNanos(15L);
   /** Failed requests in a row after which the server counts as unreachable. */
   static final int FAILURES_BEFORE_PAUSE = 3;
   /** The pause once the server counts as unreachable; each further outage in a row doubles it. */
   static final long UNREACHABLE_PAUSE_NANOS = TimeUnit.SECONDS.toNanos(15L);
   static final long MAX_UNREACHABLE_PAUSE_NANOS = TimeUnit.MINUTES.toNanos(5L);
   static final Duration MIN_RETRY_AFTER = Duration.ofSeconds(1L);
   static final Duration MAX_RETRY_AFTER = Duration.ofHours(1L);
   /** How often a waiting request checks whether it was cancelled. */
   private static final long WAIT_SLICE_MILLIS = 50L;
   private static final Logger LOGGER = LogUtils.getLogger();

   public enum PauseReason {
      /** The server answered with a rate limit or a block. */
      REFUSED,
      /** Requests failed or timed out several times in a row. */
      UNREACHABLE
   }

   /** What a response says about the server, regardless of whether it delivered the tile. */
   public enum Response {
      /** The server is working; a 404 for one tile counts as answered. */
      ANSWERED,
      /** The server turned the request down: rate limited or blocked. */
      REFUSED,
      /** The server failed or did not answer in time. */
      FAILED
   }

   /** Why the server is paused and how much longer, for a notice on the map. */
   public record Status(PauseReason reason, long remainingNanos) {
   }

   private final String serverName;
   private final int maxConcurrentRequests;
   private final long requestSpacingNanos;
   private final LongSupplier nanoClock;
   private final Object lock = new Object();
   private int inFlight;
   private long nextStartNanos;
   private boolean paused;
   private long resumeAtNanos;
   private PauseReason pauseReason;
   /** Set by a pause and cleared by the next answer; meanwhile only one request runs at a time. */
   private boolean probing;
   /** Counts pauses, so the outcome of a request sent before the latest pause does not count twice. */
   private long pauseCount;
   private int refusalsInRow;
   private int failuresInRow;
   private int outagesInRow;

   public MapTileServerGate(String serverName, int maxConcurrentRequests, Duration requestSpacing) {
      this(serverName, maxConcurrentRequests, requestSpacing, System::nanoTime);
   }

   MapTileServerGate(String serverName, int maxConcurrentRequests, Duration requestSpacing, LongSupplier nanoClock) {
      if (maxConcurrentRequests <= 0) {
         throw new IllegalArgumentException("A tile server needs at least one concurrent request");
      }
      this.serverName = Objects.requireNonNull(serverName, "serverName");
      this.maxConcurrentRequests = maxConcurrentRequests;
      this.requestSpacingNanos = Math.max(0L, requestSpacing.toNanos());
      this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
      this.nextStartNanos = nanoClock.getAsLong();
   }

   public String serverName() {
      return this.serverName;
   }

   /**
    * Waits until the server may take another request and reserves it. The caller reports the response on the permit,
    * or closes it when no request was sent.
    *
    * @throws PausedException while the server is paused
    * @throws InterruptedIOException once {@code cancelled} reports true, or when the thread is interrupted
    */
   public Permit acquire(BooleanSupplier cancelled) throws IOException {
      synchronized (this.lock) {
         while (true) {
            if (cancelled.getAsBoolean()) {
               throw new InterruptedIOException("Map tile request was cancelled");
            }
            long now = this.nanoClock.getAsLong();
            if (this.paused) {
               if (now - this.resumeAtNanos < 0L) {
                  throw new PausedException(this.serverName, this.pauseReason, this.resumeAtNanos);
               }
               this.paused = false;
            }

            // A request sent before the pause still counts while it runs, so the probe may wait for it to finish
            // or time out; that is intended, the server is not asked twice at once right after refusing.
            int limit = this.probing ? 1 : this.maxConcurrentRequests;
            long untilStart = this.nextStartNanos - now;
            if (this.inFlight < limit && untilStart <= 0L) {
               this.inFlight++;
               this.nextStartNanos = now + this.requestSpacingNanos;
               return new Permit(this.pauseCount);
            }

            long waitMillis = this.inFlight < limit ? (untilStart + 999_999L) / 1_000_000L : WAIT_SLICE_MILLIS;
            try {
               this.lock.wait(Math.max(1L, Math.min(waitMillis, WAIT_SLICE_MILLIS)));
            } catch (InterruptedException interrupted) {
               Thread.currentThread().interrupt();
               throw new InterruptedIOException("Interrupted while waiting for " + this.serverName);
            }
         }
      }
   }

   /** Why the server is paused, or null while it takes requests. */
   public Status status() {
      synchronized (this.lock) {
         if (!this.paused) {
            return null;
         }
         long remaining = this.resumeAtNanos - this.nanoClock.getAsLong();
         return remaining > 0L ? new Status(this.pauseReason, remaining) : null;
      }
   }

   private void release(Permit permit, Response response, Duration retryAfter, String detail) {
      String warning = null;
      String info = null;
      synchronized (this.lock) {
         if (permit.released) {
            return;
         }
         permit.released = true;
         this.inFlight--;
         long now = this.nanoClock.getAsLong();
         boolean current = permit.pauseCount == this.pauseCount;
         if (response == Response.ANSWERED && current) {
            if (this.probing) {
               info = this.serverName + " answers tile requests again";
            }
            this.probing = false;
            this.refusalsInRow = 0;
            this.failuresInRow = 0;
            this.outagesInRow = 0;
         } else if (response == Response.REFUSED) {
            if (current) {
               this.refusalsInRow++;
               this.failuresInRow = 0;
               long pause = retryAfter != null
                  ? clampRetryAfter(retryAfter).toNanos()
                  : doubled(REFUSED_PAUSE_NANOS, this.refusalsInRow, MAX_REFUSED_PAUSE_NANOS);
               this.pause(now + pause, PauseReason.REFUSED);
               warning = this.serverName + " refused tile requests (" + detail + "); pausing them for "
                  + TimeUnit.NANOSECONDS.toSeconds(pause) + " s";
            } else if (retryAfter != null && this.paused) {
               // A refusal of a request sent before the pause may still ask for a longer one.
               long resumeAt = now + clampRetryAfter(retryAfter).toNanos();
               if (resumeAt - this.resumeAtNanos > 0L) {
                  this.resumeAtNanos = resumeAt;
               }
            }
         } else if (response == Response.FAILED && current) {
            this.failuresInRow++;
            if (this.probing || this.failuresInRow >= FAILURES_BEFORE_PAUSE) {
               this.outagesInRow++;
               this.failuresInRow = 0;
               long pause = doubled(UNREACHABLE_PAUSE_NANOS, this.outagesInRow, MAX_UNREACHABLE_PAUSE_NANOS);
               this.pause(now + pause, PauseReason.UNREACHABLE);
               warning = this.serverName + " is not answering tile requests (" + detail + "); pausing them for "
                  + TimeUnit.NANOSECONDS.toSeconds(pause) + " s";
            }
         }
         this.lock.notifyAll();
      }

      if (warning != null) {
         LOGGER.warn(warning);
      } else if (info != null) {
         LOGGER.info(info);
      }
   }

   private void pause(long resumeAtNanos, PauseReason reason) {
      this.paused = true;
      this.resumeAtNanos = resumeAtNanos;
      this.pauseReason = reason;
      this.probing = true;
      this.pauseCount++;
   }

   private static long doubled(long base, int count, long max) {
      int shift = Math.min(Math.max(0, count - 1), 20);
      return Math.min(max, base << shift);
   }

   static Duration clampRetryAfter(Duration delay) {
      if (delay.compareTo(MIN_RETRY_AFTER) < 0) {
         return MIN_RETRY_AFTER;
      }
      return delay.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : delay;
   }

   /**
    * What a response with this status says about the server. 429, 403 and 418 mean the client is rate limited or
    * blocked; a 503 that names a {@code Retry-After} is an overloaded server asking for a pause. Other 4xx codes
    * concern only the requested tile.
    */
   public static Response classify(int statusCode, boolean hasRetryAfter) {
      if (statusCode == 429 || statusCode == 403 || statusCode == 418 || statusCode == 503 && hasRetryAfter) {
         return Response.REFUSED;
      }
      if (statusCode == 408 || statusCode >= 500) {
         return Response.FAILED;
      }
      return Response.ANSWERED;
   }

   /**
    * Reads a {@code Retry-After} header, given either in seconds or as an HTTP date, clamped to between one second
    * and one hour. Returns null when the header is missing or unreadable.
    */
   public static Duration parseRetryAfter(String value, Instant now) {
      if (value == null || value.isBlank()) {
         return null;
      }
      String trimmed = value.trim();
      Duration delay;
      if (trimmed.chars().allMatch(c -> c >= '0' && c <= '9')) {
         try {
            delay = Duration.ofSeconds(Long.parseLong(trimmed));
         } catch (NumberFormatException tooLarge) {
            delay = MAX_RETRY_AFTER;
         }
      } else {
         try {
            delay = Duration.between(now, ZonedDateTime.parse(trimmed, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
         } catch (DateTimeParseException unreadable) {
            return null;
         }
      }
      return clampRetryAfter(delay);
   }

   /** One reserved request. Report how the server responded, or close the permit when nothing was sent. */
   public final class Permit implements AutoCloseable {
      private final long pauseCount;
      private boolean released;

      private Permit(long pauseCount) {
         this.pauseCount = pauseCount;
      }

      /** The server answered; this counts even when the answer was an error for the one tile. */
      public void answered() {
         MapTileServerGate.this.release(this, Response.ANSWERED, null, null);
      }

      /** The server rate limited or blocked the request. {@code retryAfter} may be null. */
      public void refused(Duration retryAfter, String detail) {
         MapTileServerGate.this.release(this, Response.REFUSED, retryAfter, detail);
      }

      /** The server failed or did not answer in time. */
      public void failed(String detail) {
         MapTileServerGate.this.release(this, Response.FAILED, null, detail);
      }

      /** Frees the request without judging the server; does nothing after a response was reported. */
      @Override
      public void close() {
         MapTileServerGate.this.release(this, null, null, null);
      }
   }

   /** Thrown instead of waiting while the server is paused. */
   public static final class PausedException extends IOException {
      private final PauseReason reason;
      private final long resumeAtNanos;

      PausedException(String serverName, PauseReason reason, long resumeAtNanos) {
         super(serverName + " is paused after " + (reason == PauseReason.REFUSED ? "refusing" : "failing") + " tile requests");
         this.reason = reason;
         this.resumeAtNanos = resumeAtNanos;
      }

      public PauseReason reason() {
         return this.reason;
      }

      /** The {@link System#nanoTime()} at which the server takes requests again. */
      public long resumeAtNanos() {
         return this.resumeAtNanos;
      }

      /** Thrown once per waiting tile and pause; its stack trace would say nothing. */
      @Override
      public synchronized Throwable fillInStackTrace() {
         return this;
      }
   }
}
