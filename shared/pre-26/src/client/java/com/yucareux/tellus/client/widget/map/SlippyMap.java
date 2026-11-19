package com.yucareux.tellus.client.widget.map;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;

public class SlippyMap {
   public static final int TILE_SIZE = 256;
   public static final int MIN_ZOOM = 3;
   public static final int MAX_ZOOM = 19;
   private final int width;
   private final int height;
   private final SlippyMap.Camera camera;
   private final SlippyMapTileCache cache = new SlippyMapTileCache();
   /** Holds the transparent tiles drawn over the base; created the first time a map needs one. */
   private SlippyMapTileCache overlayCache;
   private MapTileLayer layer = MapTileLayer.STANDARD;

   public SlippyMap(int width, int height) {
      this.width = width;
      this.height = height;
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      this.camera = new SlippyMap.Camera(new SlippyMapPoint(0.0, 0.0), width * scale, height * scale);
   }

   public SlippyMapTile getTile(SlippyMapTilePos pos) {
      return this.cache.getTile(pos);
   }

   public void focus(double latitude, double longitude, int zoom) {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      int clampedZoom = this.camera.clampZoom(zoom);
      SlippyMapPoint point = new SlippyMapPoint(latitude, longitude);
      this.camera.focus(point.getX(clampedZoom), point.getY(clampedZoom), clampedZoom, this.width * scale, this.height * scale);
   }

   public SlippyMapTile getOverlayTile(SlippyMapTilePos pos) {
      return this.overlayCache.getTile(pos);
   }

   public boolean hasOverlay() {
      return this.layer.overlay() != null && this.overlayCache != null;
   }

   public MapTileLayer getLayer() {
      return this.layer;
   }

   /** Shows another map; zooms out first if the new map has no tiles at the current zoom. */
   public void setLayer(MapTileLayer layer) {
      this.layer = layer;
      // Maps sharing a base, such as the standard and the railway map, keep its tiles.
      this.cache.setSource(layer.base());
      if (layer.overlay() != null) {
         if (this.overlayCache == null) {
            this.overlayCache = new SlippyMapTileCache();
         }
         this.overlayCache.setSource(layer.overlay());
      } else if (this.overlayCache != null) {
         // Cancels the overlay's downloads; without a viewport update it requests no more.
         this.overlayCache.clearCache();
      }
      this.camera.maxZoom = layer.maxZoom();
      if (this.camera.getZoom() > layer.maxZoom()) {
         SlippyMapPoint center = this.getCenter();
         this.focus(center.getLatitude(), center.getLongitude(), layer.maxZoom());
      }
   }

   public SlippyMapPoint getCenter() {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      return new SlippyMapPoint(
         this.camera.getX() + this.width * scale / 2, this.camera.getY() + this.height * scale / 2, this.camera.getZoom()
      );
   }

   public void zoom(int step, int pivotX, int pivotY) {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      this.camera.zoom(step, pivotX * scale, pivotY * scale);
   }

   public void drag(int deltaX, int deltaY) {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      this.camera.pan(deltaX * scale, deltaY * scale);
   }

   public List<SlippyMapTilePos> getVisibleTiles() {
      int scale = Math.max(1, (int)Math.round(Minecraft.getInstance().getWindow().getGuiScale()));
      int cameraX = this.camera.getX();
      int cameraY = this.camera.getY();
      int cameraZoom = this.camera.getZoom();
      int minX = Mth.floor(cameraX / 256.0);
      int minY = Mth.floor(cameraY / 256.0);
      int maxX = Mth.ceil((cameraX + this.width * scale) / 256.0);
      int maxY = Mth.ceil((cameraY + this.height * scale) / 256.0);
      List<SlippyMapTilePos> visibleTiles = new ArrayList<>();

      for (int tileY = minY; tileY < maxY; tileY++) {
         for (int tileX = minX; tileX < maxX; tileX++) {
            visibleTiles.add(new SlippyMapTilePos(tileX, tileY, cameraZoom));
         }
      }

      this.cache.updateViewport(visibleTiles);
      if (this.hasOverlay()) {
         this.overlayCache.updateViewport(visibleTiles);
      }
      return visibleTiles;
   }

