package com.yucareux.tellus.client.widget.map;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.mojang.blaze3d.platform.NativeImage;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.world.data.source.MapTileDownloader;
import com.yucareux.tellus.world.data.source.MapTileImageValidator;
import com.yucareux.tellus.world.data.source.MapTileImages;
import com.yucareux.tellus.world.data.source.MapTileServerGate;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.Minecraft;

public class SlippyMapTileCache implements TellusCacheHandle {
   private static final int CACHE_SIZE = 1024;
   /** Threads that read cached tiles and wait for downloads; each server's gate caps the requests sent to it. */
   private static final int LOAD_THREADS = 4;
   private static final int MAX_ACTIVE_LOADS = 256;
   private static final int MAX_TILE_BYTES = 4 * 1024 * 1024;
   private static final int MAX_TILE_DIMENSION = 512;
   private static final long RETRY_BASE_NANOS = TimeUnit.SECONDS.toNanos(1L);
   private static final long RETRY_MAX_NANOS = TimeUnit.SECONDS.toNanos(30L);
   /** Attempts after which a tile the server answered with an error, such as 404, is not asked for again. */
   private static final int MAX_TILE_ERROR_ATTEMPTS = 3;
   private static final MapTileDownloader DOWNLOADER = new MapTileDownloader("Tellus-Minecraft-Mod (+https://github.com/Yucareux/Tellus)");
   private static final AtomicInteger NEXT_CACHE_ID = new AtomicInteger();
   /** Part of every tile's texture name: the minimap and the map screen hold caches at the same time. */
   private final int cacheId = NEXT_CACHE_ID.incrementAndGet();
   private final Object schedulingLock = new Object();
   private final AtomicLong taskSequence = new AtomicLong();
   private final ThreadPoolExecutor loadingService = new ThreadPoolExecutor(
      LOAD_THREADS,
      LOAD_THREADS,
      0L,
      TimeUnit.MILLISECONDS,
      new PriorityBlockingQueue<>(),
      new ThreadFactoryBuilder().setDaemon(true).setNameFormat("tellus-map-load-%d").build()
   );
   private final Path cacheRoot = Minecraft.getInstance().gameDirectory.toPath().resolve("tellus/cache/map");
   private volatile MapTileSource source = MapTileSource.OPENSTREETMAP;
   private volatile boolean shuttingDown;
   private long viewportRevision;
   private Map<SlippyMapTilePos, Integer> viewportPriorities = Map.of();
   private final LoadingCache<SlippyMapTilePos, SlippyMapTileCache.TileEntry> tileCache = CacheBuilder.newBuilder()
      .maximumSize(CACHE_SIZE)
      .removalListener(notification -> {
         SlippyMapTileCache.TileEntry entry = (SlippyMapTileCache.TileEntry)notification.getValue();
         if (entry != null) {
            synchronized (SlippyMapTileCache.this.schedulingLock) {
               SlippyMapTileCache.this.cancelTaskLocked(entry);
            }
            entry.tile.delete();
         }
      })
      .build(new CacheLoader<SlippyMapTilePos, SlippyMapTileCache.TileEntry>() {
         public SlippyMapTileCache.TileEntry load(SlippyMapTilePos key) {
            return new SlippyMapTileCache.TileEntry(key, SlippyMapTileCache.this.textureKey(SlippyMapTileCache.this.source));
         }
      });

   public SlippyMapTileCache() {
      TellusCacheRegistry.register(this);
   }

   public MapTileSource getSource() {
      return this.source;
   }

   private String textureKey(MapTileSource tileSource) {
      return tileSource.id() + "_" + this.cacheId;
   }

   /** Drops the tiles of the previous source; its downloads already sent still finish and are saved to disk. */
   public void setSource(MapTileSource source) {
      Objects.requireNonNull(source, "source");
      if (source != this.source) {
         this.source = source;
         this.clearCache();
      }
   }

