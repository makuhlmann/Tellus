package com.yucareux.tellus.world.edge;

import com.yucareux.tellus.worldgen.MapEdgeWrap;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.border.WorldBorder;

/**
 * Keeps the places vanilla keeps inside a world border also inside the map. Vanilla clamps every portal destination
 * (and every new portal) to the destination's world border; in an Earth level with edge wrap that clamp also keeps it
 * a few blocks away from the map edges, so a Nether portal far out (at 1:1000 the map edge is only 2,504 blocks out in
 * the Nether) leads into the map instead of into the empty chunks past it, and its new portal is built inside the map.
 */
public final class MapEdgeBorders {
   private static final Map<WorldBorder, MapEdgeWrap> WRAPS = Collections.synchronizedMap(new WeakHashMap<>());

   private MapEdgeBorders() {
   }

   /** Called for each level as it loads; levels without a map edge are forgotten. */
   public static void register(WorldBorder border, MapEdgeWrap wrap) {
      if (wrap.enabled()) {
         WRAPS.put(border, wrap);
      } else {
         WRAPS.remove(border);
      }
   }

   /** {@code pos} kept inside the map of the level owning {@code border}, or {@code null} when it already is. */
   public static BlockPos clamp(WorldBorder border, BlockPos pos) {
      MapEdgeWrap wrap = WRAPS.get(border);
      return wrap == null ? null : clamp(wrap, pos);
   }

   static BlockPos clamp(MapEdgeWrap wrap, BlockPos pos) {
      int x = Mth.floor(wrap.clampAwayFromEdgeX(pos.getX() + 0.5));
      int z = Mth.floor(wrap.clampAwayFromEdgeZ(pos.getZ() + 0.5));
      return x == pos.getX() && z == pos.getZ() ? null : new BlockPos(x, pos.getY(), z);
   }
}