   public List<SlippyMapTilePos> cascadeTiles(List<SlippyMapTilePos> tiles) {
      return cascadeTiles(this.cache, tiles);
   }

   public List<SlippyMapTilePos> cascadeOverlayTiles(List<SlippyMapTilePos> tiles) {
      return cascadeTiles(this.overlayCache, tiles);
   }

   private static List<SlippyMapTilePos> cascadeTiles(SlippyMapTileCache cache, List<SlippyMapTilePos> tiles) {
      List<SlippyMapTilePos> cascaded = new ArrayList<>(tiles.size());

      for (SlippyMapTilePos pos : tiles) {
         cascadeTile(cache, cascaded, pos);
      }

      return cascaded;
   }

   private static void cascadeTile(SlippyMapTileCache cache, List<SlippyMapTilePos> tiles, SlippyMapTilePos pos) {
      int size = 1 << pos.getZoom();
      if (pos.getX() >= 0 && pos.getY() >= 0 && pos.getX() < size && pos.getY() < size) {
         SlippyMapTile image = cache.getTile(pos);
         if (image != null && image.isReady()) {
            tiles.add(pos);
         }

         if (pos.getZoom() >= 3 && (image == null || !image.isReady() || image.getTransition() < 1.0F)) {
            cascadeTile(cache, tiles, new SlippyMapTilePos(pos.getX() >> 1, pos.getY() >> 1, pos.getZoom() - 1));
         }
      }
   }

   public int getCameraX() {
      return this.camera.getX();
   }

   public int getCameraY() {
      return this.camera.getY();
   }

   public int getCameraZoom() {
      return this.camera.getZoom();
   }

   public void shutdown() {
      try {
         this.cache.shutdown();
      } finally {
         if (this.overlayCache != null) {
            this.overlayCache.shutdown();
         }
      }
   }

   private static class Camera {
      private SlippyMapPoint origin;
      private int zoom = 3;
      private int maxZoom = MAX_ZOOM;

      private Camera(SlippyMapPoint origin, int width, int height) {
         this.origin = origin.translate(-width / 2, -height / 2, this.zoom);
      }

      private int clampZoom(int zoom) {
         return Mth.clamp(zoom, MIN_ZOOM, this.maxZoom);
      }

      public void focus(int x, int y, int zoom, int width, int height) {
         int clampedZoom = this.clampZoom(zoom);
         this.origin = new SlippyMapPoint(x, y, clampedZoom).translate(-width / 2, -height / 2, clampedZoom);
         this.zoom = clampedZoom;
      }

      public void pan(int deltaX, int deltaY) {
         this.origin = this.origin.translate(deltaX, deltaY, this.zoom);
      }

      public void zoom(int steps, int pivotX, int pivotY) {
         if (steps != 0) {
            int originX = this.origin.getX(this.zoom);
            int originY = this.origin.getY(this.zoom);
            int nextZoom = this.clampZoom(this.zoom + steps);
            if (nextZoom == this.zoom) {
               return;
            }

            this.zoom = nextZoom;
            if (steps > 0) {
               int newX = originX * 2 + pivotX;
               int newY = originY * 2 + pivotY;
               this.origin = new SlippyMapPoint(newX, newY, this.zoom);
            } else if (steps < 0) {
               int newX = (originX - pivotX) / 2;
               int newY = (originY - pivotY) / 2;
               this.origin = new SlippyMapPoint(newX, newY, this.zoom);
            }
         }
      }

      public int getX() {
         return this.origin.getX(this.zoom);
      }

      public int getY() {
         return this.origin.getY(this.zoom);
      }

      public int getZoom() {
         return this.zoom;
      }
   }
}
