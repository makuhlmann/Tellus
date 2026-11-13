package com.yucareux.tellus.worldgen.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class TrunkStartClearingTest {
   private static final Map<Holder.Reference<Block>, Object> ORIGINAL_TAGS = new HashMap<>();
   private static final BlockPos ORIGIN = new BlockPos(0, 76, 0);
   private static Field tagsField;

   @BeforeAll
   @SuppressWarnings("deprecation") // Binding tags under plain JUnit needs each block's built-in holder.
   static void bootstrapMinecraft() throws Exception {
      assumeFalse(isMinecraftForge(), "Forge's raw JUnit bootstrap cannot initialize vanilla block registries");
      SharedConstants.tryDetectVersion();
      Bootstrap.bootStrap();

      // Plain JUnit does not load data-pack tags: bind the vanilla ones this fixture needs, restored afterwards.
      tagsField = Holder.Reference.class.getDeclaredField("tags");
      tagsField.setAccessible(true);
      Method bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
      bindTags.setAccessible(true);
      for (Block block : BuiltInRegistries.BLOCK) {
         Holder.Reference<Block> holder = block.builtInRegistryHolder();
         ORIGINAL_TAGS.put(holder, tagsField.get(holder));
         List<Object> tags = new ArrayList<>();
         if (block instanceof LeavesBlock) {
            tags.add(BlockTags.LEAVES);
            tags.add(BlockTags.REPLACEABLE_BY_TREES);
         }
         if (block == Blocks.FERN || block == Blocks.LARGE_FERN || block == Blocks.DANDELION || block == Blocks.SEAGRASS) {
            tags.add(BlockTags.REPLACEABLE_BY_TREES);
         }
         bindTags.invoke(holder, tags);
      }
   }

   @AfterAll
   static void restoreTags() throws Exception {
      for (Map.Entry<Holder.Reference<Block>, Object> entry : ORIGINAL_TAGS.entrySet()) {
         tagsField.set(entry.getKey(), entry.getValue());
      }
   }

   @Test
   void aPlantWhereTheTrunkStartsIsCleared() {
      TestWorld world = new TestWorld();
      world.blocks.put(ORIGIN.below(), Blocks.DIRT.defaultBlockState());
      world.blocks.put(ORIGIN, Blocks.FERN.defaultBlockState());
      world.blocks.put(ORIGIN.east(), Blocks.FERN.defaultBlockState());

      TrunkStartClearing.clearPlants(world.level, ORIGIN);

      assertTrue(world.state(ORIGIN).isAir());
      // Only the column the trunk is checked in: the other trunk columns replace their plants themselves.
      assertTrue(world.state(ORIGIN.east()).is(Blocks.FERN));
      assertTrue(world.state(ORIGIN.below()).is(Blocks.DIRT));
   }

   @Test
   void bothHalvesOfATallPlantAreCleared() {
      TestWorld world = new TestWorld();
      world.blocks.put(ORIGIN, Blocks.LARGE_FERN.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.LOWER));
      world.blocks.put(ORIGIN.above(), Blocks.LARGE_FERN.defaultBlockState().setValue(DoublePlantBlock.HALF, DoubleBlockHalf.UPPER));

      TrunkStartClearing.clearPlants(world.level, ORIGIN);

      assertTrue(world.state(ORIGIN).isAir());
      assertTrue(world.state(ORIGIN.above()).isAir());
   }

   @Test
   void clearingStopsAtTheFirstBlockThatIsNotAPlantAndAtTheHeightLimit() {
      TestWorld world = new TestWorld();
      world.blocks.put(ORIGIN.above(), Blocks.DANDELION.defaultBlockState());
      TrunkStartClearing.clearPlants(world.level, ORIGIN);
      assertTrue(world.state(ORIGIN.above()).is(Blocks.DANDELION), "a plant above air is not in the trunk's way");

      for (int i = 0; i <= TrunkStartClearing.MAX_PLANT_HEIGHT; i++) {
         world.blocks.put(ORIGIN.above(i), Blocks.FERN.defaultBlockState());
      }
      TrunkStartClearing.clearPlants(world.level, ORIGIN);
      for (int i = 0; i < TrunkStartClearing.MAX_PLANT_HEIGHT; i++) {
         assertTrue(world.state(ORIGIN.above(i)).isAir());
      }
      assertTrue(world.state(ORIGIN.above(TrunkStartClearing.MAX_PLANT_HEIGHT)).is(Blocks.FERN));
   }

   @Test
   void leavesWaterPlantsAndSolidBlocksAreKept() {
      for (BlockState kept : new BlockState[]{
         Blocks.OAK_LEAVES.defaultBlockState(), Blocks.SEAGRASS.defaultBlockState(), Blocks.STONE.defaultBlockState()
      }) {
         TestWorld world = new TestWorld();
         world.blocks.put(ORIGIN, kept);
         world.blocks.put(ORIGIN.above(), Blocks.FERN.defaultBlockState());
         TrunkStartClearing.clearPlants(world.level, ORIGIN);
         assertEquals(kept, world.state(ORIGIN));
         assertTrue(world.state(ORIGIN.above()).is(Blocks.FERN));
         assertEquals(0, world.writes);
      }
   }

   private static boolean isMinecraftForge() {
      try {
         Class.forName("net.minecraftforge.fml.ModList", false, TrunkStartClearingTest.class.getClassLoader());
         return true;
      } catch (ClassNotFoundException ignored) {
         return false;
      }
   }

   private static final class TestWorld {
      final Map<BlockPos, BlockState> blocks = new HashMap<>();
      int writes;
      final WorldGenLevel level = (WorldGenLevel)Proxy.newProxyInstance(
         WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, (proxy, method, args) -> {
            return switch (method.getName()) {
               case "getBlockState" -> this.state((BlockPos)args[0]);
               case "setBlock" -> {
                  assertEquals(260, args[2]);
                  this.blocks.put(((BlockPos)args[0]).immutable(), (BlockState)args[1]);
                  this.writes++;
                  yield true;
               }
               case "ensureCanWrite" -> true;
               case "getMaxBuildHeight", "getMaxY", "getHeight" -> 320;
               case "getMinBuildHeight", "getMinY" -> -64;
               case "isInsideBuildHeight" -> true;
               case "isOutsideBuildHeight" -> false;
               case "toString" -> "TrunkStartClearingTest.TestWorld";
               default -> throw new UnsupportedOperationException(method.toString());
            };
         }
      );

      BlockState state(BlockPos position) {
         return this.blocks.getOrDefault(position, Blocks.AIR.defaultBlockState());
      }
   }
}
