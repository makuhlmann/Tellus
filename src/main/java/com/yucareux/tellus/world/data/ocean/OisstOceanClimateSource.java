package com.yucareux.tellus.world.data.ocean;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.yucareux.tellus.Tellus;
import com.yucareux.tellus.cache.TellusCacheDomain;
import com.yucareux.tellus.cache.TellusCacheFiles;
import com.yucareux.tellus.cache.TellusCacheHandle;
import com.yucareux.tellus.cache.TellusCacheRegistry;
import com.yucareux.tellus.integration.distant_horizons.managed.ManagedTerrainNetworkPolicy;
import com.yucareux.tellus.platform.TellusPlatform;
import com.yucareux.tellus.world.data.source.InputStreamSafety;
import com.yucareux.tellus.worldgen.EarthProjection;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.minecraft.util.Mth;

/**
 * Annual ocean climate (mean sea-surface temperature and peak sea-ice fraction) on the 0.25 degree
 * NOAA OISST v2 grid, from the 1991-2020 monthly long-term means published by NOAA PSL over OPeNDAP.
 *
 * <p>Ice counts only in months cold enough for it to be real ({@link #peakFreezingIce}), and coastal waters the grid
 * counts as land take the climate of the nearest sea cell ({@link #nearestSeaSample}).</p>
 *
 * <p>Cells are fetched and cached in 60x60 blocks (15 x 15 degrees). A PSL request takes a few seconds
 * whatever its size, so one request per large block, instead of one per cell or small block, is what
 * keeps Distant Horizons from waiting on thousands of kilometres of ocean.</p>
 */
public final class OisstOceanClimateSource implements TellusCacheHandle {
   private static final double GRID_DEGREES = 0.25;
   private static final double MIN_GRID_LAT = -89.875;
   private static final int LAT_CELL_COUNT = 720;
   private static final int LON_CELL_COUNT = 1440;
   // Divides both grid dimensions (720 x 1440), so every block is complete.
   private static final int BLOCK_CELLS = 60;
   private static final int BLOCK_AREA = BLOCK_CELLS * BLOCK_CELLS;
   private static final int MONTHS = 12;
   private static final String DEFAULT_BASE_URL = "https://psl.noaa.gov/thredds/dodsC/Datasets/noaa.oisst.v2.highres";
   private static final String BASE_URL = System.getProperty("tellus.oisst.baseUrl", DEFAULT_BASE_URL);
   private static final String SST_FILE = "sst.mon.ltm.1991-2020.nc";
   private static final String ICE_FILE = "icec.mon.ltm.1991-2020.nc";
   private static final String SOURCE_CACHE_ID = Integer.toUnsignedString((BASE_URL + '|' + SST_FILE + '|' + ICE_FILE).hashCode(), 16);
   private static final double MIN_VALID_SST_C = -3.0;
   private static final double MAX_VALID_SST_C = 45.0;
   private static final int HTTP_CONNECT_TIMEOUT = intProperty("tellus.oisst.connectTimeoutMs", 8000);
   private static final int HTTP_READ_TIMEOUT = intProperty("tellus.oisst.readTimeoutMs", 15000);
   private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
   // No sea ice occurs in the 1991-2020 climatology this close to the equator.
   private static final double ICE_FREE_MAX_ABS_LATITUDE = 30.0;
   // Three degrees of latitude (about 330 km): reaches past the land mask at the inner end of estuaries and fjords.
   static final int COASTAL_FILL_RADIUS_CELLS = 12;
   // A month's ice counts only when that month's mean water temperature is this cold (see peakFreezingIce).
   static final double ICE_MONTH_MAX_SST_C = 1.5;
   // The cached ice column is the peak over freezing months since this layout; blocks of the old one are fetched again.
   private static final String CACHE_LAYOUT = "ltm-1991-2020-block" + BLOCK_CELLS + "-freezing-ice";
   private static final long FAILED_BLOCK_RETRY_NANOS = intProperty("tellus.oisst.failedBlockRetryMs", 60_000) * 1_000_000L;
   private static final long PROVISIONAL_SECONDS = 10L;
   private static final int MAX_CACHE_CELLS = intProperty("tellus.oisst.cacheCells", 4096);
   private static final int MAX_CACHE_BLOCKS = intProperty("tellus.oisst.cacheBlocks", 64);
   private static final String HTTP_USER_AGENT = "Tellus/1.0 (Minecraft Mod)";
   private static final Map<Integer, int[]> SEARCH_ORDER = new ConcurrentHashMap<>();
   // A retired dataset answers HTTP 404. Stop asking once that is known this session.
   private static volatile boolean endpointMissing;
   private final Path cacheRoot;
   private final Cache<OisstOceanClimateSource.CellKey, OisstOceanClimateSource.Sample> cache;
   private final Cache<OisstOceanClimateSource.CellKey, OisstOceanClimateSource.Sample> provisionalOnline;
   private final Cache<OisstOceanClimateSource.CellKey, OisstOceanClimateSource.Sample> provisionalCacheOnly;
   private final LoadingCache<OisstOceanClimateSource.BlockKey, OisstOceanClimateSource.Sample[]> blocks;
   private final Map<OisstOceanClimateSource.BlockKey, Long> failedBlocks = new ConcurrentHashMap<>();

