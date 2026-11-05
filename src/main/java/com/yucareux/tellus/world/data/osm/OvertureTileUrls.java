package com.yucareux.tellus.world.data.osm;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.source.InputStreamSafety;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public final class OvertureTileUrls {
   private static final String EXTRAS_BUCKET_URL = "https://overturemaps-extras-us-west-2.s3.us-west-2.amazonaws.com";
   private static final String RELEASE_PROPERTY = "tellus.overture.release";
   // Overture keeps only its last few releases online, so this is a last resort: the newest release an earlier
   // session listed (LAST_KNOWN_RELEASE_FILE) comes first, and a world keeps the release it recorded.
   static final String FALLBACK_RELEASE = "2026-10-21.0";
   private static final String LAST_KNOWN_RELEASE_FILE = "tellus/cache/overture-release-latest.txt";
   private static final int MAX_RELEASE_LISTING_BYTES = 4 * 1024 * 1024;
   private static final Pattern RELEASE_PREFIX_PATTERN = Pattern.compile("tiles/(\\d{4}-\\d{2}-\\d{2}\\.\\d+)/");
   private static final Pattern RELEASE_ID_PATTERN = Pattern.compile("\\d{4}-\\d{2}-\\d{2}\\.\\d+");
   private static volatile String latestRelease;
   private static volatile String worldRelease;

   private OvertureTileUrls() {
   }

   public static String defaultThemeUrl(String theme) {
      String safeTheme = validateTheme(theme);
      return themeUrl(resolvedRelease(), safeTheme);
   }

   /** The archive a source reads: the URL its own property names, else the theme in the release now in use. */
   public static String configuredThemeUrl(String urlProperty, String theme) {
      String configured = System.getProperty(urlProperty);
      return configured == null || configured.isBlank() ? defaultThemeUrl(theme) : configured;
   }

   /** The {@code tellus.overture.release} property, else the release of the world being played, else the newest. */
   public static String resolvedRelease() {
      String release = configuredRelease();
      if (release != null) {
         return release;
      }

      String bound = worldRelease;
      return bound != null ? bound : latestRelease();
   }

   /** Makes {@code release} the one sources read; they switch to it when {@link OvertureReleasePin} rebinds them. */
   static void bindWorldRelease(String release) {
      if (!isValidRelease(release)) {
         throw new IllegalArgumentException("Invalid Overture release " + release);
      }

      worldRelease = release;
   }

   static boolean isValidRelease(String release) {
      return release != null && RELEASE_ID_PATTERN.matcher(release).matches();
   }

   /** Orders valid release ids oldest first: by date, then by sequence number, so {@code .10} follows {@code .9}. */
   static int compareReleases(String left, String right) {
      int dateOrder = left.substring(0, 10).compareTo(right.substring(0, 10));
      if (dateOrder != 0) {
         return dateOrder;
      }

      String leftSequence = left.substring(11);
      String rightSequence = right.substring(11);
      return leftSequence.length() != rightSequence.length()
         ? Integer.compare(leftSequence.length(), rightSequence.length())
         : leftSequence.compareTo(rightSequence);
   }

   public static String cacheNamespace(String url) {
      String safeUrl = Objects.requireNonNull(url, "url");
      return Integer.toUnsignedString(safeUrl.hashCode(), 36);
   }

   private static String themeUrl(String release, String theme) {
      return EXTRAS_BUCKET_URL + "/tiles/" + release + "/" + theme + ".pmtiles";
   }

   static String configuredRelease() {
      String configured = System.getProperty(RELEASE_PROPERTY);
      if (configured == null || configured.isBlank()) {
         return null;
      }

      String release = configured.trim();
      if (!RELEASE_ID_PATTERN.matcher(release).matches()) {
         Tellus.LOGGER.warn("Ignoring invalid Overture release '{}'", release);
         return null;
      }

      return release;
   }

   private static String latestRelease() {
      String cached = latestRelease;
      if (cached != null) {
         return cached;
      }

      synchronized (OvertureTileUrls.class) {
         cached = latestRelease;
         if (cached != null) {
            return cached;
         }

         List<String> published = listPublishedReleases();
         if (published != null) {
            cached = published.get(published.size() - 1);
         } else {
            cached = fallbackRelease();
            Tellus.LOGGER.warn("Using Overture tiles release {} until the release listing can be read", cached);
         }

         latestRelease = cached;
         return cached;
      }
   }

   /**
    * The releases Overture publishes now, oldest first, or null when the listing cannot be read. A successful
    * listing also becomes the newest release this game remembers for sessions that cannot read it.
    */
   static List<String> listPublishedReleases() {
      try {
         List<String> published = fetchPublishedReleases();
         String newest = published.get(published.size() - 1);
         latestRelease = newest;
         rememberLatestRelease(newest);
         return published;
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.warn("Failed to list the published Overture tiles releases", error);
         return null;
      }
   }

   /** The release to read when the listing cannot be: the newest an earlier session listed, else the built-in one. */
   static String fallbackRelease() {
      String lastKnown = lastKnownRelease();
      return lastKnown != null ? lastKnown : FALLBACK_RELEASE;
   }

   static String lastKnownRelease() {
      Path path = TellusPlatform.gameDir().resolve(LAST_KNOWN_RELEASE_FILE);
      try {
         if (!Files.isRegularFile(path) || Files.size(path) > 64L) {
            return null;
         }

         String release = Files.readString(path, StandardCharsets.UTF_8).trim();
         return isValidRelease(release) ? release : null;
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.debug("Failed to read the last known Overture release from {}", path, error);
         return null;
      }
   }

   private static void rememberLatestRelease(String release) {
      if (release.equals(lastKnownRelease())) {
         return;
      }

      Path path = TellusPlatform.gameDir().resolve(LAST_KNOWN_RELEASE_FILE);
      try {
         TellusCacheFiles.writeBytes(path, release.getBytes(StandardCharsets.UTF_8));
      } catch (IOException | RuntimeException error) {
         Tellus.LOGGER.debug("Failed to remember the latest Overture release in {}", path, error);
      }
   }

   private static List<String> fetchPublishedReleases() throws IOException {
      URI uri = URI.create(EXTRAS_BUCKET_URL + "/?list-type=2&prefix=tiles/&delimiter=/");
      HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();
      try {
         connection.setConnectTimeout(intProperty("tellus.overture.releaseLookup.connectTimeoutMs", 7000, 1, 120000));
         connection.setReadTimeout(intProperty("tellus.overture.releaseLookup.readTimeoutMs", 20000, 1, 180000));
         int responseCode = connection.getResponseCode();
         if (responseCode != 200) {
            throw new IOException("Overture release listing HTTP error " + responseCode);
         }
         long contentLength = connection.getContentLengthLong();
         if (contentLength > MAX_RELEASE_LISTING_BYTES) {
            throw new IOException("Overture release listing exceeds the safety limit");
         }

         try (InputStream input = connection.getInputStream()) {
            byte[] response = InputStreamSafety.readAllBytes(
               input, MAX_RELEASE_LISTING_BYTES, "Overture release listing"
            );
            Document document = newDocumentBuilderFactory().newDocumentBuilder().parse(new ByteArrayInputStream(response));
            NodeList prefixes = document.getElementsByTagName("Prefix");
            TreeSet<String> releases = new TreeSet<>(OvertureTileUrls::compareReleases);
            for (int i = 0; i < prefixes.getLength(); i++) {
               String prefix = prefixes.item(i).getTextContent();
               Matcher matcher = RELEASE_PREFIX_PATTERN.matcher(prefix);
               if (matcher.matches()) {
                  releases.add(matcher.group(1));
               }
            }

            if (releases.isEmpty()) {
               throw new IOException("No Overture tile releases found");
            }

            return List.copyOf(releases);
         } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Failed to parse Overture release listing", error);
         }
      } finally {
         connection.disconnect();
      }
   }

   private static DocumentBuilderFactory newDocumentBuilderFactory() throws ParserConfigurationException {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
      factory.setExpandEntityReferences(false);
      factory.setXIncludeAware(false);
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
      factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
      return factory;
   }

   private static String validateTheme(String theme) {
      String safeTheme = Objects.requireNonNull(theme, "theme").toLowerCase(Locale.ROOT);
      if (!safeTheme.matches("[a-z0-9_-]+")) {
         throw new IllegalArgumentException("Invalid Overture PMTiles theme " + theme);
      }

      return safeTheme;
   }

   private static int intProperty(String key, int defaultValue, int minInclusive, int maxInclusive) {
      String value = System.getProperty(key);
      if (value == null || value.isBlank()) {
         return defaultValue;
      }

      try {
         return Math.max(minInclusive, Math.min(maxInclusive, Integer.parseInt(value.trim())));
      } catch (NumberFormatException error) {
         Tellus.LOGGER.warn("Invalid integer system property {}={}, using {}", key, value, defaultValue);
         return defaultValue;
      }
   }
}
