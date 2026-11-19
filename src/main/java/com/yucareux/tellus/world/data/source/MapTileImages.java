package com.yucareux.tellus.world.data.source;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Shrinks high-resolution map tiles to the size the map draws a tile at. */
public final class MapTileImages {
   private MapTileImages() {
   }

   /**
    * Returns the PNG unchanged when it is no larger than a tile, and otherwise averaged down to one tile. A 512 px
    * tile would take four times the memory and, drawn at half size, lose its thin lines and small labels to aliasing.
    * Expects a PNG that {@link MapTileImageValidator#validatePng} accepted.
    */
   public static byte[] fitToTileSize(byte[] png, int tileSize) throws IOException {
      int width = readInt(png, 16);
      int height = readInt(png, 20);
      if (width <= tileSize && height <= tileSize) {
         return png;
      }
      if (width != height || width % tileSize != 0) {
         throw new IOException("Map tile of " + width + "x" + height + " cannot be scaled to " + tileSize + " px");
      }

      BufferedImage source = ImageIO.read(new ByteArrayInputStream(png));
      if (source == null) {
         throw new IOException("Map tile PNG could not be decoded");
      }

      int factor = width / tileSize;
      int[] pixels = source.getRGB(0, 0, width, height, null, 0, width);
      int[] scaled = new int[tileSize * tileSize];
      int samples = factor * factor;
      for (int y = 0; y < tileSize; y++) {
         for (int x = 0; x < tileSize; x++) {
            // Weigh each colour by its alpha, so transparent pixels around a line do not darken its edges.
            long alpha = 0L;
            long red = 0L;
            long green = 0L;
            long blue = 0L;
            for (int dy = 0; dy < factor; dy++) {
               int row = (y * factor + dy) * width + x * factor;
               for (int dx = 0; dx < factor; dx++) {
                  int argb = pixels[row + dx];
                  int a = argb >>> 24;
                  alpha += a;
                  red += (long)((argb >>> 16) & 0xFF) * a;
                  green += (long)((argb >>> 8) & 0xFF) * a;
                  blue += (long)(argb & 0xFF) * a;
               }
            }
            scaled[y * tileSize + x] = alpha == 0L
               ? 0
               : (int)(alpha / samples) << 24 | (int)(red / alpha) << 16 | (int)(green / alpha) << 8 | (int)(blue / alpha);
         }
      }

      BufferedImage target = new BufferedImage(tileSize, tileSize, BufferedImage.TYPE_INT_ARGB);
      target.setRGB(0, 0, tileSize, tileSize, scaled, 0, tileSize);
      ByteArrayOutputStream output = new ByteArrayOutputStream(64 * 1024);
      if (!ImageIO.write(target, "png", output)) {
         throw new IOException("No PNG writer is available to scale map tiles");
      }
      return output.toByteArray();
   }

   private static int readInt(byte[] data, int offset) {
      return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8 | data[offset + 3] & 0xFF;
   }
}
