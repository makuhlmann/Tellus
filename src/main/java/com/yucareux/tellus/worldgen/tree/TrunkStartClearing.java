package com.yucareux.tellus.worldgen.tree;

import com.yucareux.tellus.compat.MinecraftVersionCompat;
import com.yucareux.tellus.worldgen.MapEdgeWrites;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Clears small plants from where a vanilla tree is about to start its trunk.
 *
 * <p>Tellus places trees after the biome's other features, so grass, flowers and leaf litter are already on the
 * ground; vanilla places trees first. Most trunks replace such plants, but the dark and pale oak trunk skips every
 * layer whose first column is not air or leaves, so a single grass tuft there left the whole tree a block above the
 * ground. Clearing the column first gives the same tree as vanilla's order would.</p>
 */
public final class TrunkStartClearing {
   /** Double plants (tall grass, large fern, sunflowers and the like) are the tallest plants trees may replace. */
   static final int MAX_PLANT_HEIGHT = 2;
   private static final int PLACEMENT_FLAGS = 260;

   private TrunkStartClearing() {
   }

   /** Removes the plants trees may replace from {@code origin} upward, up to {@link #MAX_PLANT_HEIGHT} blocks. */
   public static void clearPlants(WorldGenLevel level, BlockPos origin) {
      BlockPos.MutableBlockPos cursor = origin.mutable();
      for (int i = 0; i < MAX_PLANT_HEIGHT; i++) {
         if (!MinecraftVersionCompat.isInsideBuildHeight(level, cursor) || !MapEdgeWrites.canWrite(level, cursor)) {
            return;
         }
         BlockState state = level.getBlockState(cursor);
         if (!isReplaceablePlant(state)) {
            return;
         }
         level.setBlock(cursor, Blocks.AIR.defaultBlockState(), PLACEMENT_FLAGS);
         cursor.move(0, 1, 0);
      }
   }

   private static boolean isReplaceablePlant(BlockState state) {
      // Leaves already let a trunk through, and water-filled blocks would leave a hole in a lake or river.
      return !state.isAir()
         && !state.is(BlockTags.LEAVES)
         && state.getFluidState().isEmpty()
         && state.is(BlockTags.REPLACEABLE_BY_TREES);
   }
}
