package com.yucareux.tellus.world.realtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeatherTemperaturePolicyTest {
   @Test
   void snowsAtTheConfiguredThreshold() {
      assertTrue(WeatherTemperaturePolicy.shouldSnow(2.0F));
   }

   @Test
   void rainsAboveTheConfiguredThreshold() {
      assertFalse(WeatherTemperaturePolicy.shouldSnow(2.1F));
   }

   @Test
   void missingTemperatureDoesNotForceSnow() {
      assertFalse(WeatherTemperaturePolicy.shouldSnow(Float.NaN));
   }

   @Test
   void lakeFreezesAtThePhysicalFreezingPointAfterFreezingDays() {
      assertTrue(WeatherTemperaturePolicy.canLakeFreeze(0.0F, 0.0F));
      assertFalse(WeatherTemperaturePolicy.canLakeFreeze(0.1F, -10.0F));
   }

   @Test
   void coldNightAfterMildDaysDoesNotFreezeALake() {
      assertFalse(WeatherTemperaturePolicy.canLakeFreeze(-5.0F, 3.0F));
   }

   @Test
   void lakeIceMeltsOnlyAfterDaysAboveFreezing() {
      assertTrue(WeatherTemperaturePolicy.canLakeIceMelt(0.1F, 0.1F));
      assertFalse(WeatherTemperaturePolicy.canLakeIceMelt(0.0F, 5.0F));
   }

   @Test
   void mildAfternoonInAColdSpellKeepsLakeIce() {
      assertFalse(WeatherTemperaturePolicy.canLakeIceMelt(5.0F, -3.0F));
      assertFalse(WeatherTemperaturePolicy.canLakeFreeze(5.0F, -3.0F));
   }

   @Test
   void missingTemperatureNeitherFreezesNorMelts() {
      assertFalse(WeatherTemperaturePolicy.canLakeFreeze(Float.NaN, -10.0F));
      assertFalse(WeatherTemperaturePolicy.canLakeFreeze(-10.0F, Float.NaN));
      assertFalse(WeatherTemperaturePolicy.canLakeIceMelt(Float.NaN, 10.0F));
      assertFalse(WeatherTemperaturePolicy.canLakeIceMelt(10.0F, Float.NaN));
   }

   @Test
   void snowNeverFallsAndMeltsAtTheSameTemperature() {
      for (float temperatureC = -10.0F; temperatureC <= 15.0F; temperatureC += 0.05F) {
         assertFalse(
            WeatherTemperaturePolicy.shouldSnow(temperatureC) && WeatherTemperaturePolicy.snowMeltChance(temperatureC) > 0.0F,
            "snow both falls and melts at " + temperatureC + " °C"
         );
      }
   }

   @Test
   void snowMeltsJustAboveTheSnowfallCutoff() {
      assertEquals(0.0F, WeatherTemperaturePolicy.snowMeltChance(2.0F));
      assertTrue(WeatherTemperaturePolicy.snowMeltChance(2.1F) > 0.0F);
   }

   @Test
   void warmerAirMeltsSnowMoreOftenUpToEveryTick() {
      float mild = WeatherTemperaturePolicy.snowMeltChance(3.0F);
      float warm = WeatherTemperaturePolicy.snowMeltChance(6.0F);
      assertTrue(mild < warm);
      assertEquals(0.5F, warm);
      assertEquals(1.0F, WeatherTemperaturePolicy.snowMeltChance(10.0F));
      assertEquals(1.0F, WeatherTemperaturePolicy.snowMeltChance(25.0F));
   }

   @Test
   void missingTemperatureMeltsNoSnow() {
      assertEquals(0.0F, WeatherTemperaturePolicy.snowMeltChance(Float.NaN));
   }
}
