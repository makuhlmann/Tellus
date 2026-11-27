package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.caves.TellusCaveDepthMapper;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.PlacementContext;

/** Shared surface-relative underground placement, independent of mixin signatures. */
public final class TellusHeightRangePlacement {
   private static final Predicate<BlockState> IS_BEDROCK = state -> state.is(Blocks.BEDROCK);

   private TellusHeightRangePlacement() {}

   /** Returns null when vanilla should handle this placement. */
   public static Stream<BlockPos> positions(HeightProvider height, PlacementContext context, RandomSource random, BlockPos origin) {
      if (!(context.generator() instanceof EarthChunkGenerator earthGenerator)
         || !earthGenerator.settings().usesTerrainShell()) {
         return null;
      }

      WorldGenerationContext vanillaContext = new WorldGenerationContext(
         context.generator(),
         LevelHeightAccessor.create(
            TellusCaveDepthMapper.VANILLA_MIN_Y,
            TellusCaveDepthMapper.VANILLA_MAX_Y - TellusCaveDepthMapper.VANILLA_MIN_Y + 1
         )
      ) {
         @Override
         public int getMinGenY() {
            return TellusCaveDepthMapper.VANILLA_MIN_Y;
         }

         @Override
         public int getGenDepth() {
            return TellusCaveDepthMapper.VANILLA_MAX_Y - TellusCaveDepthMapper.VANILLA_MIN_Y + 1;
         }
      };
      int actualSurfaceY = earthGenerator.getUndergroundPlacementSurfaceY(origin.getX(), origin.getZ());
      int actualBottomY = findUsableUndergroundBottom(context, earthGenerator, origin, actualSurfaceY);
      int virtualSurfaceY = TellusCaveDepthMapper.virtualSurfaceForTellusColumn(
         actualSurfaceY, earthGenerator.getSeaLevel()
      );
      UndergroundFeatureClassifier.Kind featureKind = context.topFeature()
         .map(UndergroundFeatureClassifier::classify)
         .orElse(UndergroundFeatureClassifier.Kind.OTHER);
      if (featureKind == UndergroundFeatureClassifier.Kind.MINEABLE_ORE && !earthGenerator.settings().oreDistribution()
         || featureKind == UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE
            && !earthGenerator.settings().geologicalStonePatches()) {
         return Stream.empty();
      }

      int sampleCount = featureKind == UndergroundFeatureClassifier.Kind.MINEABLE_ORE
         ? OrePlacementDensityPolicy.placementSamples(
            actualSurfaceY - actualBottomY,
            virtualSurfaceY - TellusCaveDepthMapper.VANILLA_MIN_Y,
            random
         )
         : 1;
      int minimumNearbySurfaceY = featureKind == UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE
         ? minimumNearbySurfaceY(earthGenerator, origin, GeologicalStonePlacementPolicy.BLOB_SURFACE_SAMPLE_RADIUS)
         : Integer.MAX_VALUE;
      Stream.Builder<BlockPos> positions = Stream.builder();
      for (int sample = 0; sample < sampleCount; sample++) {
         int virtualY = height.sample(random, vanillaContext);
         int actualY = TellusCaveDepthMapper.actualYForVirtualFeature(
            virtualY, virtualSurfaceY, actualSurfaceY, actualBottomY
         );
         if (featureKind == UndergroundFeatureClassifier.Kind.GEOLOGICAL_STONE) {
            actualY = GeologicalStonePlacementPolicy.safeBlobOriginY(
               actualY, minimumNearbySurfaceY, actualBottomY
            );
         }
         if (actualY != Integer.MIN_VALUE
            && !earthGenerator.isUndergroundStructureFeaturePlacementBlocked(
               origin.getX(), actualY, origin.getZ()
            )) {
            positions.add(origin.atY(actualY));
         }
      }
      return positions.build();
   }

   private static int minimumNearbySurfaceY(EarthChunkGenerator generator, BlockPos origin, int radius) {
      int minimumSurfaceY = Integer.MAX_VALUE;
      for (int dz = -radius; dz <= radius; dz++) {
         for (int dx = -radius; dx <= radius; dx++) {
            minimumSurfaceY = Math.min(
               minimumSurfaceY,
               generator.getUndergroundPlacementSurfaceY(origin.getX() + dx, origin.getZ() + dz)
            );
         }
      }
      return minimumSurfaceY;
   }

   /** Just above the highest bedrock below the surface in the origin's column, or the deepest generated Y without one. */
   private static int findUsableUndergroundBottom(
      PlacementContext context,
      EarthChunkGenerator generator,
      BlockPos origin,
      int surfaceY
   ) {
      int searchBottom = UndergroundGenerationDepthPolicy.deepestGenerationY(
         surfaceY,
         generator.settings().undergroundDepth(),
         context.getLevel().dimensionType().minY()
      );
      // This runs for every ore and stone placement, hundreds per chunk on the single worldgen queue, so the chunk is
      // looked up once and only sections whose palette can hold bedrock are read block by block. Heights outside the
      // chunk read as void air, never bedrock, so clamping to it finds the same block. getSections() is the real
      // chunk's array even for a read-only ImposterProtoChunk around a finished chunk.
      ChunkAccess chunk = context.getLevel().getChunk(origin);
      LevelChunkSection[] sections = chunk.getSections();
      int chunkMinY = MinecraftVersionCompat.minY(chunk);
      int bottom = Math.max(searchBottom, chunkMinY);
      int localX = origin.getX() & 15;
      int localZ = origin.getZ() & 15;
      int y = Math.min(surfaceY - 1, chunkMinY + chunk.getHeight() - 1);
      while (y >= bottom) {
         int sectionIndex = chunk.getSectionIndex(y);
         LevelChunkSection section = sections[sectionIndex];
         int sectionMinY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(sectionIndex));
         int sectionStop = Math.max(bottom, sectionMinY);
         if (section.maybeHas(IS_BEDROCK)) {
            for (; y >= sectionStop; y--) {
               if (section.getBlockState(localX, y & 15, localZ).is(Blocks.BEDROCK)) {
                  return Math.min(surfaceY - 1, y + 1);
               }
            }
         } else {
            y = sectionStop - 1;
         }
      }
      return searchBottom;
   }
}
