package com.yucareux.tellus.client.widget.map.component;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's arrow on the minimap as horizontal strips, pointing up (north) with its pivot at (0, 0). Units are half
 * GUI pixels, so it is drawn at half scale and its edges stay fine when rotated.
 */
final class PlayerArrowShape {
   private static final int TIP = -12;
   private static final int BASE = 9;
   private static final int HALF_WIDTH = 8;
   /** Where the notch in the arrow's tail starts. */
   private static final int NOTCH = 3;
   /** Each strip is {y, left, right}; a row with a notch has two. */
   static final List<int[]> STRIPS = buildStrips();

   private PlayerArrowShape() {
   }

   private static List<int[]> buildStrips() {
      List<int[]> strips = new ArrayList<>();
      for (int y = TIP; y < BASE; y++) {
         int outer = Math.round((y - TIP + 1) * (float)HALF_WIDTH / (BASE - TIP));
         int notch = y >= NOTCH ? Math.round((y - NOTCH + 1) * (float)HALF_WIDTH / (BASE - NOTCH)) : 0;
         if (notch >= outer) {
            continue;
         }
         if (notch > 0) {
            strips.add(new int[]{y, -outer, -notch});
            strips.add(new int[]{y, notch, outer});
         } else {
            strips.add(new int[]{y, -outer, outer});
         }
      }
      return List.copyOf(strips);
   }
}
