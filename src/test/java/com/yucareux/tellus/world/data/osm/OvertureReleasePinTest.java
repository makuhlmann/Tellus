package com.yucareux.tellus.world.data.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OvertureReleasePinTest {
   private static final List<String> PUBLISHED = List.of("2026-09-23.0", "2026-09-23.1", "2026-10-21.0");

   @TempDir
   Path worldRoot;

   @Test
   void keepsARecordedReleaseWhileItIsPublished() {
      OvertureReleasePin.Decision decision = OvertureReleasePin.decide("2026-09-23.0", PUBLISHED, "2026-01-01.0");
      assertEquals("2026-09-23.0", decision.release());
      assertFalse(decision.writeRecord());
      assertNull(decision.replacedRelease());
   }

   @Test
   void movesToTheNewestReleaseOnceTheRecordedOneIsWithdrawn() {
      OvertureReleasePin.Decision decision = OvertureReleasePin.decide("2026-06-17.0", PUBLISHED, "2026-01-01.0");
      assertEquals("2026-10-21.0", decision.release());
      assertTrue(decision.writeRecord());
      assertEquals("2026-06-17.0", decision.replacedRelease());
   }

   @Test
   void keepsARecordedReleaseWhenTheListingIsUnavailable() {
      OvertureReleasePin.Decision decision = OvertureReleasePin.decide("2026-06-17.0", null, "2026-01-01.0");
      assertEquals("2026-06-17.0", decision.release());
      assertFalse(decision.writeRecord());
   }

   @Test
   void recordsTheNewestReleaseForAWorldWithoutOne() {
      OvertureReleasePin.Decision listed = OvertureReleasePin.decide(null, PUBLISHED, "2026-01-01.0");
      assertEquals("2026-10-21.0", listed.release());
      assertTrue(listed.writeRecord());

      OvertureReleasePin.Decision unlisted = OvertureReleasePin.decide(null, null, "2026-01-01.0");
      assertEquals("2026-01-01.0", unlisted.release());
      assertTrue(unlisted.writeRecord());
   }

   @Test
   void ordersReleasesByDateThenSequenceNumber() {
      List<String> releases = new java.util.ArrayList<>(List.of("2026-10-21.10", "2026-10-21.9", "2026-09-23.1", "2026-10-21.0"));
      releases.sort(OvertureTileUrls::compareReleases);
      assertEquals(List.of("2026-09-23.1", "2026-10-21.0", "2026-10-21.9", "2026-10-21.10"), releases);
   }

   @Test
   void writesTheRecordWithoutDroppingOtherFields() throws Exception {
      Path recordFile = this.worldRoot.resolve(OvertureReleasePin.RECORD_FILE);
      Files.createDirectories(recordFile.getParent());
      Files.writeString(recordFile, "{\"note\": \"kept\", \"overtureRelease\": \"2026-09-23.0\"}", StandardCharsets.UTF_8);
      assertEquals("2026-09-23.0", OvertureReleasePin.readRecordedRelease(recordFile));

      OvertureReleasePin.writeRecordedRelease(recordFile, "2026-10-21.0");
      assertEquals("2026-10-21.0", OvertureReleasePin.readRecordedRelease(recordFile));
      assertTrue(Files.readString(recordFile, StandardCharsets.UTF_8).contains("\"note\": \"kept\""));
   }

   @Test
   void ignoresRecordsThatAreNotReleaseIds() throws Exception {
      Path recordFile = this.worldRoot.resolve(OvertureReleasePin.RECORD_FILE);
      Files.createDirectories(recordFile.getParent());
      Files.writeString(recordFile, "{\"overtureRelease\": \"../../somewhere\"}", StandardCharsets.UTF_8);
      assertNull(OvertureReleasePin.readRecordedRelease(recordFile));
      Files.writeString(recordFile, "not json {", StandardCharsets.UTF_8);
      assertNull(OvertureReleasePin.readRecordedRelease(recordFile));
      assertNull(OvertureReleasePin.readRecordedRelease(this.worldRoot.resolve("missing.json")));
   }
}
