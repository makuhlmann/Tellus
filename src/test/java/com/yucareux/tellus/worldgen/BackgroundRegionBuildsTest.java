package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BackgroundRegionBuildsTest {
   private static final long KEY = 42L;

   @Test
   void aKeyBuildsOnceWhileItsBuildIsRunning() {
      QueuedExecutor executor = new QueuedExecutor();
      BackgroundRegionBuilds builds = new BackgroundRegionBuilds(executor, 30_000L);
      AtomicInteger runs = new AtomicInteger();

      builds.request(KEY, runs::incrementAndGet);
      builds.request(KEY, runs::incrementAndGet);
      assertEquals(1, executor.tasks.size(), "a second request for a running key queues nothing");
      assertTrue(builds.isRunning(KEY));

      executor.runAll();
      assertEquals(1, runs.get());
      assertFalse(builds.isRunning(KEY));

      builds.request(KEY, runs::incrementAndGet);
      assertEquals(1, executor.tasks.size(), "a finished key can be built again, after an eviction say");
   }

   @Test
   void otherKeysBuildAlongside() {
      QueuedExecutor executor = new QueuedExecutor();
      BackgroundRegionBuilds builds = new BackgroundRegionBuilds(executor, 30_000L);

      builds.request(1L, () -> {});
      builds.request(2L, () -> {});

      assertEquals(2, executor.tasks.size());
   }

   @Test
   void aFailedKeyWaitsOutThePauseBeforeItIsTriedAgain() throws InterruptedException {
      QueuedExecutor executor = new QueuedExecutor();
      BackgroundRegionBuilds shortPause = new BackgroundRegionBuilds(executor, 20L);
      BackgroundRegionBuilds longPause = new BackgroundRegionBuilds(executor, 30_000L);
      Runnable failing = () -> {
         throw new IllegalStateException("tile missing");
      };

      longPause.request(KEY, failing);
      executor.runAll();
      assertFalse(longPause.isRunning(KEY));
      longPause.request(KEY, failing);
      assertEquals(0, executor.tasks.size(), "no retry on every tick while the pause lasts");

      shortPause.request(KEY, failing);
      executor.runAll();
      Thread.sleep(200L);
      shortPause.request(KEY, failing);
      assertEquals(1, executor.tasks.size(), "tried again once the pause is over");
   }

   @Test
   void aRejectedBuildCanBeRequestedAgain() {
      AtomicInteger attempts = new AtomicInteger();
      Executor rejecting = task -> {
         attempts.incrementAndGet();
         throw new RejectedExecutionException("shut down");
      };
      BackgroundRegionBuilds builds = new BackgroundRegionBuilds(rejecting, 30_000L);

      builds.request(KEY, () -> {});
      assertFalse(builds.isRunning(KEY));
      builds.request(KEY, () -> {});

      assertEquals(2, attempts.get());
   }

   private static final class QueuedExecutor implements Executor {
      private final List<Runnable> tasks = new ArrayList<>();

      @Override
      public void execute(Runnable task) {
         this.tasks.add(task);
      }

      void runAll() {
         List<Runnable> pending = new ArrayList<>(this.tasks);
         this.tasks.clear();
         pending.forEach(Runnable::run);
      }
   }
}
