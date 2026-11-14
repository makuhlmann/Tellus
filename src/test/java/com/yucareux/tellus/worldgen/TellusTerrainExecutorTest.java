package com.yucareux.tellus.worldgen;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TellusTerrainExecutorTest {
   @Test
   void runsTasksOnItsOwnThreads() throws Exception {
      Thread caller = Thread.currentThread();
      Thread worker = TellusTerrainExecutor.supply(Thread::currentThread).get(10, TimeUnit.SECONDS);

      assertNotSame(caller, worker);
      assertTrue(worker.getName().startsWith(TellusTerrainExecutor.THREAD_NAME_PREFIX), worker.getName());
      assertTrue(worker.isDaemon(), "terrain threads must not keep the server process alive");
   }

   @Test
   void oneWaitingChunkDoesNotHoldUpAnother() throws Exception {
      assertTrue(TellusTerrainExecutor.threads() >= 2, "the default pool has at least two threads");
      CountDownLatch bothStarted = new CountDownLatch(2);

      CompletableFuture<Boolean> first = TellusTerrainExecutor.supply(() -> awaitPeer(bothStarted));
      CompletableFuture<Boolean> second = TellusTerrainExecutor.supply(() -> awaitPeer(bothStarted));

      assertTrue(first.get(10, TimeUnit.SECONDS));
      assertTrue(second.get(10, TimeUnit.SECONDS));
   }

   private static boolean awaitPeer(CountDownLatch bothStarted) {
      bothStarted.countDown();
      try {
         return bothStarted.await(5, TimeUnit.SECONDS);
      } catch (InterruptedException error) {
         Thread.currentThread().interrupt();
         return false;
      }
   }
}
