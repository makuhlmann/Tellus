package com.yucareux.tellus.mixin;

import com.yucareux.tellus.world.edge.MapEdgeBorders;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.border.WorldBorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Portal destinations and new portals are clamped to the destination's world border; in an Earth level with edge
 * wrap they are also kept inside the map (see {@link MapEdgeBorders}). Every {@code clampToBounds} overload goes
 * through this one in all supported versions.
 */
@Mixin(WorldBorder.class)
public abstract class WorldBorderMapEdgeMixin {
   @Inject(method = "clampToBounds(DDD)Lnet/minecraft/core/BlockPos;", at = @At("RETURN"), cancellable = true)
   private void tellus$keepInsideMapEdge(double x, double y, double z, CallbackInfoReturnable<BlockPos> cir) {
      BlockPos inside = MapEdgeBorders.clamp((WorldBorder)(Object)this, cir.getReturnValue());
      if (inside != null) {
         cir.setReturnValue(inside);
      }
   }
}
