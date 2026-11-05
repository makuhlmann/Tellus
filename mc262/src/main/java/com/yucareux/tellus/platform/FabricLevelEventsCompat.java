package com.yucareux.tellus.platform;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
import net.minecraft.server.level.ServerLevel;

/** Fabric level lifecycle bridge for Minecraft 26.x, whose Fabric API names the event after levels. */
public final class FabricLevelEventsCompat {
   private FabricLevelEventsCompat() {
   }

   public static void onLevelLoad(Consumer<ServerLevel> callback) {
      ServerLevelEvents.LOAD.register((server, level) -> callback.accept(level));
   }
}
