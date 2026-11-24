package com.yucareux.tellus.client.widget.map.component;

import com.yucareux.tellus.client.widget.map.SlippyMap;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/** The player on the minimap: an arrow at their position, pointing where they look. */
public class PlayerMapComponent implements MapComponent {
   private static final float OUTLINE_SCALE = 1.4F;
   private SlippyMapPoint position;
   private float yaw;

   public void update(SlippyMapPoint position, float yaw) {
      this.position = position;
      this.yaw = yaw;
   }

   @Override
   public void onDrawMap(SlippyMap map, GuiGraphics graphics, int mouseX, int mouseY, SlippyMapPoint mouse) {
      if (this.position == null) {
         return;
      }

      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      float x = (float)(this.position.getX(map.getCameraZoom()) - map.getCameraX()) / scale;
      float y = (float)(this.position.getY(map.getCameraZoom()) - map.getCameraY()) / scale;
      ClientMinecraftCompat.pushPose(graphics);
      ClientMinecraftCompat.translatePose(graphics, x, y);
      // Yaw 180 faces north, which is up; turning right raises the yaw, as a clockwise rotation does on screen.
      ClientMinecraftCompat.rotatePose(graphics, (float)Math.toRadians(this.yaw - 180.0F));
      ClientMinecraftCompat.scalePose(graphics, 0.5F, 0.5F);
      ClientMinecraftCompat.pushPose(graphics);
      ClientMinecraftCompat.scalePose(graphics, OUTLINE_SCALE, OUTLINE_SCALE);
      drawArrow(graphics, 0xFF000000);
      ClientMinecraftCompat.popPose(graphics);
      drawArrow(graphics, 0xFFFFFFFF);
      ClientMinecraftCompat.popPose(graphics);
   }

   private static void drawArrow(GuiGraphics graphics, int color) {
      for (int[] strip : PlayerArrowShape.STRIPS) {
         graphics.fill(strip[1], strip[0], strip[2], strip[0] + 1, color);
      }
   }
}
