package com.yucareux.tellus.world.data.elevation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TellusElevationSourceArchiveMirrorTest {
   @TempDir
   Path gameDirectory;

   @BeforeAll
   static void bootstrapMinecraft() {
      // Building the source loads TellusCommon, whose registry keys need vanilla's bootstrap.
      assumeFalse(
         TellusElevationSourceArchiveMirrorTest.class.getClassLoader().getResource("net/minecraftforge/fml/ModList.class") != null,
         "Forge's raw JUnit bootstrap cannot initialize vanilla registries"
      );
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
   }

   @Test
   void onlyTilesTheArchivesLackLeaveMissingMarkers() throws Exception {
      String previousGameDir = System.getProperty("tellus.gameDir");
      System.setProperty("tellus.gameDir", this.gameDirectory.toString());
      try (PmTilesTestServer server = new PmTilesTestServer()) {
         server.serve(
            "planet.pmtiles",
            // Like Mapterhorn's planet archive, it ends at zoom 12; zoom 13 comes from the squares' archives.
            PmTilesTestServer.archive(0, 12, PmTilesTestServer.COMPRESSION_NONE, Map.of(new int[]{6, 10, 20}, "unused".getBytes(StandardCharsets.US_ASCII)))
         );
         TellusElevationSource source = new TellusElevationSource();
         Field archives = TellusElevationSource.class.getDeclaredField("mapterhornArchives");
         archives.setAccessible(true);
         archives.set(source, new MapterhornArchiveMirror(server.folderUrl(), 2000, 2000));

         Class<?> keyClass = Class.forName(TellusElevationSource.class.getName() + "$TileKey");
         Constructor<?> keyConstructor = keyClass.getDeclaredConstructor(int.class, int.class, int.class);
         keyConstructor.setAccessible(true);
         Method loadTile = TellusElevationSource.class.getDeclaredMethod("loadTile", keyClass);
         loadTile.setAccessible(true);
         Method cachePathOf = TellusElevationSource.class.getDeclaredMethod("cachePath", keyClass);
         cachePathOf.setAccessible(true);
         Field missingRasterField = TellusElevationSource.class.getDeclaredField("MISSING_RASTER");
         missingRasterField.setAccessible(true);
         Object missingRaster = missingRasterField.get(null);

         // No archive for this square: missing now, but it may still be copied into the mirror.
         Object notMirrored = keyConstructor.newInstance(13, 4000, 3000);
         assertSame(missingRaster, loadTile.invoke(source, notMirrored));
         Path notMirroredPath = (Path)cachePathOf.invoke(source, notMirrored);
         assertFalse(Files.exists(notMirroredPath.resolveSibling(notMirroredPath.getFileName() + ".missing")));

         // The planet archive lacks this tile, as the tile server would answer with 404: remembered on disk.
         Object absent = keyConstructor.newInstance(6, 11, 20);
         loadTile.invoke(source, absent);
         Path absentPath = (Path)cachePathOf.invoke(source, absent);
         assertTrue(Files.exists(absentPath.resolveSibling(absentPath.getFileName() + ".missing")));
      } finally {
         if (previousGameDir == null) {
            System.clearProperty("tellus.gameDir");
         } else {
            System.setProperty("tellus.gameDir", previousGameDir);
         }
      }
   }
}
