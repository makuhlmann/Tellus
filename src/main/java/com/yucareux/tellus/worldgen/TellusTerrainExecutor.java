package com.yucareux.tellus.worldgen;

import com.yucareux.tellus.Tellus;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Runs the biome and terrain steps of chunk generation on Tellus's own threads.
 *
 * <p>Minecraft runs every generation step, including the steps that bring a saved chunk back, on a single worldgen
 * queue that takes one task at a time. Vanilla terrain hands its work to another thread and frees that queue at once;
 * doing the same here stops players in land that already exists from waiting behind terrain still being built for
 * someone exploring. These are not Minecraft's shared worker threads, because the fill can wait on tile downloads and
 * those workers also parse the saved chunks being loaded.
 */
final class TellusTerrainExecutor {
   // Distinct from the "tellus-terrain-" prefetch threads, so a profile tells the two apart.
   static final String THREAD_NAME_PREFIX = "tellus-chunkgen-";
   private static final int THREADS = threadCount();
   private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(THREADS, new ThreadFactory() {
      private final AtomicInteger index = new AtomicInteger();

      @Override
      public Thread newThread(Runnable runnable) {
         Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + this.index.incrementAndGet());
         thread.setDaemon(true);
         return thread;
      }
   });

   private TellusTerrainExecutor() {
   }

   static <T> CompletableFuture<T> supply(Supplier<T> task) {
      return CompletableFuture.supplyAsync(task, EXECUTOR);
   }

   static int threads() {
      return THREADS;
   }

   private static int threadCount() {
      int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
      int defaultThreads = Math.max(2, Math.min(8, cores / 2));
      String value = System.getProperty("tellus.chunkgen.terrainThreads");
      if (value == null) {
         return defaultThreads;
      }

      try {
         return Math.max(1, Math.min(64, Integer.parseInt(value.trim())));
      } catch (NumberFormatException error) {
         Tellus.LOGGER.warn("Invalid tellus.chunkgen.terrainThreads '{}', using {}", value, defaultThreads);
         return defaultThreads;
      }
   }
}
