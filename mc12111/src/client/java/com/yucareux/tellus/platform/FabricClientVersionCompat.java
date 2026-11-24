package com.yucareux.tellus.platform;

import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.client.hud.ManagedTerrainDownloadOverlay;
import com.yucareux.tellus.client.hud.TellusMinimapOverlay;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;

/** Fabric client key category and HUD registration for Minecraft 1.21.11. */
public final class FabricClientVersionCompat {
   /** Registered once: every Tellus key mapping shares it. */
   private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Tellus.id("controls"));

   private FabricClientVersionCompat() {
   }

   public static KeyMapping.Category keyCategory() {
      return KEY_CATEGORY;
   }

   public static void registerHud() {
      HudElementRegistry.addLast(Tellus.id("minimap"), (graphics, deltaTracker) -> TellusMinimapOverlay.render(graphics));
      HudElementRegistry.addLast(Tellus.id("managed_terrain_status"), (graphics, deltaTracker) -> ManagedTerrainDownloadOverlay.render(graphics));
   }
}
