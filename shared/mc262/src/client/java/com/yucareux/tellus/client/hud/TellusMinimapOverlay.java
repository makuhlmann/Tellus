package com.yucareux.tellus.client.hud;

import com.yucareux.tellus.client.screen.EarthMapSession;
import com.yucareux.tellus.client.screen.EarthTeleportScreen;
import com.yucareux.tellus.client.widget.map.SlippyMap;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.client.widget.map.SlippyMapWidget;
import com.yucareux.tellus.client.widget.map.component.PlayerMapComponent;
import com.yucareux.tellus.worldgen.EarthProjection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;

/** The minimap in the top-right corner of the HUD, on the map type last picked on the Tellus map. */
public final class TellusMinimapOverlay {
   private static final int MAX_SIZE = 128;
   private static final int MIN_SIZE = 64;
   private static final int MARGIN = 4;
   /** Room for the two rows of status effect icons the game draws in the same corner. */
   private static final int EFFECT_ICONS_HEIGHT = 52;
   private static SlippyMapWidget widget;
   private static PlayerMapComponent playerComponent;

   private TellusMinimapOverlay() {
   }

   public static void render(GuiGraphicsExtractor graphics) {
      Minecraft minecraft = Minecraft.getInstance();
      Player player = minecraft.player;
      double worldScale = EarthMapSession.worldScale();
      int top = MARGIN + (player != null && hasEffectIcons(player) ? EFFECT_ICONS_HEIGHT : 0);
      int right = graphics.guiWidth() - MARGIN;
      if (!TellusMinimap.enabled() || player == null || minecraft.level == null || !(worldScale > 0.0) || minecraft.gui.hud.isHidden()) {
         // Closing here, on the render thread, frees the minimap's tile textures safely.
         close();
         drawNotice(graphics, minecraft.font, right, top);
         return;
      }
      if (minecraft.gui.screen() instanceof EarthTeleportScreen) {
         // The map covers the whole screen; drawing the minimap under it would only download tiles twice.
         return;
      }

      int size = Mth.clamp(graphics.guiHeight() / 3, MIN_SIZE, MAX_SIZE);
      if (widget == null || widget.getWidth() != size) {
         close();
         widget = new SlippyMapWidget(right - size, top, size, size);
         widget.setAttributionBelow(true);
         playerComponent = widget.addComponent(new PlayerMapComponent());
      }
      widget.setX(right - size);
      widget.setY(top);

      SlippyMap map = widget.getMap();
      if (map.getLayer() != EarthMapSession.layer()) {
         map.setLayer(EarthMapSession.layer());
      }
      double latitude = EarthProjection.blockZToLat(player.getZ(), worldScale);
      double longitude = Mth.clamp(EarthProjection.blockXToLongitude(player.getX(), worldScale), -180.0, 180.0);
      int guiScale = Math.max(1, minecraft.getWindow().getGuiScale());
      map.focus(latitude, longitude, EarthProjection.mapZoomForBlocksAcross(worldScale, size * guiScale, TellusMinimap.BLOCKS_ACROSS));
      playerComponent.update(new SlippyMapPoint(latitude, longitude), player.getYRot());
      widget.extractRenderState(graphics, -1, -1, 1.0F);
      drawNotice(graphics, minecraft.font, right, top);
   }

   private static void close() {
      if (widget != null) {
         widget.close();
         widget = null;
         playerComponent = null;
      }
   }

   private static boolean hasEffectIcons(Player player) {
      for (MobEffectInstance effect : player.getActiveEffects()) {
         if (effect.showIcon()) {
            return true;
         }
      }
      return false;
   }

   private static void drawNotice(GuiGraphicsExtractor graphics, Font font, int right, int top) {
      Component notice = TellusMinimap.notice();
      if (notice == null) {
         return;
      }
      String text = notice.getString();
      int left = right - font.width(text) - 8;
      graphics.fill(left, top, right, top + 13, -1072689136);
      graphics.text(font, text, left + 4, top + 3, -1);
   }
}