   public SlippyMapTile getTile(SlippyMapTilePos pos) {
      SlippyMapTileCache.TileEntry entry;
      try {
         entry = this.tileCache.getUnchecked(pos);
      } catch (RuntimeException error) {
         Tellus.LOGGER.warn("Failed to create map tile {}", pos, error);
         return new SlippyMapTile(pos, this.textureKey(this.source));
      }

      synchronized (this.schedulingLock) {
         Integer priority = this.viewportPriorities.get(pos);
         if (priority != null) {
            this.requestLoadLocked(entry, priority, this.viewportRevision, System.nanoTime());
         }
      }
      return entry.tile;
   }

   public void updateViewport(List<SlippyMapTilePos> visibleTiles) {
      if (this.shuttingDown) {
         return;
      }
      Map<SlippyMapTilePos, Integer> plannedPriorities = buildViewportPriorities(visibleTiles);
      List<Map.Entry<SlippyMapTilePos, Integer>> ordered = new ArrayList<>(plannedPriorities.entrySet());
      ordered.sort(Map.Entry.comparingByValue());
      long now = System.nanoTime();

      synchronized (this.schedulingLock) {
         boolean viewportChanged = !plannedPriorities.equals(this.viewportPriorities);
         if (viewportChanged) {
            this.viewportRevision++;
            this.viewportPriorities = Map.copyOf(plannedPriorities);
         }

         if (viewportChanged) {
            for (Map.Entry<SlippyMapTilePos, SlippyMapTileCache.TileEntry> cached : this.tileCache.asMap().entrySet()) {
               SlippyMapTileCache.TileEntry entry = cached.getValue();
               SlippyMapTileCache.TileLoadTask task = entry.task;
               if (task == null || task.committed) {
                  // A download already sent finishes even when its tile scrolled away: the server has done the work.
                  continue;
               }
               Integer priority = plannedPriorities.get(cached.getKey());
               boolean leftView = priority == null;
               boolean reprioritized = !task.started && (task.revision != this.viewportRevision || task.priority != priority);
               if (leftView || reprioritized) {
                  this.cancelTaskLocked(entry);
               }
            }
         }

         for (Map.Entry<SlippyMapTilePos, Integer> request : ordered) {
            if (this.currentLoadCount() >= MAX_ACTIVE_LOADS) {
               break;
            }
            SlippyMapTileCache.TileEntry entry = this.tileCache.getUnchecked(request.getKey());
            this.requestLoadLocked(entry, request.getValue(), this.viewportRevision, now);
         }
      }
   }

   /** Stops loading tiles. Downloads already sent finish in the background and are saved to disk. */
   public void shutdown() {
      this.shuttingDown = true;
      this.clearCache();
      this.loadingService.shutdown();
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.OSM;
   }

   @Override
   public void clearCache() {
      synchronized (this.schedulingLock) {
         for (SlippyMapTileCache.TileEntry entry : this.tileCache.asMap().values()) {
            this.cancelTaskLocked(entry);
         }
      }
      this.tileCache.invalidateAll();
      this.tileCache.cleanUp();
   }

   private int currentLoadCount() {
      return this.loadingService.getActiveCount() + this.loadingService.getQueue().size();
   }

   private void requestLoadLocked(SlippyMapTileCache.TileEntry entry, int priority, long revision, long now) {
      if (this.shuttingDown
         || entry.loaded
         || entry.abandoned
         || entry.task != null
         || entry.retryScheduled && now - entry.retryAtNanos < 0L
         || this.currentLoadCount() >= MAX_ACTIVE_LOADS) {
         return;
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OSM);
      SlippyMapTileCache.TileLoadTask task = new SlippyMapTileCache.TileLoadTask(
         entry, this.source, generation, revision, priority, this.taskSequence.incrementAndGet()
      );
      entry.task = task;
      try {
         this.loadingService.execute(task);
      } catch (RejectedExecutionException ignored) {
         if (entry.task == task) {
            entry.task = null;
            this.scheduleRetryLocked(entry, now + RETRY_BASE_NANOS);
         }
      }
   }

   /** Abandons a task that has not sent its request; one that has finishes and saves its tile, but shows nothing. */
   private void cancelTaskLocked(SlippyMapTileCache.TileEntry entry) {
      SlippyMapTileCache.TileLoadTask task = entry.task;
      if (task != null) {
         entry.task = null;
         task.cancelled = true;
         this.loadingService.remove(task);
      }
   }

