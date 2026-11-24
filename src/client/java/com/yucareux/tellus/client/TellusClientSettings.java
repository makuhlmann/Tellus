package com.yucareux.tellus.client;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.platform.TellusPlatform;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Client settings kept between game sessions, in {@code config/tellus-client.properties}. */
public final class TellusClientSettings {
   private static final String FILE_NAME = "tellus-client.properties";
   private static final String MINIMAP = "minimap";
   private static Properties properties;

   private TellusClientSettings() {
   }

   public static synchronized boolean minimapEnabled() {
      return Boolean.parseBoolean(load().getProperty(MINIMAP, "false"));
   }

   public static synchronized void setMinimapEnabled(boolean enabled) {
      load().setProperty(MINIMAP, Boolean.toString(enabled));
      save();
   }

   private static Properties load() {
      if (properties == null) {
         properties = new Properties();
         Path file = file();
         if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
               properties.load(reader);
            } catch (IOException | IllegalArgumentException error) {
               Tellus.LOGGER.warn("Could not read {}; using default client settings", file, error);
            }
         }
      }
      return properties;
   }

   private static void save() {
      Path file = file();
      try {
         Files.createDirectories(file.getParent());
         try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            properties.store(writer, "Tellus client settings");
         }
      } catch (IOException error) {
         Tellus.LOGGER.warn("Could not save {}", file, error);
      }
   }

   private static Path file() {
      return TellusPlatform.configDir().resolve(FILE_NAME);
   }
}
