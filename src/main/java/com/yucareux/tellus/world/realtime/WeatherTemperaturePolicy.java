package com.yucareux.tellus.world.realtime;

public final class WeatherTemperaturePolicy {
   // Snow can commonly reach the surface slightly above freezing; 2 C is a practical rain/snow cutoff.
   public static final float MAX_SNOW_TEMPERATURE_C = 2.0F;
   public static final float FRESH_WATER_FREEZING_POINT_C = 0.0F;
   // At and above this every random tick melts a snow layer.
   public static final float FULL_SNOW_MELT_TEMPERATURE_C = 10.0F;

   private WeatherTemperaturePolicy() {
   }

   public static boolean hasTemperature(float temperatureC) {
      return Float.isFinite(temperatureC);
   }

   public static boolean shouldSnow(float temperatureC) {
      return hasTemperature(temperatureC) && temperatureC <= MAX_SNOW_TEMPERATURE_C;
   }

   // A lake's water lags the air, so ice needs the last days to have been freezing on average as well as the
   // air now. Between the freeze and melt rules a lake keeps what it has: a mild afternoon in a cold spell or a
   // cold night after mild days changes nothing.
   public static boolean canLakeFreeze(float temperatureC, float recentMeanTemperatureC) {
      return hasTemperature(temperatureC) && hasTemperature(recentMeanTemperatureC)
         && temperatureC <= FRESH_WATER_FREEZING_POINT_C && recentMeanTemperatureC <= FRESH_WATER_FREEZING_POINT_C;
   }

   public static boolean canLakeIceMelt(float temperatureC, float recentMeanTemperatureC) {
      return hasTemperature(temperatureC) && hasTemperature(recentMeanTemperatureC)
         && temperatureC > FRESH_WATER_FREEZING_POINT_C && recentMeanTemperatureC > FRESH_WATER_FREEZING_POINT_C;
   }

   // Lying snow melts only above the temperature snow falls at, so no temperature both lays a layer and takes one.
   // A thin layer follows the air at once, unlike a lake, so the recent mean plays no part. The warmer it is, the
   // likelier a random tick melts a layer: a thaw leaves patches before the last snow goes.
   public static float snowMeltChance(float temperatureC) {
      if (!hasTemperature(temperatureC) || temperatureC <= MAX_SNOW_TEMPERATURE_C) {
         return 0.0F;
      }

      return Math.min(1.0F, (temperatureC - MAX_SNOW_TEMPERATURE_C) / (FULL_SNOW_MELT_TEMPERATURE_C - MAX_SNOW_TEMPERATURE_C));
   }
}