   public OisstOceanClimateSource() {
      this.cacheRoot = TellusPlatform.gameDir().resolve("tellus/cache/ocean-oisst-v21");
      this.cache = CacheBuilder.newBuilder().maximumSize(MAX_CACHE_CELLS).build();
      this.provisionalOnline = CacheBuilder.newBuilder()
         .maximumSize(MAX_CACHE_CELLS)
         .expireAfterWrite(PROVISIONAL_SECONDS, TimeUnit.SECONDS)
         .build();
      this.provisionalCacheOnly = CacheBuilder.newBuilder()
         .maximumSize(MAX_CACHE_CELLS)
         .expireAfterWrite(PROVISIONAL_SECONDS, TimeUnit.SECONDS)
         .build();
      this.blocks = CacheBuilder.newBuilder()
         .maximumSize(MAX_CACHE_BLOCKS)
         .build(new CacheLoader<OisstOceanClimateSource.BlockKey, OisstOceanClimateSource.Sample[]>() {
            public OisstOceanClimateSource.Sample[] load(OisstOceanClimateSource.BlockKey key) throws IOException {
               return OisstOceanClimateSource.this.loadBlock(key);
            }
         });
      TellusCacheRegistry.register(this);
   }

   public OisstOceanClimateSource.Sample sample(double blockX, double blockZ, double worldScale) {
      if (worldScale <= 0.0) {
         return fallbackForLatitude(0.0);
      }

      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      if (blocksPerDegree <= 0.0) {
         return fallbackForLatitude(0.0);
      }

      double lat = EarthProjection.blockZToLat(blockZ, worldScale);
      OisstOceanClimateSource.CellKey key = cellKeyForLatLon(lat, blockX / blocksPerDegree);
      if (key == null) {
         return fallbackForLatitude(lat);
      }

      return this.sampleCell(key);
   }

   /**
    * A cell's climate, the same whatever order cells and blocks load in. Only a result worked out from every block it
    * needed is kept for the session; one missing a block (not downloaded yet while Distant Horizons generates from the
    * cache alone, or a failed download) is kept for a few seconds, separately for each mode, and then worked out again.
    */
   private OisstOceanClimateSource.Sample sampleCell(OisstOceanClimateSource.CellKey key) {
      OisstOceanClimateSource.Sample settled = this.cache.getIfPresent(key);
      if (settled != null) {
         return settled;
      }
      if (endpointMissing) {
         return fallbackForLatitude(key.latCenter());
      }

      boolean allowFetch = !ManagedTerrainNetworkPolicy.isCacheOnly();
      Cache<OisstOceanClimateSource.CellKey, OisstOceanClimateSource.Sample> provisional = allowFetch
         ? this.provisionalOnline
         : this.provisionalCacheOnly;
      OisstOceanClimateSource.Sample pending = provisional.getIfPresent(key);
      if (pending != null) {
         return pending;
      }

      boolean[] incomplete = new boolean[1];
      OisstOceanClimateSource.Sample resolved = this.resolveCell(key, allowFetch, incomplete);
      if (incomplete[0]) {
         provisional.put(key, resolved);
      } else {
         this.cache.put(key, resolved);
      }
      return resolved;
   }

