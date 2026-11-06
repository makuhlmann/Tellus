package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NaturalBiomeVariantsTest {
   @TempDir
   static Path gameDirectory;

   private static HolderLookup<Biome> biomes;
   private static String previousGameDir;
   private static String previousConfigDir;
   private static ClassLoader previousContextClassLoader;

   @BeforeAll
   static void bootstrapMinecraft() {
      previousGameDir = System.getProperty("tellus.gameDir");
      previousConfigDir = System.getProperty("tellus.configDir");
      previousContextClassLoader = Thread.currentThread().getContextClassLoader();
      assumeFalse(
         NaturalBiomeVariantsTest.class.getClassLoader().getResource("net/minecraftforge/fml/ModList.class") != null,
         "Forge's raw JUnit bootstrap cannot initialize vanilla registries; the shared generator is tested on Fabric 1.20.1"
      );
      // NeoForge's JUnit runner has a separate loader; find biome providers through the fixture's.
      Thread.currentThread().setContextClassLoader(NaturalBiomeVariantsTest.class.getClassLoader());
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();
      biomes = com.yucareux.tellus.test.MinecraftTestRegistries.create().lookupOrThrow(Registries.BIOME);
      System.setProperty("tellus.gameDir", gameDirectory.toString());
      System.setProperty("tellus.configDir", gameDirectory.resolve("config").toString());
   }

   @AfterAll
   static void restoreTestEnvironment() {
      Thread.currentThread().setContextClassLoader(previousContextClassLoader);
      restoreProperty("tellus.gameDir", previousGameDir);
      restoreProperty("tellus.configDir", previousConfigDir);
   }

   @Test
   void darkForestKeepsSmallPatchesOfMushroomFieldsAndPaleGarden() {
      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         EarthGeneratorSettings settings = EarthGeneratorSettings.DEFAULT;
         EarthBiomeSource source = new EarthBiomeSource(biomes, settings);
         WaterSurfaceResolver.WaterColumnData dryLand = new WaterSurfaceResolver.WaterColumnData(false, false, 320, 320);
         // From Georgia's Piedmont north into the Blue Ridge: broadleaf forest, whose humid subtropical
         // tree cover is Dark Forest. The coastal plain to the south is conifer forest instead.
         int originX = (int)Math.round(-84.5 * EarthProjection.blocksPerDegree(settings.worldScale()));
         int originZ = (int)Math.round(EarthProjection.latToBlockZ(33.6, settings.worldScale()));
         int tree = MountainSurfaceRules.ESA_TREE_COVER;
         int samples = 0;
         int darkForestFamily = 0;
         int mushroom = 0;
         int pale = 0;
         for (int i = 0; i < 80; i++) {
            for (int j = 0; j < 80; j++) {
               int x = originX + i * 96;
               int z = originZ - j * 96;
               samples++;
               Holder<Biome> biome = source.getBiomeAtBlock(x, z, tree, tree, dryLand, "Cfa");
               mushroom += biome.is(Biomes.MUSHROOM_FIELDS) ? 1 : 0;
               pale += MinecraftVersionCompat.isPaleGarden(biome) ? 1 : 0;
               // Dark Forest, and what takes its place: Flower Forest and the two rare variants.
               darkForestFamily += biome.is(Biomes.DARK_FOREST)
                  || biome.is(Biomes.FLOWER_FOREST)
                  || biome.is(Biomes.MUSHROOM_FIELDS)
                  || MinecraftVersionCompat.isPaleGarden(biome) ? 1 : 0;

               // Only Dark Forest gives way to them.
               Holder<Biome> oceanic = source.getBiomeAtBlock(x, z, tree, tree, dryLand, "Cfb");
               assertFalse(oceanic.is(Biomes.MUSHROOM_FIELDS) || MinecraftVersionCompat.isPaleGarden(oceanic));
               Holder<Biome> grass = source.getBiomeAtBlock(x, z, MountainSurfaceRules.ESA_GRASSLAND, MountainSurfaceRules.ESA_GRASSLAND, dryLand, "Cfa");
               assertFalse(grass.is(Biomes.MUSHROOM_FIELDS) || MinecraftVersionCompat.isPaleGarden(grass));
            }
         }

         assertTrue(darkForestFamily > samples / 2, "Dark Forest samples " + darkForestFamily + " of " + samples);
         double mushroomShare = mushroom / (double)darkForestFamily;
         assertTrue(mushroomShare > 0.005 && mushroomShare < 0.06, "Mushroom Fields share " + mushroomShare);
         double paleShare = pale / (double)darkForestFamily;
         if (MinecraftVersionCompat.defaultRandomBiomeIds().contains("pale_garden")) {
            assertTrue(paleShare > 0.01 && paleShare < 0.1, "Pale Garden share " + paleShare);
         } else {
            assertEquals(0, pale);
         }
      }
   }

   @Test
   void riversFreezeWhereForestsAreSnowyTaiga() {
      for (String code : new String[]{"ET", "EF", "Dfc", "Dfd", "Dwc", "Dwd", "Dsc", "Dsd"}) {
         assertTrue(EarthBiomeSource.isFrozenRiverClimate(code), code);
      }
      for (String code : new String[]{"Dfb", "Dfa", "Dwb", "Dsb", "Cfb", "Cfc", "BWk", "Af"}) {
         assertFalse(EarthBiomeSource.isFrozenRiverClimate(code), code);
      }
      assertFalse(EarthBiomeSource.isFrozenRiverClimate(null));

      try (@SuppressWarnings("unused") ManagedTerrainNetworkPolicy.Scope ignored = ManagedTerrainNetworkPolicy.cacheOnly()) {
         EarthGeneratorSettings settings = EarthGeneratorSettings.DEFAULT;
         EarthBiomeSource source = new EarthBiomeSource(biomes, settings);
         WaterSurfaceResolver.WaterColumnData river = new WaterSurfaceResolver.WaterColumnData(true, false, 60, 64);
         int water = 80;
         // The Lena at Yakutsk, in the coldest subarctic climate, and the Thames in London.
         assertTrue(riverAt(source, settings, 129.73, 62.03, water, river).is(Biomes.FROZEN_RIVER));
         assertTrue(riverAt(source, settings, -0.12, 51.50, water, river).is(Biomes.RIVER));
      }
   }

   @Test
   void naturalVariantsAreDeclaredWithoutRandomBiomes() {
      EarthGeneratorSettings settings = EarthGeneratorSettings.DEFAULT;
      assertFalse(settings.randomBiomes());
      EarthBiomeSource source = new EarthBiomeSource(biomes, settings);
      for (ResourceKey<Biome> key : List.of(Biomes.CHERRY_GROVE, Biomes.MUSHROOM_FIELDS, Biomes.FROZEN_RIVER, Biomes.ERODED_BADLANDS, Biomes.BEACH, Biomes.SNOWY_BEACH)) {
         assertTrue(source.possibleBiomes().stream().anyMatch(biome -> biome.is(key)), key.toString());
      }
   }

   private static Holder<Biome> riverAt(
      EarthBiomeSource source,
      EarthGeneratorSettings settings,
      double longitude,
      double latitude,
      int coverClass,
      WaterSurfaceResolver.WaterColumnData column
   ) {
      int x = (int)Math.round(longitude * EarthProjection.blocksPerDegree(settings.worldScale()));
      int z = (int)Math.round(EarthProjection.latToBlockZ(latitude, settings.worldScale()));
      return source.getBiomeAtBlock(x, z, coverClass, coverClass, column, null);
   }

   private static void restoreProperty(String key, String value) {
      if (value == null) {
         System.clearProperty(key);
      } else {
         System.setProperty(key, value);
      }
   }
}
