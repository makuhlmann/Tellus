package com.yucareux.tellus.preload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.yucareux.tellus.worldgen.EarthGeneratorSettings;
import org.junit.jupiter.api.Test;

class TerrainPreloadStorageTest {
   @Test
   void rejectsIdentifiersThatCouldEscapeTheCacheRoot() {
      assertTrue(TerrainPreloadStorage.isValidIdentifier("preload-1234_test.v2"));
      assertFalse(TerrainPreloadStorage.isValidIdentifier("../../outside"));
      assertFalse(TerrainPreloadStorage.isValidIdentifier(".."));
      assertThrows(
         IllegalArgumentException.class,
         () -> TerrainPreloadStorage.instance().publishedDirectory("../../outside")
      );
   }

   @Test
   void mapEdgeWrapLeavesPackageFingerprintsUnchanged() {
      TerrainPreloadStorage storage = TerrainPreloadStorage.instance();
      JsonObject current = storage.encodeSettings(EarthGeneratorSettings.DEFAULT).getAsJsonObject();
      assertTrue(current.has("map_edge_wrap"));
      // A package built before the setting existed stored settings without the key.
      JsonObject beforeTheSetting = current.deepCopy();
      beforeTheSetting.remove("map_edge_wrap");
      JsonObject turnedOff = current.deepCopy();
      turnedOff.addProperty("map_edge_wrap", false);

      String fingerprint = storage.settingsFingerprint(beforeTheSetting);
      assertEquals(fingerprint, storage.settingsFingerprint(current));
      assertEquals(fingerprint, storage.settingsFingerprint(turnedOff));

      JsonObject otherWorld = current.deepCopy();
      otherWorld.addProperty("world_scale", 17.0);
      assertNotEquals(fingerprint, storage.settingsFingerprint(otherWorld));
   }
}