   public boolean preloadAreaInputs(
      int minBlockX,
      int minBlockZ,
      int maxBlockX,
      int maxBlockZ,
      double worldScale,
      int completedUnits,
      BiConsumer<Integer, String> progress
   ) {
      if (worldScale <= 0.0) {
         return true;
      }
      double blocksPerDegree = EarthProjection.blocksPerDegree(worldScale);
      int step = Math.max(16, (int)Math.floor(blocksPerDegree * GRID_DEGREES * 0.8));
      Set<OisstOceanClimateSource.CellKey> cells = new LinkedHashSet<>();
      for (long z = minBlockZ; z <= (long)maxBlockZ + step; z += step) {
         double sampleZ = Math.min(maxBlockZ, z);
         double latitude = EarthProjection.blockZToLat(sampleZ, worldScale);
         for (long x = minBlockX; x <= (long)maxBlockX + step; x += step) {
            double sampleX = Math.min(maxBlockX, x);
            OisstOceanClimateSource.CellKey key = cellKeyForLatLon(latitude, sampleX / blocksPerDegree);
            if (key != null) {
               cells.add(key);
            }
         }
      }

      boolean available = true;
      int finished = 0;
      for (OisstOceanClimateSource.CellKey key : cells) {
         try {
            available &= this.sampleCell(key).available();
         } catch (RuntimeException error) {
            available = false;
         }
         finished++;
         if (progress != null) {
            progress.accept(completedUnits + finished, "Ocean climate " + finished + "/" + cells.size());
         }
      }
      return available;
   }

   /**
    * A cell's climate. Cells OISST counts as land, which along coasts include estuaries, bays and fjords that Tellus
    * fills with sea, take the nearest cell with sea data instead (see {@link #nearestSeaSample}). Sets
    * {@code incomplete[0]} when a block that search needed could not be read.
    */
   private OisstOceanClimateSource.Sample resolveCell(OisstOceanClimateSource.CellKey key, boolean allowFetch, boolean[] incomplete) {
      OisstOceanClimateSource.Sample[] block = this.blockSamples(key.block(), allowFetch);
      if (block == null) {
         incomplete[0] = true;
         return fallbackForLatitude(key.latCenter());
      }

      OisstOceanClimateSource.Sample own = block[key.blockOffset()];
      if (own.available()) {
         return own;
      }
      OisstOceanClimateSource.Sample nearest = nearestSeaSample(key.latIndex(), key.lonIndex(), (latIndex, lonIndex) -> {
         OisstOceanClimateSource.Sample[] neighbour = this.blockSamples(
            new OisstOceanClimateSource.BlockKey(latIndex / BLOCK_CELLS, lonIndex / BLOCK_CELLS), allowFetch
         );
         if (neighbour == null) {
            incomplete[0] = true;
            return null;
         }
         return neighbour[(latIndex % BLOCK_CELLS) * BLOCK_CELLS + lonIndex % BLOCK_CELLS];
      });
      return nearest != null ? nearest : own;
   }

   /** A block's cells: fetched when allowed, otherwise only from memory or the disk cache ({@code null} if neither). */
   private OisstOceanClimateSource.Sample[] blockSamples(OisstOceanClimateSource.BlockKey key, boolean allowFetch) {
      if (allowFetch) {
         Long retryAt = this.failedBlocks.get(key);
         if (retryAt != null && System.nanoTime() - retryAt < 0L) {
            return this.blocks.getIfPresent(key);
         }
         try {
            OisstOceanClimateSource.Sample[] block = this.blocks.get(key);
            this.failedBlocks.remove(key);
            return block;
         } catch (ExecutionException | UncheckedExecutionException error) {
            // Coastal cells near a block edge each ask for the neighbouring block; offline, ask once a minute at most.
            this.failedBlocks.put(key, System.nanoTime() + FAILED_BLOCK_RETRY_NANOS);
            Tellus.LOGGER.debug("Falling back from NOAA OISST v2 block {}", key, error.getCause());
            return null;
         }
      }

      OisstOceanClimateSource.Sample[] block = this.blocks.getIfPresent(key);
      if (block == null) {
         block = this.readCachedBlock(key);
         if (block != null) {
            this.blocks.put(key, block);
         }
      }
      return block;
   }

