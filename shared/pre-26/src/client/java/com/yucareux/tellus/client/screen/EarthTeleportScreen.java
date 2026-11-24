package com.yucareux.tellus.client.screen;

import com.yucareux.tellus.client.widget.map.MapTileLayer;
import com.yucareux.tellus.client.widget.map.PlaceSearchWidget;
import com.yucareux.tellus.client.widget.map.SlippyMapPoint;
import com.yucareux.tellus.client.widget.map.SlippyMapWidget;
import com.yucareux.tellus.client.widget.map.component.MarkerMapComponent;
import com.yucareux.tellus.client.compat.AbstractTellusScreen;
import com.yucareux.tellus.compat.ClientMinecraftCompat;
import com.yucareux.tellus.platform.TellusClientPlatform;
import com.yucareux.tellus.world.data.source.Geocoder;
import com.yucareux.tellus.world.data.source.NominatimGeocoder;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

public class EarthTeleportScreen extends AbstractTellusScreen {
   private static final int DEFAULT_ZOOM = 6;
   private static final int SEARCH_ZOOM = 12;
   private static final int MAX_BUTTON_WIDTH = 150;
   private static final int BUTTON_GAP = 4;
   private static final int NOTICE_TICKS = 100;
   private static final int NOTICE_COLOR = 0xFFFF6060;

   private final Screen parent;
   private final double initialLatitude;
   private final double initialLongitude;
   private final double worldScale;
   private final boolean canTeleport;
   private SlippyMapWidget mapWidget;
   private MarkerMapComponent markerComponent;
   private PlaceSearchWidget searchWidget;
   private Button layerButton;
   private boolean suppressMapRelease;
   /** Shown in place of the marker's coordinates for a few seconds. */
   private Component notice;
   private int noticeTicks;

   public EarthTeleportScreen(Screen parent, double latitude, double longitude, double worldScale, boolean canTeleport) {
      super(Component.translatable("gui.earth.map"));
      this.parent = parent;
      this.initialLatitude = latitude;
      this.initialLongitude = longitude;
      this.worldScale = worldScale;
      this.canTeleport = canTeleport;
   }

   protected void init() {
      // init() runs again when the window is resized: keep the view and the marker the player chose.
      SlippyMapPoint marker = new SlippyMapPoint(this.initialLatitude, this.initialLongitude);
      SlippyMapPoint focus = marker;
      if (this.mapWidget != null) {
         EarthMapSession.rememberZoom(this.mapWidget.getMap().getCameraZoom());
         focus = this.mapWidget.getMap().getCenter();
      }
      if (this.markerComponent != null && this.markerComponent.getMarker() != null) {
         marker = this.markerComponent.getMarker();
      }
      MapScreenResources.close(this.mapWidget, this.searchWidget);

      int mapX = 20;
      int mapY = 20;
      int mapWidth = this.width - 40;
      int mapHeight = this.height - 72;
      this.mapWidget = new SlippyMapWidget(mapX, mapY, mapWidth, mapHeight);
      this.mapWidget.getMap().setLayer(EarthMapSession.layer());
      this.markerComponent = new MarkerMapComponent(marker).allowMovement();
      this.mapWidget.addComponent(this.markerComponent);
      this.mapWidget.getMap().focus(focus.getLatitude(), focus.getLongitude(), EarthMapSession.zoom(DEFAULT_ZOOM));
      Geocoder geocoder = new NominatimGeocoder(() -> this.minecraft == null ? null : this.minecraft.getLanguageManager().getSelected());
      this.searchWidget = new PlaceSearchWidget(mapX + 5, mapY + 5, 200, 20, geocoder, this::handleSearch);
      this.addRenderableOnly(this.mapWidget);
      this.addRenderableWidget(this.searchWidget);

      int buttonY = this.height - 28;
      boolean xaeroWaypoints = XaeroWaypoints.available();
      int buttonCount = xaeroWaypoints ? 4 : 3;
      int buttonWidth = Math.min(MAX_BUTTON_WIDTH, (mapWidth - BUTTON_GAP * (buttonCount - 1)) / buttonCount);
      int buttonStep = buttonWidth + BUTTON_GAP;
      int buttonX = (this.width - buttonStep * buttonCount + BUTTON_GAP) / 2;
      this.layerButton = this.addRenderableWidget(
         Button.builder(layerMessage(EarthMapSession.layer()), button -> this.switchLayer())
            .bounds(buttonX, buttonY, buttonWidth, 20)
            .build()
      );
      if (xaeroWaypoints) {
         buttonX += buttonStep;
         Button waypointButton = this.addRenderableWidget(
            Button.builder(Component.translatable("gui.earth.waypoint"), button -> this.addXaeroWaypoint())
               .bounds(buttonX, buttonY, buttonWidth, 20)
               .build()
         );
         waypointButton.setTooltip(Tooltip.create(Component.translatable("gui.earth.waypoint.tooltip")));
      }
      Button teleportButton = this.addRenderableWidget(
         Button.builder(Component.translatable("gui.earth.teleport"), button -> this.sendTeleport())
            .bounds(buttonX + buttonStep, buttonY, buttonWidth, 20)
            .build()
      );
      if (!this.canTeleport) {
         teleportButton.active = false;
         teleportButton.setTooltip(Tooltip.create(Component.translatable("gui.earth.teleport.operators_only")));
      }
      this.addRenderableWidget(
         Button.builder(Component.translatable(this.canTeleport ? "gui.cancel" : "gui.done"), button -> this.closeScreen())
            .bounds(buttonX + buttonStep * 2, buttonY, buttonWidth, 20)
            .build()
      );
      this.addWidget(this.mapWidget);
   }

