package com.yucareux.tellus.world.data.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MapTileImagesTest {
   private static final int TILE_SIZE = 256;

   @Test
   void leavesTilesThatAlreadyFitUntouched() throws IOException {
      byte[] png = png(new BufferedImage(TILE_SIZE, TILE_SIZE, BufferedImage.TYPE_INT_ARGB));

      assertSame(png, MapTileImages.fitToTileSize(png, TILE_SIZE));
   }

   @Test
   void averagesDoubleResolutionTilesDownToOneTile() throws IOException {
      BufferedImage source = new BufferedImage(TILE_SIZE * 2, TILE_SIZE * 2, BufferedImage.TYPE_INT_ARGB);
      // Top-left block: one opaque red pixel among three transparent ones, as at the edge of a line.
      source.setRGB(0, 0, 0xFFFF0000);
      // The block to its right is opaque blue, and one further right is half red, half green.
      fillBlock(source, 1, 0, 0xFF0000FF);
      source.setRGB(4, 0, 0xFFFF0000);
      source.setRGB(5, 0, 0xFFFF0000);
      source.setRGB(4, 1, 0xFF00FF00);
      source.setRGB(5, 1, 0xFF00FF00);

      BufferedImage scaled = ImageIO.read(new ByteArrayInputStream(MapTileImages.fitToTileSize(png(source), TILE_SIZE)));

      assertEquals(TILE_SIZE, scaled.getWidth());
      assertEquals(TILE_SIZE, scaled.getHeight());
      // The transparent pixels lower the alpha but must not darken the red.
      assertEquals(0x3FFF0000, scaled.getRGB(0, 0));
      assertEquals(0xFF0000FF, scaled.getRGB(1, 0));
      assertEquals(0xFF7F7F00, scaled.getRGB(2, 0));
      assertEquals(0, scaled.getRGB(3, 0) >>> 24);
   }

   @Test
   void rejectsTilesThatAreNotAWholeMultipleOfATile() throws IOException {
      byte[] png = png(new BufferedImage(300, 300, BufferedImage.TYPE_INT_ARGB));

      assertThrows(IOException.class, () -> MapTileImages.fitToTileSize(png, TILE_SIZE));
   }

   private static void fillBlock(BufferedImage image, int blockX, int blockY, int argb) {
      for (int dy = 0; dy < 2; dy++) {
         for (int dx = 0; dx < 2; dx++) {
            image.setRGB(blockX * 2 + dx, blockY * 2 + dy, argb);
         }
      }
   }

   private static byte[] png(BufferedImage image) throws IOException {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      ImageIO.write(image, "png", output);
      return output.toByteArray();
   }
}
