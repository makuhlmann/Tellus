package com.yucareux.tellus.mixin;

import com.yucareux.tellus.worldgen.EarthChunkGenerator;
import com.yucareux.tellus.worldgen.WaterSurfaceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Disables only the infinite-source conversion that can make DEM-following
 * waterfall sheets fill their surroundings. Normal spreading and downward
 * flow still run through vanilla's FlowingFluid implementation.
 */
@Mixin(FlowingFluid.class)
public class WaterSourceConversionMixin {
   @Unique
   private static final int TELLUS$NOT_READY_RETRY_TICKS = 20;

   @Inject(
      method = "getNewLiquid(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/level/material/FluidState;",
      at = @At("RETURN"),
      cancellable = true
   )
   private void tellus$gateWaterSourceConversion(
      Level level,
      BlockPos pos,
      BlockState state,
      CallbackInfoReturnable<FluidState> cir
   ) {
      FluidState current = level.getFluidState(pos);
      FluidState resolved = cir.getReturnValue();
      if (resolved.isSourceOfType(Fluids.WATER)
         && !current.isSource()
         && current.getType().isSame(Fluids.WATER)
         && level instanceof ServerLevel serverLevel
         && serverLevel.getChunkSource().getGenerator() instanceof EarthChunkGenerator generator) {
         WaterSurfaceResolver.SourceConversion conversion = generator.waterSourceConversion(pos.getX(), pos.getY(), pos.getZ());
         if (conversion != WaterSurfaceResolver.SourceConversion.ALLOW) {
            // Returning the existing flowing state blocks only this attempted
            // conversion. The next vanilla tick still updates and spreads it.
            cir.setReturnValue(current);
            if (conversion == WaterSurfaceResolver.SourceConversion.NOT_READY) {
               // Vanilla reschedules only water whose state changed, so this block would not be asked again; ask once
               // its region has had time to build. A region that cannot be built yet costs one tick here each time.
               serverLevel.scheduleTick(pos, current.getType(), TELLUS$NOT_READY_RETRY_TICKS);
            }
         }
      }
   }
}