   private void scheduleRetryLocked(SlippyMapTileCache.TileEntry entry, long retryAtNanos) {
      entry.retryScheduled = true;
      entry.retryAtNanos = retryAtNanos;
   }

   private boolean isCancelled(SlippyMapTileCache.TileLoadTask task) {
      return task.cancelled || this.shuttingDown;
   }

   /** Marks the task's request as sent, unless the task was cancelled while it waited for the server. */
   private boolean commit(SlippyMapTileCache.TileLoadTask task) {
      synchronized (this.schedulingLock) {
         if (this.isCancelled(task)) {
            return false;
         }
         task.committed = true;
         return true;
      }
   }

   private void loadTile(SlippyMapTileCache.TileLoadTask task) {
      byte[] data;
      try {
         data = this.readTileData(task);
      } catch (MapTileServerGate.PausedException paused) {
         this.finishDeferred(task, paused.resumeAtNanos());
         return;
      } catch (IOException | RuntimeException error) {
         if (this.isCancelled(task)) {
            this.finishCancelled(task);
         } else {
            this.finishFailed(task, error);
         }
         return;
      }
      if (this.isCancelled(task)) {
         // A download for a tile no longer wanted has been saved to disk; decoding it would only waste time.
         this.finishCancelled(task);
         return;
      }

      NativeImage image;
      try {
         image = NativeImage.read(new ByteArrayInputStream(data));
      } catch (IOException | RuntimeException error) {
         // A truncated PNG passes the header check; drop it from disk, so the next attempt downloads it again.
         this.deleteCachedTile(task);
         this.finishFailed(task, new SlippyMapTileCache.InvalidTileException(task.source.providerName() + " tile " + task.entry.pos + " cannot be decoded", error));
         return;
      }

      boolean accepted;
      synchronized (this.schedulingLock) {
         accepted = !task.cancelled
            && !this.shuttingDown
            && task.entry.task == task
            && TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, task.generation);
         if (task.entry.task == task) {
            task.entry.task = null;
         }
         if (accepted) {
            task.entry.loaded = true;
            task.entry.failures = 0;
            task.entry.retryScheduled = false;
         }
      }

