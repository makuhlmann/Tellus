package com.yucareux.tellus.client.hud;

import com.yucareux.tellus.client.TellusClientSettings;
import com.yucareux.tellus.client.screen.EarthMapSession;
import net.minecraft.network.chat.Component;

/**
 * Whether the minimap is shown, and the short notice after its hotkey is pressed. The map type follows the one last
 * picked on the Tellus map; the overlays for each Minecraft version draw it.
 */
public final class TellusMinimap {
   /** About this many blocks fit across the minimap. */
   public static final int BLOCKS_ACROSS = 256;
   private static final long NOTICE_MILLIS = 2000L;
   private static Component notice;
   private static long noticeUntilMillis;

   private TellusMinimap() {
   }

   public static boolean enabled() {
      return TellusClientSettings.minimapEnabled();
   }

   public static void setEnabled(boolean enabled) {
      TellusClientSettings.setMinimapEnabled(enabled);
   }

   /** For the hotkey: switches the minimap and says so where it is, since it may not be visible here. */
   public static void toggle() {
      boolean enabled = !enabled();
      setEnabled(enabled);
      String key = !enabled ? "tellus.minimap.off" : EarthMapSession.worldScale() > 0.0 ? "tellus.minimap.on" : "tellus.minimap.not_tellus";
      notice = Component.translatable(key);
      noticeUntilMillis = System.currentTimeMillis() + NOTICE_MILLIS;
   }

   /** The notice to show, or null once it has expired. */
   public static Component notice() {
      return notice != null && System.currentTimeMillis() < noticeUntilMillis ? notice : null;
   }

   public static Component buttonMessage() {
      return Component.translatable(enabled() ? "gui.earth.minimap.on" : "gui.earth.minimap.off");
   }
}