   /**
    * The cell with sea data nearest to a cell OISST counts as land, within {@link #COASTAL_FILL_RADIUS_CELLS} cells
    * north, south, east or west, or {@code null} when there is none. East-west steps are shortened by the cosine of the
    * latitude, as they are on the globe, and wrap around the 180th meridian. Equally near cells are tried north to
    * south, then west to east, so the choice does not depend on the order cells were loaded in.
    */
   static OisstOceanClimateSource.Sample nearestSeaSample(int latIndex, int lonIndex, OisstOceanClimateSource.CellLookup lookup) {
      int[] order = SEARCH_ORDER.computeIfAbsent(latIndex, OisstOceanClimateSource::searchOrder);
      for (int i = 0; i < order.length; i += 2) {
         OisstOceanClimateSource.Sample sample = lookup.sample(latIndex + order[i], Math.floorMod(lonIndex + order[i + 1], LON_CELL_COUNT));
         if (sample != null && sample.available()) {
            return sample;
         }
      }
      return null;
   }

   /** The offsets around a cell in this grid row, nearest first, as (latitude, longitude) pairs. */
   private static int[] searchOrder(int latIndex) {
      double cosLatitude = Math.cos(Math.toRadians(MIN_GRID_LAT + latIndex * GRID_DEGREES));
      int side = COASTAL_FILL_RADIUS_CELLS * 2 + 1;
      List<int[]> offsets = new ArrayList<>(side * side - 1);
      for (int dLat = COASTAL_FILL_RADIUS_CELLS; dLat >= -COASTAL_FILL_RADIUS_CELLS; dLat--) {
         for (int dLon = -COASTAL_FILL_RADIUS_CELLS; dLon <= COASTAL_FILL_RADIUS_CELLS; dLon++) {
            if ((dLat != 0 || dLon != 0) && latIndex + dLat >= 0 && latIndex + dLat < LAT_CELL_COUNT) {
               offsets.add(new int[]{dLat, dLon});
            }
         }
      }
      // A stable sort keeps the north-to-south, west-to-east order among equal distances.
      offsets.sort(Comparator.comparingDouble(offset -> offset[0] * offset[0] + square(offset[1] * cosLatitude)));
      int[] order = new int[offsets.size() * 2];
      for (int i = 0; i < offsets.size(); i++) {
         order[i * 2] = offsets.get(i)[0];
         order[i * 2 + 1] = offsets.get(i)[1];
      }
      return order;
   }

   private static double square(double value) {
      return value * value;
   }

   private OisstOceanClimateSource.Sample[] loadBlock(OisstOceanClimateSource.BlockKey key) throws IOException {
      OisstOceanClimateSource.Sample[] cached = this.readCachedBlock(key);
      if (cached != null) {
         return cached;
      }
      if (ManagedTerrainNetworkPolicy.isCacheOnly()) {
         throw new IOException("Network access is disabled during managed Distant Horizons generation");
      }

      long generation = TellusCacheRegistry.generation(TellusCacheDomain.OISST);
      double[] sst = this.fetchMonthlyGrid(SST_FILE, "sst", key);
      // A failed ice download (NOAA PSL answers HTTP 429 when asked too often) fails the block, to be fetched again
      // later: a block cached without its ice would never turn freezing seas into frozen ocean.
      double[] ice = iceFree(key) ? null : this.fetchMonthlyGrid(ICE_FILE, "icec", key);

      OisstOceanClimateSource.Sample[] samples = new OisstOceanClimateSource.Sample[BLOCK_AREA];
      for (int offset = 0; offset < BLOCK_AREA; offset++) {
         double sum = 0.0;
         int count = 0;
         for (int month = 0; month < MONTHS; month++) {
            double value = sst[month * BLOCK_AREA + offset];
            if (Double.isFinite(value)) {
               sum += value;
               count++;
            }
         }
         double peakIce = ice == null ? 0.0 : peakFreezingIce(sst, ice, offset, BLOCK_AREA);
         double latCenter = MIN_GRID_LAT + (key.latBlock() * BLOCK_CELLS + offset / BLOCK_CELLS) * GRID_DEGREES;
         samples[offset] = count == 0
            ? fallbackForLatitude(latCenter)
            : new OisstOceanClimateSource.Sample(stored(sum / count), stored(Mth.clamp(peakIce, 0.0, 1.0)), true);
      }
      this.writeCachedBlock(key, samples, generation);
      return samples;
   }

