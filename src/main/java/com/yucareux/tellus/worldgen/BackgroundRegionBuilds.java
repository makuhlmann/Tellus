package com.yucareux.tellus.worldgen;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Builds cached data in the background for callers that must not wait for it, such as the server thread's block ticks.
 *
 * <p>Each key builds at most once at a time. A key whose build failed waits out a pause before it is tried again, so
 * data that cannot be built yet (a tile still missing, say) does not queue a new build on every tick that asks for it.
 */
final class BackgroundRegionBuilds {
   private final Executor executor;
   private final Set<Long> running = ConcurrentHashMap.newKeySet();
   private final Cache<Long, Boolean> failedRecently;

   BackgroundRegionBuilds(Executor executor, long retryAfterMillis) {
      this.executor = executor;
      this.failedRecently = CacheBuilder.newBuilder()
         .maximumSize(4096)
         .expireAfterWrite(Math.max(1L, retryAfterMillis), TimeUnit.MILLISECONDS)
         .build();
   }

   /** Starts the build for this key unless it is already running or failed within the pause. */
   void request(long key, Runnable build) {
      if (this.failedRecently.getIfPresent(key) != null || !this.running.add(key)) {
         return;
      }

      try {
         this.executor.execute(() -> {
            try {
               build.run();
            } catch (RuntimeException error) {
               // Marked before the key leaves the running set, so no request slips in between and retries at once.
               this.failedRecently.put(key, Boolean.TRUE);
            } finally {
               this.running.remove(key);
            }
         });
      } catch (RejectedExecutionException error) {
         this.running.remove(key);
      }
   }

   boolean isRunning(long key) {
      return this.running.contains(key);
   }
}
