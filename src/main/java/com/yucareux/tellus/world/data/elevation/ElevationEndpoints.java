package com.yucareux.tellus.world.data.elevation;

import com.yucareux.tellus.Tellus;
import java.net.URI;

/** Reads the {@code -D} properties that point elevation sources at a mirror instead of the official servers. */
final class ElevationEndpoints {
   private ElevationEndpoints() {
   }

   /** The http(s) URL that {@code key} names, without a trailing slash; {@code fallback} when unset or invalid. */
   static String urlProperty(String key, String fallback) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return fallback;
      }

      String url = value.trim();
      while (url.endsWith("/")) {
         url = url.substring(0, url.length() - 1);
      }
      try {
         URI uri = URI.create(url);
         String scheme = uri.getScheme();
         if (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
            && uri.getHost() != null
            && uri.getRawQuery() == null
            && uri.getRawFragment() == null) {
            Tellus.LOGGER.info("Reading elevation data for {} from {}", key, url);
            return url;
         }
      } catch (IllegalArgumentException ignored) {
         // Reported below.
      }

      Tellus.LOGGER.warn(
         "Ignoring invalid {}='{}', which must be an http(s) URL without a query; {}",
         key,
         value,
         fallback == null ? "the setting has no effect" : "using " + fallback
      );
      return fallback;
   }
}
