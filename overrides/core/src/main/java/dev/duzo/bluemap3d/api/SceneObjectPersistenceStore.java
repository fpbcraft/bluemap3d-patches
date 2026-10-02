package dev.duzo.bluemap3d.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.bluecolored.bluemap.api.BlueMapAPI;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Persistent snapshot storage used by {@link PersistentSceneObjectProvider}. */
final class SceneObjectPersistenceStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Persistence");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            Path.of("config", "bluemap3d", "cache", "scene-objects.json");
    private static final long SAVE_INTERVAL_NANOS = 5_000_000_000L;
    private static final int CACHE_FORMAT_VERSION = 5;

    private static final Object LOCK = new Object();
    private static final Map<String, SavedObject> SAVED = new ConcurrentHashMap<>();
    private static final Map<String, SavedObject> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, Long> PUBLISHED = new ConcurrentHashMap<>();

    private static boolean loaded;
    private static boolean dirty;
    private static long lastWriteNanos;

    private SceneObjectPersistenceStore() {}

    static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;

            if (Files.isRegularFile(FILE)) {
                try (var reader = Files.newBufferedReader(FILE)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    JsonArray entries = null;
                    int sourceFormat = 1;

                    if (parsed.isJsonArray()) {
                        entries = parsed.getAsJsonArray();
                    } else if (parsed.isJsonObject()) {
                        JsonObject root = parsed.getAsJsonObject();
                        if (root.has("formatVersion")) {
                            sourceFormat = root.get("formatVersion").getAsInt();
                        }
                        if (root.has("objects") && root.get("objects").isJsonArray()) {
                            entries = root.getAsJsonArray("objects");
                        }
                    }

                    if (entries != null) {
                        int droppedCreate = 0;
                        int droppedSable = 0;
                        int droppedSprings = 0;
                        int droppedRopes = 0;

                        boolean preV3 = sourceFormat < 3;
                        boolean preV4 = sourceFormat < 4;
                        boolean preV5 = sourceFormat < 5;

                        for (JsonElement element : entries) {
                            SavedObject saved = GSON.fromJson(element, SavedObject.class);
                            if (saved == null || !saved.valid()) continue;

                            if (ScenePersistencePolicy.shouldDropForMigration(
                                    saved.provider, sourceFormat)) {
                                if (preV3 && "create_contraptions".equals(saved.provider)) {
                                    droppedCreate++;
                                } else if (preV4 && "sable_ships".equals(saved.provider)) {
                                    droppedSable++;
                                } else if (preV3 && "simulated_springs".equals(saved.provider)) {
                                    droppedSprings++;
                                } else if (preV5 && "simulated_ropes".equals(saved.provider)) {
                                    droppedRopes++;
                                }
                                dirty = true;
                                continue;
                            }

                            SAVED.put(key(saved.provider, saved.id), saved);
                            PUBLISHED.put(key(saved.provider, saved.id), saved.version);
                        }

                        if (droppedCreate > 0 || droppedSable > 0
                                || droppedSprings > 0 || droppedRopes > 0) {
                            LOGGER.info(
                                    "Migrated scene cache v{} -> v{}: dropped {} Create, {} Sable, "
                                            + "{} spring and {} rope snapshot(s); authoritative "
                                            + "providers will repopulate current objects",
                                    sourceFormat,
                                    CACHE_FORMAT_VERSION,
                                    droppedCreate,
                                    droppedSable,
                                    droppedSprings,
                                    droppedRopes);
                        }
                        if (sourceFormat < CACHE_FORMAT_VERSION) dirty = true;
                    }
                } catch (IOException | RuntimeException error) {
                    LOGGER.warn("Could not read generic scene-object cache {}", FILE, error);
                }
            } else {
                bootstrapPreviousFeed();
            }

            if (!SAVED.isEmpty()) {
                LOGGER.info("Restored {} persistent BlueMap3D scene object(s)", SAVED.size());
            }
        }
    }

    static void meshPublished(String provider, String objectId, long version) {
        ensureLoaded();
        String key = key(provider, objectId);
        PUBLISHED.put(key, version);
        SavedObject pending = PENDING.get(key);
        if (pending != null && pending.version == version) {
            PENDING.remove(key);
            saveSnapshot(pending);
            flushIfDue();
        }
    }

    static void flushNow() {
        ensureLoaded();
        synchronized (LOCK) {
            writeLocked();
        }
    }

    static void stage(String provider, SceneObject object) {
        SavedObject snapshot = SavedObject.from(provider, object);
        String key = key(provider, object.id());
        PENDING.put(key, snapshot);

        Long published = PUBLISHED.get(key);
        if (published != null && published == snapshot.version) {
            PENDING.remove(key);
            saveSnapshot(snapshot);
        }
    }

    static void remove(String provider, String objectId) {
        String key = key(provider, objectId);
        PENDING.remove(key);
        PUBLISHED.remove(key);
        if (SAVED.remove(key) != null) dirty = true;
    }

    static void pruneAuthoritativelyMissing(
            String provider,
            String dimension,
            Collection<String> prefixes,
            Set<String> liveIds) {
        if (prefixes == null || prefixes.isEmpty()) return;

        for (String prefix : prefixes) {
            if (prefix == null || prefix.isBlank()) continue;
            for (SavedObject saved : List.copyOf(SAVED.values())) {
                if (!ScenePersistencePolicy.isAuthoritativelyMissing(
                        provider,
                        dimension,
                        prefix,
                        saved.provider,
                        saved.dimension,
                        saved.id,
                        liveIds.contains(saved.id))) {
                    continue;
                }
                remove(provider, saved.id);
            }
        }
    }

    static List<SceneObject> cached(
            String provider,
            String dimension,
            ServerLevel level,
            Set<String> liveIds) {
        List<SceneObject> out = new ArrayList<>();
        for (SavedObject saved : SAVED.values()) {
            if (!provider.equals(saved.provider) || !dimension.equals(saved.dimension)) continue;
            if (liveIds.contains(saved.id)) continue;
            out.add(saved.cached(level));
        }
        return List.copyOf(out);
    }

    static void flushIfDue() {
        long now = System.nanoTime();
        if (!dirty || now - lastWriteNanos < SAVE_INTERVAL_NANOS) return;
        synchronized (LOCK) {
            if (dirty && now - lastWriteNanos >= SAVE_INTERVAL_NANOS) {
                writeLocked();
            }
        }
    }

    private static void saveSnapshot(SavedObject snapshot) {
        String key = key(snapshot.provider, snapshot.id);
        SavedObject previous = SAVED.put(key, snapshot);
        if (!snapshot.equals(previous)) dirty = true;
    }

    private static String key(String provider, String objectId) {
        return provider + "\u0000" + objectId;
    }

    private static void bootstrapPreviousFeed() {
        try {
            var api = BlueMapAPI.getInstance();
            if (api.isEmpty()) return;
            Path feed = api.get().getWebApp().getWebRoot()
                    .resolve("assets/bluemap3d/entities3d.json");
            if (!Files.isRegularFile(feed)) return;

            JsonObject root;
            try (var reader = Files.newBufferedReader(feed)) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonObject()) return;
                root = parsed.getAsJsonObject();
            }

            JsonArray objects = root.getAsJsonArray("objects");
            if (objects == null) return;

            for (JsonElement element : objects) {
                if (!element.isJsonObject()) continue;
                JsonObject row = element.getAsJsonObject();
                if (!row.has("provider") || !row.has("id") || !row.has("dimension")
                        || !row.has("mesh")) continue;

                String provider = row.get("provider").getAsString();

                if ("sable_ships".equals(provider)
                        || "simulated_ropes".equals(provider)) continue;

                String fullId = row.get("id").getAsString();
                String id = fullId.startsWith(provider + "/")
                        ? fullId.substring(provider.length() + 1)
                        : fullId;
                long version = versionFromMesh(row.get("mesh").getAsString());
                if (version == Long.MIN_VALUE) continue;

                JsonArray pos = row.getAsJsonArray("pos");
                JsonArray rot = row.getAsJsonArray("rot");
                JsonArray scale = row.has("scale") ? row.getAsJsonArray("scale") : null;
                if (pos == null || pos.size() != 3 || rot == null || rot.size() != 4) continue;

                SavedObject saved = new SavedObject(
                        provider,
                        id,
                        row.get("dimension").getAsString(),
                        row.has("label") ? row.get("label").getAsString() : null,
                        version,
                        pos.get(0).getAsDouble(),
                        pos.get(1).getAsDouble(),
                        pos.get(2).getAsDouble(),
                        rot.get(0).getAsFloat(),
                        rot.get(1).getAsFloat(),
                        rot.get(2).getAsFloat(),
                        rot.get(3).getAsFloat(),
                        scale != null && scale.size() == 3 ? scale.get(0).getAsFloat() : 1f,
                        scale != null && scale.size() == 3 ? scale.get(1).getAsFloat() : 1f,
                        scale != null && scale.size() == 3 ? scale.get(2).getAsFloat() : 1f);
                if (!saved.valid()) continue;
                SAVED.put(key(provider, id), saved);
                PUBLISHED.put(key(provider, id), version);
            }

            if (!SAVED.isEmpty()) {
                dirty = true;
                writeLocked();
                LOGGER.info(
                        "Bootstrapped {} generic scene-object snapshot(s) from previous BlueMap3D feed",
                        SAVED.size());
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Could not bootstrap generic scene cache from previous feed", error);
        }
    }

    private static long versionFromMesh(String mesh) {
        int marker = mesh.lastIndexOf("-v");
        int separator = marker < 0 ? -1 : mesh.indexOf('-', marker + 2);
        int end = mesh.endsWith(".bm3d") ? mesh.length() - 5 : mesh.length();
        if (separator < 0 || separator + 1 >= end) return Long.MIN_VALUE;
        try {
            return Long.parseLong(mesh.substring(separator + 1, end));
        } catch (NumberFormatException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static void writeLocked() {
        if (!dirty) return;
        try {
            Files.createDirectories(FILE.getParent());
            Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
            try (var writer = Files.newBufferedWriter(tmp)) {
                JsonObject root = new JsonObject();
                root.addProperty("formatVersion", CACHE_FORMAT_VERSION);
                root.add("objects", GSON.toJsonTree(new ArrayList<>(SAVED.values())));
                GSON.toJson(root, writer);
            }
            try {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
            lastWriteNanos = System.nanoTime();
        } catch (IOException error) {
            LOGGER.warn("Could not persist generic scene-object cache {}", FILE, error);
        }
    }

    private static final class SavedObject {
        String provider;
        String id;
        String dimension;
        String label;
        long version;
        double x;
        double y;
        double z;
        float qx;
        float qy;
        float qz;
        float qw;
        float sx;
        float sy;
        float sz;

        SavedObject() {}

        SavedObject(
                String provider,
                String id,
                String dimension,
                String label,
                long version,
                double x,
                double y,
                double z,
                float qx,
                float qy,
                float qz,
                float qw,
                float sx,
                float sy,
                float sz) {
            this.provider = provider;
            this.id = id;
            this.dimension = dimension;
            this.label = label;
            this.version = version;
            this.x = x;
            this.y = y;
            this.z = z;
            this.qx = qx;
            this.qy = qy;
            this.qz = qz;
            this.qw = qw;
            this.sx = sx;
            this.sy = sy;
            this.sz = sz;
        }

        static SavedObject from(String provider, SceneObject object) {
            Vec3 position = object.position();
            Quaternionf rotation = object.rotation();
            Vector3f scale = object.scale();
            return new SavedObject(
                    provider,
                    object.id(),
                    object.dimension().location().toString(),
                    object.label(),
                    object.geometryVersion(),
                    position.x,
                    position.y,
                    position.z,
                    rotation.x,
                    rotation.y,
                    rotation.z,
                    rotation.w,
                    scale.x,
                    scale.y,
                    scale.z);
        }

        boolean valid() {
            return provider != null && !provider.isBlank()
                    && id != null && !id.isBlank()
                    && dimension != null && !dimension.isBlank()
                    && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z)
                    && Float.isFinite(qx) && Float.isFinite(qy)
                    && Float.isFinite(qz) && Float.isFinite(qw)
                    && Float.isFinite(sx) && Float.isFinite(sy) && Float.isFinite(sz);
        }

        SceneObject cached(ServerLevel level) {
            ResourceKey<Level> dimensionKey = ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse(dimension));
            Vec3 position = new Vec3(x, y, z);
            Quaternionf rotation = new Quaternionf(qx, qy, qz, qw);
            Vector3f scale = new Vector3f(sx, sy, sz);

            return new SceneObject() {
                @Override public String id() { return SavedObject.this.id; }
                @Override public BlockVolume geometry() { return BlockVolume.EMPTY; }
                @Override public boolean canBakeGeometry() { return false; }
                @Override public long geometryVersion() { return version; }
                @Override public Vec3 position() { return position; }
                @Override public Quaternionf rotation() { return new Quaternionf(rotation); }
                @Override public Vector3f scale() { return new Vector3f(scale); }
                @Override public String label() { return label; }
                @Override public ResourceKey<Level> dimension() { return dimensionKey; }
            };
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SavedObject that)) return false;
            return version == that.version
                    && Double.compare(x, that.x) == 0
                    && Double.compare(y, that.y) == 0
                    && Double.compare(z, that.z) == 0
                    && Float.compare(qx, that.qx) == 0
                    && Float.compare(qy, that.qy) == 0
                    && Float.compare(qz, that.qz) == 0
                    && Float.compare(qw, that.qw) == 0
                    && Float.compare(sx, that.sx) == 0
                    && Float.compare(sy, that.sy) == 0
                    && Float.compare(sz, that.sz) == 0
                    && Objects.equals(provider, that.provider)
                    && Objects.equals(id, that.id)
                    && Objects.equals(dimension, that.dimension)
                    && Objects.equals(label, that.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(provider, id, dimension, label, version,
                    x, y, z, qx, qy, qz, qw, sx, sy, sz);
        }
    }
}