   /**
    * A cell's peak monthly ice fraction, counting only months whose mean water temperature is at most
    * {@link #ICE_MONTH_MAX_SST_C}. Values are month-major with {@code monthStride} cells per month.
    *
    * <p>The satellite ice record picks up land along coasts: Kiel Bay reads over 40% ice from December to April, at 2
    * to 5 C, though it freezes only in hard winters. Where sea ice really forms, the monthly mean temperature stays
    * near freezing while it lasts (Bothnian Bay, the Gulf of Finland, the Gulf of Riga, Liaodong Bay: 1 C or less).</p>
    */
   static double peakFreezingIce(double[] sst, double[] ice, int offset, int monthStride) {
      double peak = 0.0;
      for (int month = 0; month < MONTHS; month++) {
         double temperature = sst[month * monthStride + offset];
         double fraction = ice[month * monthStride + offset];
         if (Double.isFinite(temperature) && temperature <= ICE_MONTH_MAX_SST_C && Double.isFinite(fraction)) {
            peak = Math.max(peak, fraction);
         }
      }
      return peak;
   }

   /** Returns the block's 12 monthly values, month-major then row-major; missing values are NaN. */
   private double[] fetchMonthlyGrid(String file, String variable, OisstOceanClimateSource.BlockKey key) throws IOException {
      int latStart = key.latBlock() * BLOCK_CELLS;
      int lonStart = key.lonBlock() * BLOCK_CELLS;
      String url = BASE_URL + "/" + file + ".ascii?" + variable
         + "%5B0:1:" + (MONTHS - 1) + "%5D"
         + "%5B" + latStart + ":1:" + (latStart + BLOCK_CELLS - 1) + "%5D"
         + "%5B" + lonStart + ":1:" + (lonStart + BLOCK_CELLS - 1) + "%5D";
      HttpURLConnection connection = (HttpURLConnection)URI.create(url).toURL().openConnection();
      connection.setConnectTimeout(HTTP_CONNECT_TIMEOUT);
      connection.setReadTimeout(HTTP_READ_TIMEOUT);
      connection.setRequestProperty("User-Agent", HTTP_USER_AGENT);
      boolean reusable = false;
      try {
         int status = connection.getResponseCode();
         if (status == 404 && !endpointMissing) {
            endpointMissing = true;
            Tellus.LOGGER.warn("NOAA OISST climatology unavailable (HTTP 404); using latitude ocean climate defaults this session");
         }
         if (status < 200 || status >= 300) {
            throw new IOException("NOAA OISST climatology returned HTTP " + status);
         }
         long contentLength = connection.getContentLengthLong();
         if (contentLength > MAX_RESPONSE_BYTES) {
            throw new IOException("NOAA OISST climatology response exceeds the safety limit");
         }

         byte[] response;
         try (InputStream input = connection.getInputStream()) {
            response = InputStreamSafety.readAllBytes(input, MAX_RESPONSE_BYTES, "NOAA OISST response");
         }
         reusable = true;
         return parseAsciiGrid(response, variable, BLOCK_CELLS);
      } finally {
         if (!reusable) {
            connection.disconnect();
         }
      }
   }

   private static boolean iceFree(OisstOceanClimateSource.BlockKey key) {
      double southEdge = MIN_GRID_LAT - GRID_DEGREES * 0.5 + key.latBlock() * BLOCK_CELLS * GRID_DEGREES;
      double northEdge = southEdge + BLOCK_CELLS * GRID_DEGREES;
      return southEdge >= -ICE_FREE_MAX_ABS_LATITUDE && northEdge <= ICE_FREE_MAX_ABS_LATITUDE;
   }