      if (accepted) {
         task.entry.tile.supplyImage(image);
      } else {
         image.close();
      }
   }

   private void finishCancelled(SlippyMapTileCache.TileLoadTask task) {
      synchronized (this.schedulingLock) {
         if (task.entry.task == task) {
            task.entry.task = null;
         }
      }
   }

   /** The server is paused: ask again once it resumes, without counting this as a failure of the tile. */
   private void finishDeferred(SlippyMapTileCache.TileLoadTask task, long resumeAtNanos) {
      synchronized (this.schedulingLock) {
         if (task.entry.task == task) {
            task.entry.task = null;
            this.scheduleRetryLocked(task.entry, resumeAtNanos);
         }
      }
   }

   private void finishFailed(SlippyMapTileCache.TileLoadTask task, Throwable error) {
      boolean tileOnly = concernsTileOnly(error);
      int failures;
      boolean abandoned;
      synchronized (this.schedulingLock) {
         if (task.cancelled || this.shuttingDown || task.entry.task != task) {
            return;
         }
         task.entry.task = null;
         failures = Math.min(task.entry.failures + 1, 30);
         task.entry.failures = failures;
         // Asking again for a tile the server says is missing or broken would not change its answer.
         abandoned = tileOnly && failures >= MAX_TILE_ERROR_ATTEMPTS;
         task.entry.abandoned = abandoned;
         this.scheduleRetryLocked(task.entry, System.nanoTime() + retryDelayNanos(failures));
      }

      if (error instanceof RuntimeException && failures == 1) {
         Tellus.LOGGER.warn("Failed to load {} map tile {}", task.source.providerName(), task.entry.pos, error);
      } else if (abandoned) {
         Tellus.LOGGER.debug("Giving up on {} map tile {} after {} attempts: {}", task.source.providerName(), task.entry.pos, failures, error.toString());
      } else {
         // Refusals and outages of the whole server are logged once by its gate.
         Tellus.LOGGER.debug("{} map tile {} failed (attempt {}): {}", task.source.providerName(), task.entry.pos, failures, error.toString());
      }
   }

   private static boolean concernsTileOnly(Throwable error) {
      return error instanceof MapTileDownloader.TileResponseException response && response.concernsTileOnly()
         || error instanceof MapTileDownloader.TileTooLargeException
         || error instanceof SlippyMapTileCache.InvalidTileException;
   }

   private Path cachePath(SlippyMapTileCache.TileLoadTask task) {
      return task.source.cacheDirectory(this.cacheRoot).resolve(task.entry.pos.getCacheName());
   }

   private void deleteCachedTile(SlippyMapTileCache.TileLoadTask task) {
      try {
         Files.deleteIfExists(this.cachePath(task));
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to delete broken map tile {}", this.cachePath(task), error);
      }
   }

   private byte[] readTileData(SlippyMapTileCache.TileLoadTask task) throws IOException {
      Path cachePath = this.cachePath(task);
      byte[] cached = readCachedTile(cachePath);
      if (cached != null) {
         return cached;
      }
      if (this.isCancelled(task)) {
         throw new InterruptedIOException("Map tile request was cancelled");
      }

      byte[] downloaded = DOWNLOADER.download(
         URI.create(task.source.tileUrl(task.entry.pos)), task.source.gate(), MAX_TILE_BYTES, new MapTileDownloader.Request() {
            @Override
            public boolean cancelled() {
               return SlippyMapTileCache.this.isCancelled(task);
            }

            @Override
            public boolean commit() {
               return SlippyMapTileCache.this.commit(task);
            }
         }
      );
      byte[] data;
      try {
         MapTileImageValidator.validatePng(downloaded, MAX_TILE_DIMENSION, MAX_TILE_DIMENSION);
         // Cache the scaled tile, so reading it back from disk needs no scaling.
         data = MapTileImages.fitToTileSize(downloaded, SlippyMap.TILE_SIZE);
      } catch (IOException invalid) {
         throw new SlippyMapTileCache.InvalidTileException(task.source.providerName() + " sent an unusable tile for " + task.entry.pos, invalid);
      }
      // Saved even when the tile is no longer wanted, since it may be wanted again.
      if (TellusCacheRegistry.isCurrent(TellusCacheDomain.OSM, task.generation)) {
         this.cacheData(cachePath, data, task.generation);
      }
      return data;
   }

   /** The cached tile, or null when there is none; a broken or oversized file is deleted. */
   private static byte[] readCachedTile(Path cachePath) throws IOException {
      if (!Files.isRegularFile(cachePath)) {
         return null;
      }
      if (Files.size(cachePath) <= MAX_TILE_BYTES) {
         try (InputStream input = new BufferedInputStream(Files.newInputStream(cachePath))) {
            byte[] data = MapTileImageValidator.readBounded(input, MAX_TILE_BYTES);
            MapTileImageValidator.validatePng(data, MAX_TILE_DIMENSION, MAX_TILE_DIMENSION);
            return data;
         } catch (IOException invalidCache) {
            Tellus.LOGGER.debug("Discarding unreadable cached map tile {}", cachePath, invalidCache);
         }
      }
      Files.deleteIfExists(cachePath);
      return null;
   }

   private void cacheData(Path cachePath, byte[] data, long generation) {
      try {
         TellusCacheFiles.writeBytesIfCurrent(TellusCacheDomain.OSM, generation, cachePath, data);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to cache map tile {}", cachePath, error);
      }
   }

   private static long retryDelayNanos(int failures) {
      int shift = Math.min(Math.max(0, failures - 1), 5);
      return Math.min(RETRY_MAX_NANOS, RETRY_BASE_NANOS << shift);
   }

   private static Map<SlippyMapTilePos, Integer> buildViewportPriorities(List<SlippyMapTilePos> visibleTiles) {
      if (visibleTiles == null || visibleTiles.isEmpty()) {
         return Map.of();
      }

      int viewZoom = visibleTiles.get(0).getZoom();
      double minX = Double.POSITIVE_INFINITY;
      double maxX = Double.NEGATIVE_INFINITY;
      double minY = Double.POSITIVE_INFINITY;
      double maxY = Double.NEGATIVE_INFINITY;
      for (SlippyMapTilePos pos : visibleTiles) {
         if (isValid(pos) && pos.getZoom() == viewZoom) {
            minX = Math.min(minX, pos.getX());
            maxX = Math.max(maxX, pos.getX());
            minY = Math.min(minY, pos.getY());
            maxY = Math.max(maxY, pos.getY());
         }
      }
      if (!Double.isFinite(minX)) {
         return Map.of();
      }

      double centerX = (minX + maxX + 1.0) * 0.5;
      double centerY = (minY + maxY + 1.0) * 0.5;
      Map<SlippyMapTilePos, Integer> priorities = new HashMap<>();
      for (SlippyMapTilePos visible : visibleTiles) {
         if (!isValid(visible) || visible.getZoom() != viewZoom) {
            continue;
         }

         SlippyMapTilePos candidate = visible;
         int depth = 0;
         while (candidate.getZoom() >= SlippyMap.MIN_ZOOM) {
            int scale = 1 << depth;
            double projectedX = (candidate.getX() + 0.5) * scale;
            double projectedY = (candidate.getY() + 0.5) * scale;
            int spatialPriority = (int)Math.min(10_000.0, Math.round((Math.abs(projectedX - centerX) + Math.abs(projectedY - centerY)) * 100.0));
            int layerPriority = depth == 0 ? 0 : depth == 1 ? 150 : 400 + depth * 50;
            int priority = layerPriority + spatialPriority;
            priorities.merge(candidate, priority, Math::min);
            if (candidate.getZoom() == SlippyMap.MIN_ZOOM) {
               break;
            }
            candidate = new SlippyMapTilePos(candidate.getX() >> 1, candidate.getY() >> 1, candidate.getZoom() - 1);
            depth++;
         }
      }
      return priorities;
   }

   private static boolean isValid(SlippyMapTilePos pos) {
      int zoom = pos.getZoom();
      if (zoom < SlippyMap.MIN_ZOOM || zoom > SlippyMap.MAX_ZOOM) {
         return false;
      }
      int size = 1 << zoom;
      return pos.getX() >= 0 && pos.getY() >= 0 && pos.getX() < size && pos.getY() < size;
   }

   private static final class TileEntry {
      private final SlippyMapTilePos pos;
      private final SlippyMapTile tile;
      private SlippyMapTileCache.TileLoadTask task;
      private boolean loaded;
      /** Set once the server has answered this tile with errors often enough; it is not asked for again. */
      private boolean abandoned;
      private int failures;
      private boolean retryScheduled;
      private long retryAtNanos;

      private TileEntry(SlippyMapTilePos pos, String textureKey) {
         this.pos = pos;
         this.tile = new SlippyMapTile(pos, textureKey);
      }
   }

   private final class TileLoadTask implements Runnable, Comparable<SlippyMapTileCache.TileLoadTask> {
      private final SlippyMapTileCache.TileEntry entry;
      private final MapTileSource source;
      private final long generation;
      private final long revision;
      private final int priority;
      private final long sequence;
      private volatile boolean cancelled;
      /** A worker has taken the task: it is reading the cached tile or waiting for the server. */
      private volatile boolean started;
      /** The request has been sent; from now on the download finishes even when the tile is no longer wanted. */
      private volatile boolean committed;

      private TileLoadTask(SlippyMapTileCache.TileEntry entry, MapTileSource source, long generation, long revision, int priority, long sequence) {
         this.entry = entry;
         this.source = source;
         this.generation = generation;
         this.revision = revision;
         this.priority = priority;
         this.sequence = sequence;
      }

      @Override
      public void run() {
         if (this.cancelled) {
            return;
         }
         this.started = true;
         SlippyMapTileCache.this.loadTile(this);
      }

      @Override
      public int compareTo(SlippyMapTileCache.TileLoadTask other) {
         int revisionOrder = Long.compare(other.revision, this.revision);
         if (revisionOrder != 0) {
            return revisionOrder;
         }
         int priorityOrder = Integer.compare(this.priority, other.priority);
         return priorityOrder != 0 ? priorityOrder : Long.compare(this.sequence, other.sequence);
      }
   }

   /** The server sent something that is not a usable tile image. */
   private static final class InvalidTileException extends IOException {
      private InvalidTileException(String message, Throwable cause) {
         super(message, cause);
      }
   }
}
