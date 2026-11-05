package com.yucareux.tellus.world.data.elevation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TellusElevationSourceEndpointTest {
   private static final String KEY = "tellus.test.elevationEndpoint";
   private static final String FALLBACK = "https://tiles.example.org/dem";

   @AfterEach
   void clearProperty() {
      System.clearProperty(KEY);
   }

   @Test
   void usesTheOfficialServerWhenUnset() {
      assertEquals(FALLBACK, TellusElevationSource.endpointProperty(KEY, FALLBACK));
      System.setProperty(KEY, "   ");
      assertEquals(FALLBACK, TellusElevationSource.endpointProperty(KEY, FALLBACK));
   }

   @Test
   void acceptsAMirrorWithOrWithoutATrailingSlash() {
      System.setProperty(KEY, " http://127.0.0.1:8090/mapterhorn/ ");
      assertEquals("http://127.0.0.1:8090/mapterhorn", TellusElevationSource.endpointProperty(KEY, FALLBACK));
      System.setProperty(KEY, "https://mirror.example.org");
      assertEquals("https://mirror.example.org", TellusElevationSource.endpointProperty(KEY, FALLBACK));
   }

   @Test
   void rejectsUrlsThatCannotTakeATilePath() {
      for (String invalid : new String[]{"file:///D:/tiles", "127.0.0.1:8090/tiles", "http://mirror/tiles?key=1", "http://mirror/tiles#z", "not a url"}) {
         System.setProperty(KEY, invalid);
         assertEquals(FALLBACK, TellusElevationSource.endpointProperty(KEY, FALLBACK), invalid);
      }
   }
}