   /**
    * Parses an OPeNDAP ASCII grid response of shape [12][cells][cells]: after the {@code var.var[..]}
    * header, each line is {@code [month][row], v0, ..., v(cells-1)}.
    */
   static double[] parseAsciiGrid(byte[] response, String variable, int cells) throws IOException {
      int area = cells * cells;
      double[] values = new double[MONTHS * area];
      Arrays.fill(values, Double.NaN);
      String header = variable + "." + variable + "[";
      int rows = 0;
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(response), StandardCharsets.UTF_8))) {
         String line;
         boolean inData = false;
         while ((line = reader.readLine()) != null) {
            if (!inData) {
               inData = line.startsWith(header);
               continue;
            }
            if (line.isBlank()) {
               break;
            }
            int close = line.indexOf(']');
            int secondClose = close < 0 ? -1 : line.indexOf(']', close + 1);
            if (!line.startsWith("[") || secondClose < 0) {
               throw new IOException("Unexpected NOAA OISST grid row");
            }
            int month = Integer.parseInt(line.substring(1, close).trim());
            int row = Integer.parseInt(line.substring(close + 2, secondClose).trim());
            String[] parts = line.substring(secondClose + 1).split(",");
            if (month < 0 || month >= MONTHS || row < 0 || row >= cells || parts.length != cells + 1) {
               throw new IOException("Unexpected NOAA OISST grid shape");
            }
            for (int column = 0; column < cells; column++) {
               double value = parseDouble(parts[column + 1]);
               values[month * area + row * cells + column] = validValue(variable, value);
            }
            rows++;
         }
      } catch (NumberFormatException error) {
         throw new IOException("Malformed NOAA OISST grid", error);
      }
      if (rows != MONTHS * cells) {
         throw new IOException("Incomplete NOAA OISST grid (" + rows + " rows)");
      }
      return values;
   }

   private static double validValue(String variable, double value) {
      if (!Double.isFinite(value)) {
         return Double.NaN;
      }
      if ("sst".equals(variable)) {
         return value >= MIN_VALID_SST_C && value <= MAX_VALID_SST_C ? value : Double.NaN;
      }
      double fraction = value > 1.5 ? value / 100.0 : value;
      return fraction >= 0.0 && fraction <= 1.0 ? fraction : Double.NaN;
   }

   private OisstOceanClimateSource.Sample[] readCachedBlock(OisstOceanClimateSource.BlockKey key) {
      Path path = this.cachePath(key);
      if (!Files.isRegularFile(path)) {
         return null;
      }

      try {
         List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
         if (lines.size() < BLOCK_AREA) {
            return null;
         }
         OisstOceanClimateSource.Sample[] samples = new OisstOceanClimateSource.Sample[BLOCK_AREA];
         for (int offset = 0; offset < BLOCK_AREA; offset++) {
            String[] parts = lines.get(offset).trim().split(",", -1);
            if (parts.length < 2) {
               return null;
            }
            double meanSstC = Double.parseDouble(parts[0]);
            double maxIceFraction = Double.parseDouble(parts[1]);
            double latCenter = MIN_GRID_LAT + (key.latBlock() * BLOCK_CELLS + offset / BLOCK_CELLS) * GRID_DEGREES;
            // Cells without ocean data (land) are stored as NaN and resolve to the latitude default.
            samples[offset] = Double.isFinite(meanSstC)
               ? new OisstOceanClimateSource.Sample(meanSstC, Mth.clamp(maxIceFraction, 0.0, 1.0), true)
               : fallbackForLatitude(latCenter);
         }
         return samples;
      } catch (IOException | NumberFormatException error) {
         Tellus.LOGGER.debug("Failed to read cached NOAA OISST block {}", key, error);
         return null;
      }
   }

   private void writeCachedBlock(OisstOceanClimateSource.BlockKey key, OisstOceanClimateSource.Sample[] samples, long generation) {
      StringBuilder value = new StringBuilder(BLOCK_AREA * 16);
      for (OisstOceanClimateSource.Sample sample : samples) {
         if (sample.available()) {
            value.append(storedText(sample.meanSstC())).append(',').append(storedText(sample.maxIceFraction())).append('\n');
         } else {
            value.append("NaN,0\n");
         }
      }
      try {
         TellusCacheFiles.writeStringIfCurrent(TellusCacheDomain.OISST, generation, this.cachePath(key), value.toString(), StandardCharsets.UTF_8);
      } catch (IOException error) {
         Tellus.LOGGER.debug("Failed to persist NOAA OISST block {}", key, error);
      }
   }

   /**
    * A value as the disk cache keeps it (four decimals), so a block read back in a later session gives exactly the
    * climate it gave when it was downloaded, even for a sea right at a biome threshold.
    */
   static double stored(double value) {
      return Double.parseDouble(storedText(value));
   }

   private static String storedText(double value) {
      return String.format(Locale.ROOT, "%.4f", value);
   }

   private Path cachePath(OisstOceanClimateSource.BlockKey key) {
      return this.cacheRoot
         .resolve(SOURCE_CACHE_ID)
         .resolve(CACHE_LAYOUT)
         .resolve(String.format(Locale.ROOT, "%02d_%02d.txt", key.latBlock(), key.lonBlock()));
   }

   private static OisstOceanClimateSource.CellKey cellKeyForLatLon(double lat, double lon) {
      if (!Double.isFinite(lat) || !Double.isFinite(lon)) {
         return null;
      }

      double clampedLat = Mth.clamp(lat, -89.999999, 89.999999);
      double normalizedLon = lon % 360.0;
      if (normalizedLon < 0.0) {
         normalizedLon += 360.0;
      }

      int latIndex = Mth.clamp((int)Math.floor((clampedLat + 90.0) / GRID_DEGREES), 0, LAT_CELL_COUNT - 1);
      int lonIndex = Mth.clamp((int)Math.floor(normalizedLon / GRID_DEGREES), 0, LON_CELL_COUNT - 1);
      double latCenter = MIN_GRID_LAT + latIndex * GRID_DEGREES;
      double lonCenter = 0.125 + lonIndex * GRID_DEGREES;
      return new OisstOceanClimateSource.CellKey(latIndex, lonIndex, latCenter, lonCenter);
   }

   private static OisstOceanClimateSource.Sample fallbackForLatitude(double lat) {
      double absLat = Math.abs(lat);
      if (absLat >= 70.0) {
         return new OisstOceanClimateSource.Sample(-1.8, 0.6, false);
      } else if (absLat >= 55.0) {
         return new OisstOceanClimateSource.Sample(6.0, 0.0, false);
      } else {
         return absLat >= 35.0
            ? new OisstOceanClimateSource.Sample(16.0, 0.0, false)
            : new OisstOceanClimateSource.Sample(26.0, 0.0, false);
      }
   }

   private static double parseDouble(String value) {
      if (value == null || value.isBlank()) {
         return Double.NaN;
      }

      try {
         return Double.parseDouble(value.trim());
      } catch (NumberFormatException error) {
         return Double.NaN;
      }
   }

   private static int intProperty(String key, int defaultValue) {
      String value = System.getProperty(key);
      if (value == null) {
         return defaultValue;
      }

      try {
         return Math.max(1, Integer.parseInt(value));
      } catch (NumberFormatException error) {
         return defaultValue;
      }
   }

   @Override
   public TellusCacheDomain cacheDomain() {
      return TellusCacheDomain.OISST;
   }

   @Override
   public void clearCache() {
      this.cache.invalidateAll();
      this.cache.cleanUp();
      this.provisionalOnline.invalidateAll();
      this.provisionalCacheOnly.invalidateAll();
      this.blocks.invalidateAll();
      this.blocks.cleanUp();
      this.failedBlocks.clear();
   }

   public record Sample(double meanSstC, double maxIceFraction, boolean available) {
   }

   /** Reads one cell of the global grid, or {@code null} when its block is unavailable. */
   @FunctionalInterface
   interface CellLookup {
      OisstOceanClimateSource.Sample sample(int latIndex, int lonIndex);
   }

   private record CellKey(int latIndex, int lonIndex, double latCenter, double lonCenter) {
      BlockKey block() {
         return new BlockKey(this.latIndex / BLOCK_CELLS, this.lonIndex / BLOCK_CELLS);
      }

      int blockOffset() {
         return (this.latIndex % BLOCK_CELLS) * BLOCK_CELLS + this.lonIndex % BLOCK_CELLS;
      }
   }

   private record BlockKey(int latBlock, int lonBlock) {
   }
}
