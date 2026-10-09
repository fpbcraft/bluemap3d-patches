package dev.duzo.bluemapseasons;

import com.google.gson.Gson;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapMapImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.BmMap;
import de.bluecolored.bluemap.core.world.Chunk;
import de.bluecolored.bluemap.core.world.ChunkConsumer;
import de.bluecolored.bluemap.core.world.Region;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;

/** Background, bounded saved-region indexing. Seasonal changes never queue terrain renders. */
public final class SeasonalLayer implements AutoCloseable {
    private static final Gson GSON = new Gson();
    private final BlueMapAPI api;
    private final Path root;
    private final ScheduledExecutorService worker;
    private volatile Capture capture;
    private volatile boolean closed;
    private final ArrayDeque<Job> jobs = new ArrayDeque<>();
    private Scan scan;
    private long nextScan;
    private String epoch = "";
    private String lastState = "";
    private Map<String, Integer> ids = Map.of();
    private int indexed;
    private Capture described;
    private Map<String, Object> mapDescriptions = Map.of();

    private record Capture(SeasonalPalette palette, List<BmMap> maps, long capturedAt) {}
    private record Job(BmMap map, int x, int z) {}
    private record Coordinate(int x, int z, int modified) {}
    private static final class Scan {
        final Job job;
        final Region<Chunk> region;
        final Path target;
        final String fingerprint;
        final List<Coordinate> chunks;
        final BufferedImage image = new BufferedImage(512, 1024, BufferedImage.TYPE_INT_RGB);
        int offset;
        Scan(Job job, Region<Chunk> region, Path target, String fingerprint, List<Coordinate> chunks) {
            this.job = job; this.region = region; this.target = target;
            this.fingerprint = fingerprint; this.chunks = chunks;
        }
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty("bluemap.compat.ecliptic.dynamic", "true"));
    }

    public SeasonalLayer(BlueMapAPI api) throws IOException {
        this.api = api;
        root = api.getWebApp().getWebRoot().resolve("assets/bluemap-seasons");
        Files.createDirectories(root);
        for (String file : List.of("loader.js", "seasonal.js")) {
            try (var input = SeasonalLayer.class.getResourceAsStream("/bluemap-seasons/" + file)) {
                if (input == null) throw new IOException("Missing seasonal browser resource " + file);
                atomicWrite(root.resolve(file), input.readAllBytes());
            }
        }
        // A stable loader avoids accumulating registered URLs after upgrades.
        api.getWebApp().registerScript("assets/bluemap-seasons/loader.js");
        atomicWrite(root.resolve("state.json"), "{\"version\":1,\"maps\":{}}".getBytes(StandardCharsets.UTF_8));
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BlueMap-seasonal-atlas"); t.setDaemon(true); return t;
        });
        worker.scheduleWithFixedDelay(this::step, 0, 25, TimeUnit.MILLISECONDS);
    }

    /** Called on the server thread; publishes only immutable snapshots to the worker. */
    public void capture(Object server, String term) throws ReflectiveOperationException {
        SeasonalPalette palette = SeasonalPalette.capture(server, term);
        Object overworld = server.getClass().getMethod("overworld").invoke(server);
        List<BmMap> maps = new ArrayList<>();
        api.getWorld(overworld).ifPresent(world -> world.getMaps().forEach(map -> {
            if (map instanceof BlueMapMapImpl impl) maps.add(impl.map());
        }));
        capture = new Capture(palette, List.copyOf(maps), System.currentTimeMillis());
    }

    public void clear() { capture = null; }

    private void step() {
        if (closed) return;
        try {
            Capture current = capture;
            if (current == null) {
                publish(Map.of("version", 1, "maps", Map.of()));
                return;
            }
            if (current != described) {
                String nextEpoch = digest("surface-v1:" + current.palette.biomes().stream().map(SeasonalPalette.Entry::id).toList().toString());
                if (!epoch.equals(nextEpoch)) {
                    epoch = nextEpoch; scan = null; jobs.clear(); nextScan = 0;
                    Map<String, Integer> lookup = new HashMap<>();
                    for (int i = 0; i < current.palette.biomes().size(); i++) lookup.put(current.palette.biomes().get(i).id(), i + 1);
                    ids = Map.copyOf(lookup);
                }
                Map<String, Object> maps = new TreeMap<>();
                for (BmMap map : current.maps) {
                    if (map.getWorld().getRegionGrid().getGridSize().getX() != 512
                            || map.getWorld().getRegionGrid().getGridSize().getY() != 512) continue;
                    maps.put(map.getId(), Map.of("atlas", epoch + "/" + mapKey(map),
                            "minY", map.getMapSettings().getMinPos().getY(), "maxY", map.getMapSettings().getMaxPos().getY()));
                }
                mapDescriptions = maps;
                publish(Map.of("version", 1, "maps", maps, "palette", current.palette.biomes(),
                        "term", current.palette.term(), "updated", current.capturedAt / 10000,
                        "indexedRegions", indexed,
                        "tint", Boolean.parseBoolean(System.getProperty("bluemap.compat.ecliptic.tint", "true")),
                        "snow", Boolean.parseBoolean(System.getProperty("bluemap.compat.ecliptic.snow", "true"))));
                described = current;
            }
            if (scan == null && jobs.isEmpty() && System.currentTimeMillis() >= nextScan) {
                for (BmMap map : current.maps) {
                    if (!mapDescriptions.containsKey(map.getId())) continue;
                    var grid = map.getWorld().getRegionGrid();
                    var filter = map.getMapSettings().getCellRenderBoundariesFilter(grid, true);
                    Set<String> present = new HashSet<>();
                    for (var pos : map.getWorld().listRegions()) {
                        if (filter.test(pos)) {
                            jobs.add(new Job(map, pos.getX(), pos.getY()));
                            present.add(pos.getX() + "_" + pos.getY() + ".png");
                        }
                    }
                    Path directory = root.resolve(epoch).resolve(mapKey(map));
                    if (Files.isDirectory(directory)) try (var files = Files.list(directory)) {
                        for (Path file : files.filter(p -> p.toString().endsWith(".png")).toList())
                            if (!present.contains(file.getFileName().toString())) {
                                Files.deleteIfExists(file);
                                Files.deleteIfExists(Path.of(file + ".stamp"));
                            }
                    }
                }
                nextScan = System.currentTimeMillis() + 300_000;
            }
            if (scan == null && !jobs.isEmpty()) prepare(jobs.removeFirst());
            if (scan != null) advance();
        } catch (Exception failure) {
            scan = null;
            Logger.global.logWarning("Seasonal atlas: " + failure);
        }
    }

    private String mapKey(BmMap map) {
        // Config changes cannot accidentally reuse a surface index with different render bounds.
        return digest(map.getId() + map.getWorld().getId() + map.getMapSettings().getMinPos()
                + map.getMapSettings().getMaxPos() + map.getMapSettings().getMinInhabitedTime());
    }

    private void prepare(Job job) throws IOException {
        Path target = root.resolve(epoch).resolve(mapKey(job.map)).resolve(job.x + "_" + job.z + ".png");
        Region<Chunk> region = job.map.getWorld().getRegion(job.x, job.z);
        List<Coordinate> chunks = new ArrayList<>();
        region.iterateAllChunks((ChunkConsumer.ListOnly<Chunk>) (x, z, modified) -> chunks.add(new Coordinate(x, z, modified)));
        chunks.sort(Comparator.comparingInt(Coordinate::x).thenComparingInt(Coordinate::z));
        String fingerprint = digest(chunks.toString() + dev.duzo.bluemapcompat.SeasonalCompatPolicy.revision());
        Path stamp = Path.of(target + ".stamp");
        if (Files.exists(target) && Files.exists(stamp) && Files.readString(stamp).equals(fingerprint)) return;
        scan = new Scan(job, region, target, fingerprint, chunks);
    }

    private void advance() throws IOException {
        Scan s = scan;
        // Bound decoded chunks per batch, retaining at most one region image. No live chunk APIs.
        int end = Math.min(s.offset + 32, s.chunks.size());
        Set<Long> wanted = new HashSet<>();
        for (int i = s.offset; i < end; i++) {
            Coordinate c = s.chunks.get(i); wanted.add(((long)c.x << 32) ^ (c.z & 0xffffffffL));
        }
        s.region.iterateAllChunks(new ChunkConsumer<>() {
            @Override public boolean filter(int x, int z, int modified) {
                return wanted.contains(((long)x << 32) ^ (z & 0xffffffffL));
            }
            @Override public void accept(int cx, int cz, Chunk chunk) {
                if (closed) return;
                if (chunk == Chunk.ERRORED_CHUNK) throw new IllegalStateException("Saved chunk could not be read");
                var settings = s.job.map.getMapSettings();
                if (chunk.getInhabitedTime() < settings.getMinInhabitedTime()) return;
                int min = Math.max(settings.getMinPos().getY(), s.job.map.getWorld().getDimensionType().getMinY());
                int max = Math.min(settings.getMaxPos().getY(), s.job.map.getWorld().getDimensionType().getMinY()
                        + s.job.map.getWorld().getDimensionType().getHeight() - 1);
                for (int dz = 0; dz < 16; dz++) for (int dx = 0; dx < 16; dx++) {
                    int x = cx * 16 + dx, z = cz * 16 + dz;
                    if (!settings.isInsideRenderBoundaries(x, z)) continue;
                    if (!s.job.map.getTileFilter().test(s.job.map.getHiresModelManager().getTileGrid().getCell(new com.flowpowered.math.vector.Vector2i(x, z)))) continue;
                    SeasonalSurface surface = SeasonalSurface.sample(chunk, x, z, min, max);
                    boolean explicit = dev.duzo.bluemapcompat.SeasonalCompatPolicy.hasTint(chunk.getBlockState(x, surface.height() - 1, z));
                    int biome = ids.getOrDefault(surface.biome(), 0);
                    if (biome == 0) continue;
                    int px = Math.floorMod(x, 512), pz = Math.floorMod(z, 512);
                    int flags = surface.kind() | (surface.snow() ? 4 : 0) | (explicit ? 8 : 0);
                    s.image.setRGB(px, pz, (biome << 8) | flags);
                    s.image.setRGB(px, pz + 512, ((surface.height() + 32768) & 65535) << 8);
                }
            }
        });
        s.offset = end;
        if (end < s.chunks.size() || closed) return;
        Files.createDirectories(s.target.getParent());
        Path temp = Files.createTempFile(s.target.getParent(), "atlas-", ".png");
        try {
            ImageIO.write(s.image, "png", temp.toFile());
            move(temp, s.target);
            atomicWrite(Path.of(s.target + ".stamp"), s.fingerprint.getBytes(StandardCharsets.UTF_8));
        } finally { Files.deleteIfExists(temp); }
        indexed++;
        scan = null;
    }

    private synchronized void publish(Object state) throws IOException {
        if (closed) return;
        String json = GSON.toJson(state);
        if (json.equals(lastState)) return;
        atomicWrite(root.resolve("state.json"), json.getBytes(StandardCharsets.UTF_8));
        lastState = json;
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(), "season-", ".tmp");
        try { Files.write(temp, bytes); move(temp, target); }
        finally { Files.deleteIfExists(temp); }
    }

    private static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING); }
    }

    @Override public void close() {
        closed = true;
        worker.shutdownNow();
        // Serialize with publication so an in-flight worker cannot restore stale enabled state.
        synchronized (this) {
            try { atomicWrite(root.resolve("state.json"), "{\"version\":1,\"maps\":{}}".getBytes(StandardCharsets.UTF_8)); }
            catch (IOException failure) { Logger.global.logWarning("Could not disable seasonal state: " + failure); }
        }
    }
}
