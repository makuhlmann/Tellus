package com.yucareux.tellus.client.widget.map.component;

import com.yucareux.tellus.client.widget.map.SlippyMap;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;

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
   public void onDrawMap(SlippyMap map, GuiGraphicsExtractor graphics, int mouseX, int mouseY, SlippyMapPoint mouse) {
      if (this.position == null) {
         return;
      }

      int scale = Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
      float x = (float)(this.position.getX(map.getCameraZoom()) - map.getCameraX()) / scale;
      float y = (float)(this.position.getY(map.getCameraZoom()) - map.getCameraY()) / scale;
      graphics.pose().pushMatrix();
      graphics.pose().translate(x, y);
      // Yaw 180 faces north, which is up; turning right raises the yaw, as a clockwise rotation does on screen.
      graphics.pose().rotate((float)Math.toRadians(this.yaw - 180.0F));
      graphics.pose().scale(0.5F, 0.5F);
      graphics.pose().pushMatrix();
      graphics.pose().scale(OUTLINE_SCALE, OUTLINE_SCALE);
      drawArrow(graphics, 0xFF000000);
      graphics.pose().popMatrix();
      drawArrow(graphics, 0xFFFFFFFF);
      graphics.pose().popMatrix();
   }

   private static void drawArrow(GuiGraphicsExtractor graphics, int color) {
      for (int[] strip : PlayerArrowShape.STRIPS) {
         graphics.fill(strip[1], strip[0], strip[2], strip[0] + 1, color);
      }
   }
}