   protected void setInitialFocus() {
      if (this.searchWidget != null) {
         this.setInitialFocus(this.searchWidget);
      }
   }

   private void handleSearch(double latitude, double longitude) {
      this.markerComponent.moveMarker(latitude, longitude);
      this.mapWidget.getMap().focus(latitude, longitude, SEARCH_ZOOM);
   }

   private void switchLayer() {
      MapTileLayer layer = this.mapWidget.getMap().getLayer().next();
      this.mapWidget.getMap().setLayer(layer);
      EarthMapSession.rememberLayer(layer);
      this.layerButton.setMessage(layerMessage(layer));
   }

   private void addXaeroWaypoint() {
      SlippyMapPoint marker = this.markerComponent == null ? null : this.markerComponent.getMarker();
      if (marker == null || this.minecraft == null) {
         return;
      }

      // Xaero's Minimap opens its own screen in place of this one. It stays closed when the minimap cannot take a
      // waypoint at the moment, which it only writes to its log, so say here why nothing happened.
      if (!XaeroWaypoints.openNewWaypoint(this.minecraft, marker, this.worldScale) || this.minecraft.screen == this) {
         this.notice = Component.translatable("gui.earth.waypoint.failed");
         this.noticeTicks = NOTICE_TICKS;
      }
   }

   private static Component layerMessage(MapTileLayer layer) {
      return Component.translatable("gui.earth.map_layer", Component.translatable(layer.translationKey()));
   }

   private void sendTeleport() {
      if (this.canTeleport && this.markerComponent != null) {
         SlippyMapPoint marker = this.markerComponent.getMarker();
         if (marker != null && this.minecraft != null) {
            if (!TellusClientPlatform.sendGeoTeleport(marker.getLatitude(), marker.getLongitude())) {
               if (this.minecraft.player != null) {
                  this.minecraft.player.displayClientMessage(Component.translatable("tellus.geotp.server_rejected"), true);
               }
            }

            this.closeScreen();
         }
      }
   }

   @Override
   protected boolean tellusMouseClicked(double mouseX, double mouseY, int button) {
      if (this.isSearchOverlayMouseOver(mouseX, mouseY)) {
         this.suppressMapRelease = true;
         this.cancelMapInteraction();
         this.setFocused(this.searchWidget);
         this.searchWidget.setFocused(true);
         ClientMinecraftCompat.mouseClicked(this.searchWidget, mouseX, mouseY, button);
         return true;
      }

      this.suppressMapRelease = false;
      return super.tellusMouseClicked(mouseX, mouseY, button);
   }

   @Override
   protected boolean tellusMouseReleased(double mouseX, double mouseY, int button) {
      if (this.suppressMapRelease || this.isSearchOverlayMouseOver(mouseX, mouseY)) {
         this.suppressMapRelease = false;
         this.cancelMapInteraction();
         return true;
      }

      return super.tellusMouseReleased(mouseX, mouseY, button);
   }

   private void closeScreen() {
      if (this.minecraft != null) {
         this.minecraft.setScreen(this.parent);
      }
   }

   public void render( GuiGraphics graphics, int mouseX, int mouseY, float delta) {
      graphics.fill(0, 0, this.width, this.height, -1072689136);
      graphics.drawCenteredString(this.font, this.title, this.width / 2, 4, 16777215);
      SlippyMapPoint marker = this.markerComponent == null ? null : this.markerComponent.getMarker();
      if (this.noticeTicks > 0 && this.notice != null) {
         graphics.drawCenteredString(this.font, this.notice, this.width / 2, this.height - 45, NOTICE_COLOR);
      } else if (marker != null) {
         graphics.drawCenteredString(this.font, MapMarkerText.describe(marker, this.worldScale), this.width / 2, this.height - 45, -1);
      }
      super.render(graphics, mouseX, mouseY, delta);
   }

   public void tick() {
      super.tick();
      if (this.noticeTicks > 0) {
         this.noticeTicks--;
      }
      if (this.searchWidget != null) {
         this.searchWidget.tick();
      }
   }

   public void onClose() {
      this.closeScreen();
   }

   public void removed() {
      if (this.mapWidget != null) {
         EarthMapSession.rememberZoom(this.mapWidget.getMap().getCameraZoom());
      }
      MapScreenResources.close(this.mapWidget, this.searchWidget);
   }

   private boolean isSearchOverlayMouseOver(double mouseX, double mouseY) {
      return this.searchWidget != null && this.searchWidget.isMouseOver(mouseX, mouseY);
   }

   private void cancelMapInteraction() {
      if (this.mapWidget != null) {
         this.mapWidget.cancelInteraction();
      }
   }
}
