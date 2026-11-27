package com.yucareux.tellus.world.data.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InterruptedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class MapTileServerGateTest {
   private final AtomicLong clock = new AtomicLong(1_000_000_000L);

   private MapTileServerGate gate(int maxConcurrentRequests, Duration spacing) {
      return new MapTileServerGate("Test server", maxConcurrentRequests, spacing, this.clock::get);
   }

   private void advance(Duration duration) {
      this.clock.addAndGet(duration.toNanos());
   }

   private static BooleanSupplier notCancelled() {
      return () -> false;
   }

   /** Not cancelled on the first check, cancelled on the next: an acquire that has to wait gives up at once. */
   private static BooleanSupplier cancelledOnceWaiting() {
      AtomicInteger checks = new AtomicInteger();
      return () -> checks.incrementAndGet() > 1;
   }

   private long remainingSeconds(MapTileServerGate gate) {
      MapTileServerGate.Status status = gate.status();
      assertNotNull(status, "the server should be paused");
      return TimeUnit.NANOSECONDS.toSeconds(status.remainingNanos());
   }

   @Test
   void refusalPausesTheWholeServerAndDoublesWhileItRepeats() throws Exception {
      MapTileServerGate gate = this.gate(2, Duration.ZERO);
      gate.acquire(notCancelled()).refused(null, "HTTP 429");

      MapTileServerGate.PausedException paused = assertThrows(MapTileServerGate.PausedException.class, () -> gate.acquire(notCancelled()));
      assertEquals(MapTileServerGate.PauseReason.REFUSED, paused.reason());
      assertEquals(60L, this.remainingSeconds(gate));

      this.advance(Duration.ofSeconds(60L));
      assertNull(gate.status());
      gate.acquire(notCancelled()).refused(null, "HTTP 429");
      assertEquals(120L, this.remainingSeconds(gate));

      this.advance(Duration.ofSeconds(120L));
      gate.acquire(notCancelled()).answered();
      gate.acquire(notCancelled()).refused(null, "HTTP 429");
      assertEquals(60L, this.remainingSeconds(gate), "an answer resets the doubling");
   }

   @Test
   void refusalPausesAreCapped() throws Exception {
      MapTileServerGate gate = this.gate(1, Duration.ZERO);
      for (int refusal = 0; refusal < 10; refusal++) {
         gate.acquire(notCancelled()).refused(null, "HTTP 429");
         this.advance(Duration.ofNanos(gate.status().remainingNanos()));
      }
      gate.acquire(notCancelled()).refused(null, "HTTP 429");
      assertEquals(TimeUnit.NANOSECONDS.toSeconds(MapTileServerGate.MAX_REFUSED_PAUSE_NANOS), this.remainingSeconds(gate));
   }

   @Test
   void retryAfterSetsThePause() throws Exception {
      MapTileServerGate gate = this.gate(2, Duration.ZERO);
      gate.acquire(notCancelled()).refused(Duration.ofSeconds(5L), "HTTP 429");
      assertEquals(5L, this.remainingSeconds(gate));

      this.advance(Duration.ofSeconds(5L));
      assertNotNull(gate.acquire(notCancelled()));
   }

   @Test
   void requestsSentBeforeAPauseDoNotLengthenIt() throws Exception {
      MapTileServerGate gate = this.gate(3, Duration.ZERO);
      MapTileServerGate.Permit first = gate.acquire(notCancelled());
      MapTileServerGate.Permit second = gate.acquire(notCancelled());
      MapTileServerGate.Permit third = gate.acquire(notCancelled());

      first.refused(null, "HTTP 429");
      second.refused(null, "HTTP 429");
      third.failed("timeout");
      assertEquals(60L, this.remainingSeconds(gate));

      MapTileServerGate.Permit fourth;
      this.advance(Duration.ofSeconds(60L));
      fourth = gate.acquire(notCancelled());
      fourth.refused(null, "HTTP 429");
      assertEquals(120L, this.remainingSeconds(gate), "only the refusal after the pause counts as the next one");
   }

   @Test
   void aLaterRetryAfterOfAnEarlierRequestStillExtendsThePause() throws Exception {
      MapTileServerGate gate = this.gate(2, Duration.ZERO);
      MapTileServerGate.Permit first = gate.acquire(notCancelled());
      MapTileServerGate.Permit second = gate.acquire(notCancelled());
      first.refused(null, "HTTP 429");
      second.refused(Duration.ofMinutes(10L), "HTTP 429");
      assertEquals(600L, this.remainingSeconds(gate));
   }

   @Test
   void failuresInARowMarkTheServerUnreachable() throws Exception {
      MapTileServerGate gate = this.gate(1, Duration.ZERO);
      gate.acquire(notCancelled()).failed("timeout");
      gate.acquire(notCancelled()).failed("timeout");
      gate.acquire(notCancelled()).answered();
      gate.acquire(notCancelled()).failed("timeout");
      gate.acquire(notCancelled()).failed("timeout");
      assertNull(gate.status(), "an answer in between resets the count");

      gate.acquire(notCancelled()).failed("timeout");
      MapTileServerGate.PausedException paused = assertThrows(MapTileServerGate.PausedException.class, () -> gate.acquire(notCancelled()));
      assertEquals(MapTileServerGate.PauseReason.UNREACHABLE, paused.reason());
      assertEquals(15L, this.remainingSeconds(gate));

      this.advance(Duration.ofSeconds(15L));
      gate.acquire(notCancelled()).failed("timeout");
      assertEquals(30L, this.remainingSeconds(gate), "a failed first request after a pause pauses again at once");
   }

   @Test
   void tileErrorsDoNotCountAgainstTheServer() throws Exception {
      MapTileServerGate gate = this.gate(1, Duration.ZERO);
      for (int request = 0; request < 10; request++) {
         gate.acquire(notCancelled()).answered();
      }
      for (int request = 0; request < 10; request++) {
         gate.acquire(notCancelled()).close();
      }
      assertNull(gate.status());
   }

   @Test
   void onlyOneRequestRunsUntilTheServerAnswersAgain() throws Exception {
      MapTileServerGate gate = this.gate(4, Duration.ZERO);
      gate.acquire(notCancelled()).refused(Duration.ofSeconds(1L), "HTTP 429");
      this.advance(Duration.ofSeconds(1L));

      MapTileServerGate.Permit probe = gate.acquire(notCancelled());
      assertThrows(InterruptedIOException.class, () -> gate.acquire(cancelledOnceWaiting()));
      probe.answered();

      MapTileServerGate.Permit first = gate.acquire(notCancelled());
      MapTileServerGate.Permit second = gate.acquire(notCancelled());
      first.close();
      second.close();
   }

   @Test
   void concurrentRequestsAreCapped() throws Exception {
      MapTileServerGate gate = this.gate(2, Duration.ZERO);
      MapTileServerGate.Permit first = gate.acquire(notCancelled());
      gate.acquire(notCancelled());
      assertThrows(InterruptedIOException.class, () -> gate.acquire(cancelledOnceWaiting()));

      first.answered();
      assertNotNull(gate.acquire(notCancelled()));
   }

   @Test
   void requestStartsAreSpaced() throws Exception {
      MapTileServerGate gate = this.gate(4, Duration.ofMillis(100L));
      gate.acquire(notCancelled()).answered();
      assertThrows(InterruptedIOException.class, () -> gate.acquire(cancelledOnceWaiting()));

      this.advance(Duration.ofMillis(100L));
      assertNotNull(gate.acquire(notCancelled()));
   }

   @Test
   void aCancelledRequestNeverWaits() {
      MapTileServerGate gate = this.gate(1, Duration.ZERO);
      assertThrows(InterruptedIOException.class, () -> gate.acquire(() -> true));
   }

   @Test
   void statusCodesAreJudgedByWhatTheySayAboutTheServer() {
      assertEquals(MapTileServerGate.Response.ANSWERED, MapTileServerGate.classify(200, false));
      assertEquals(MapTileServerGate.Response.ANSWERED, MapTileServerGate.classify(404, false));
      assertEquals(MapTileServerGate.Response.ANSWERED, MapTileServerGate.classify(400, false));
      assertEquals(MapTileServerGate.Response.REFUSED, MapTileServerGate.classify(429, false));
      assertEquals(MapTileServerGate.Response.REFUSED, MapTileServerGate.classify(403, false));
      assertEquals(MapTileServerGate.Response.REFUSED, MapTileServerGate.classify(418, false));
      assertEquals(MapTileServerGate.Response.REFUSED, MapTileServerGate.classify(503, true));
      assertEquals(MapTileServerGate.Response.FAILED, MapTileServerGate.classify(503, false));
      assertEquals(MapTileServerGate.Response.FAILED, MapTileServerGate.classify(500, false));
      assertEquals(MapTileServerGate.Response.FAILED, MapTileServerGate.classify(408, false));
   }

   @Test
   void retryAfterIsReadInSecondsOrAsADate() {
      Instant now = Instant.parse("2026-11-27T12:00:00Z");
      assertEquals(Duration.ofSeconds(120L), MapTileServerGate.parseRetryAfter("120", now));
      assertEquals(Duration.ofSeconds(30L), MapTileServerGate.parseRetryAfter(" 30 ", now));
      assertEquals(Duration.ofSeconds(1L), MapTileServerGate.parseRetryAfter("0", now));
      assertEquals(Duration.ofHours(1L), MapTileServerGate.parseRetryAfter("86400", now));
      assertEquals(Duration.ofHours(1L), MapTileServerGate.parseRetryAfter("99999999999999999999999", now));

      String inNinetySeconds = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(90L).atOffset(ZoneOffset.UTC));
      assertEquals(Duration.ofSeconds(90L), MapTileServerGate.parseRetryAfter(inNinetySeconds, now));
      String past = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.minusSeconds(90L).atOffset(ZoneOffset.UTC));
      assertEquals(Duration.ofSeconds(1L), MapTileServerGate.parseRetryAfter(past, now));

      assertNull(MapTileServerGate.parseRetryAfter(null, now));
      assertNull(MapTileServerGate.parseRetryAfter("", now));
      assertNull(MapTileServerGate.parseRetryAfter("soon", now));
      assertNull(MapTileServerGate.parseRetryAfter("-5", now));
   }
}
