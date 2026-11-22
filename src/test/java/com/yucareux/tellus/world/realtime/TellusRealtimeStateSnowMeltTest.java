package com.yucareux.tellus.world.realtime;

import java.util.Arrays;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TellusRealtimeStateSnowMeltTest {
   private static final BlockPos POS = new BlockPos(100, 64, -100);
   private static final int SPACING_BLOCKS = 1024;

   @AfterEach
   void resetState() {
      TellusRealtimeState.reset();
   }

   @Test
   void warmAirMeltsSnowWithoutRealTimeWeather() {
      TellusRealtimeState.updateWeatherState(false, TellusRealtimeState.PrecipitationMode.CLEAR, false, SnowGrid.empty(), uniformTemperature(6.0F));
      assertEquals(0.5F, TellusRealtimeState.snowMeltChance(POS));
   }

   @Test
   void coldAirKeepsSnow() {
      TellusRealtimeState.updateWeatherState(false, TellusRealtimeState.PrecipitationMode.CLEAR, false, SnowGrid.empty(), uniformTemperature(1.0F));
      assertEquals(0.0F, TellusRealtimeState.snowMeltChance(POS));
   }

   @Test
   void realTimeSnowfallKeepsTheSnowItLays() {
      TellusRealtimeState.updateWeatherState(true, TellusRealtimeState.PrecipitationMode.SNOW, false, SnowGrid.empty(), uniformTemperature(6.0F));
      assertEquals(0.0F, TellusRealtimeState.snowMeltChance(POS));
   }

   @Test
   void recentSnowCoverKeepsItsSnow() {
      float[] deepSnow = new float[SnowGrid.GRID_POINTS];
      Arrays.fill(deepSnow, 1.0F);
      SnowGrid snowGrid = new SnowGrid(0, 0, SPACING_BLOCKS, deepSnow);
      TellusRealtimeState.updateWeatherState(true, TellusRealtimeState.PrecipitationMode.RAIN, true, snowGrid, uniformTemperature(6.0F));
      assertEquals(0.0F, TellusRealtimeState.snowMeltChance(POS));
   }

   @Test
   void realTimeRainMeltsSnowWhereNoneLies() {
      TellusRealtimeState.updateWeatherState(true, TellusRealtimeState.PrecipitationMode.RAIN, true, new SnowGrid(0, 0, SPACING_BLOCKS, null), uniformTemperature(6.0F));
      assertEquals(0.5F, TellusRealtimeState.snowMeltChance(POS));
   }

   @Test
   void noTemperatureMeltsNoSnow() {
      assertEquals(0.0F, TellusRealtimeState.snowMeltChance(POS));
   }

   private static TemperatureGrid uniformTemperature(float temperatureC) {
      float[] temperatures = new float[TemperatureGrid.GRID_POINTS];
      Arrays.fill(temperatures, temperatureC);
      return new TemperatureGrid(0, 0, SPACING_BLOCKS, temperatures, System.currentTimeMillis());
   }
}
