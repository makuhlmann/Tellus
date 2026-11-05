package com.yucareux.tellus.platform;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.level.ServerLevel;

/** Fabric level lifecycle bridge for Minecraft 1.21.11. */
public final class FabricLevelEventsCompat {
   private FabricLevelEventsCompat() {
   }

   public static void onLevelLoad(Consumer<ServerLevel> callback) {
      ServerWorldEvents.LOAD.register((server, level) -> callback.accept(level));
   }
}
