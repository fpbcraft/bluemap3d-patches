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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core-owned persistence wrapper for normal scene providers.
 *
 * <p>Every provider whose lifecycle has restoreOnLaunch=true is wrapped automatically
 * by {@link BlueMap3D#register(SceneObjectProvider)}. A missing live object is treated
 * as temporarily unavailable; only {@link SceneObjectProvider#deletedObjectIds(ServerLevel)}
 * removes a saved object.
 *
 * <p>Snapshots are committed only after the matching mesh version is known to be
 * published, guaranteeing restart can reuse an existing BM3D file without touching
 * unloaded game state.
 */
final class PersistentSceneObjectProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Persistence");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE =
            Path.of("config", "bluemap3d", "cache", "scene-objects.json");
    private static final long SAVE_INTERVAL_NANOS = 5_000_000_000L;
    private static final int CACHE_FORMAT_VERSION = 4;

    private static final Object LOCK = new Object();
    private static final Map<String, SavedObject> SAVED = new ConcurrentHashMap<>();
    private static final Map<String, SavedObject> PENDING = new ConcurrentHashMap<>();
    private static final Map<String, Long> PUBLISHED = new ConcurrentHashMap<>();

    private static boolean loaded;
    private static boolean dirty;
    private static long lastWriteNanos;

    private final SceneObjectProvider delegate;

    private PersistentSceneObjectProvider(SceneObjectProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static SceneObjectProvider wrap(SceneObjectProvider provider) {
        if (!provider.lifecycle().restoreOnLaunch()) return provider;
        return provider instanceof PersistentSceneObjectProvider
                ? provider
                : new PersistentSceneObjectProvider(provider);
    }

    static SceneObjectProvider unwrap(SceneObjectProvider provider) {
        return provider instanceof PersistentSceneObjectProvider persistent
                ? persistent.delegate
                : provider;
    }

    static boolean wraps(SceneObjectProvider registered, SceneObjectProvider original) {
        return registered == original
                || (registered instanceof PersistentSceneObjectProvider persistent
                    && persistent.delegate == original);
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

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public SceneObjectLifecycle lifecycle() {
        return delegate.lifecycle();
    }

    @Override
    public Collection<ResourceLocation> hiddenBlocks() {
        return delegate.hiddenBlocks();
    }

    @Override
    public Collection<String> deletedObjectIds(ServerLevel level) {
        return delegate.deletedObjectIds(level);
    }

    @Override
    public Collection<String> authoritativeObjectPrefixes(ServerLevel level) {
        return delegate.authoritativeObjectPrefixes(level);
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        ensureLoaded();

        Collection<? extends SceneObject> live = delegate.objects(level);
        Map<String, SceneObject> merged = new LinkedHashMap<>();
        Set<String> liveIds = new HashSet<>();

        if (live != null) {
            for (SceneObject object : live) {
                if (object == null) continue;
                merged.put(object.id(), object);
                liveIds.add(object.id());

                SavedObject snapshot = SavedObject.from(id(), object);
                String key = key(id(), object.id());
                PENDING.put(key, snapshot);

                Long published = PUBLISHED.get(key);
                if (published != null && published == snapshot.version) {
                    PENDING.remove(key);
                    saveSnapshot(snapshot);
                }
            }
        }

        Collection<String> deleted = delegate.deletedObjectIds(level);
        if (deleted != null) {
            for (String objectId : deleted) {
                if (objectId == null) continue;
                removeSnapshot(id(), objectId);
            }
        }

        String dimension = level.dimension().location().toString();

        // A provider can positively declare a logical-child scope complete. Remove saved
        // children that no longer exist inside that scope while preserving every other
        // missing object as merely unloaded.
        Collection<String> authoritative = delegate.authoritativeObjectPrefixes(level);
        if (authoritative != null && !authoritative.isEmpty()) {
            for (String prefix : authoritative) {
                if (prefix == null || prefix.isBlank()) continue;
                for (SavedObject saved : List.copyOf(SAVED.values())) {
                    if (!id().equals(saved.provider)
                            || !dimension.equals(saved.dimension)
                            || !saved.id.startsWith(prefix)
                            || liveIds.contains(saved.id)) {
                        continue;
                    }
                    removeSnapshot(id(), saved.id);
                }
            }
        }

        for (SavedObject saved : SAVED.values()) {
            if (!id().equals(saved.provider) || !dimension.equals(saved.dimension)) continue;
            if (merged.containsKey(saved.id)) continue;
            merged.put(saved.id, saved.cached(level));
        }

        flushIfDue();
        return List.copyOf(merged.values());
    }

    private static void saveSnapshot(SavedObject snapshot) {
        String key = key(snapshot.provider, snapshot.id);
        SavedObject previous = SAVED.put(key, snapshot);
        if (!snapshot.equals(previous)) dirty = true;
    }

    private static void removeSnapshot(String provider, String objectId) {
        String key = key(provider, objectId);
        PENDING.remove(key);
        PUBLISHED.remove(key);
        if (SAVED.remove(key) != null) dirty = true;
    }

    private static String key(String provider, String objectId) {
        return provider + "\u0000" + objectId;
    }

    private static void ensureLoaded() {
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

                        // v3 introduced deletion/topology cleanup for Create and Simulated
                        // springs. v4 adds the missing positive-deletion path for Sable
                        // sub-levels themselves. Keep the migrations provider-specific so
                        // upgrading v3 -> v4 does not discard unrelated corrected snapshots.
                        boolean preV3 = sourceFormat < 3;
                        boolean preV4 = sourceFormat < 4;

                        for (JsonElement element : entries) {
                            SavedObject saved = GSON.fromJson(element, SavedObject.class);
                            if (saved == null || !saved.valid()) continue;

                            if (preV3 && "create_contraptions".equals(saved.provider)) {
                                droppedCreate++;
                                dirty = true;
                                continue;
                            }
                            if (preV4 && "sable_ships".equals(saved.provider)) {
                                droppedSable++;
                                dirty = true;
                                continue;
                            }
                            if (preV3 && "simulated_springs".equals(saved.provider)) {
                                droppedSprings++;
                                dirty = true;
                                continue;
                            }

                            SAVED.put(key(saved.provider, saved.id), saved);
                            PUBLISHED.put(key(saved.provider, saved.id), saved.version);
                        }

                        if (droppedCreate > 0 || droppedSable > 0 || droppedSprings > 0) {
                            LOGGER.info(
                                    "Migrated scene cache v{} -> v{}: dropped {} Create, {} Sable "
                                            + "and {} spring snapshot(s); authoritative providers "
                                            + "will repopulate current objects",
                                    sourceFormat,
                                    CACHE_FORMAT_VERSION,
                                    droppedCreate,
                                    droppedSable,
                                    droppedSprings);
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

    /**
     * Upgrade path: the previous live feed is itself a valid last-known snapshot, and its
     * mesh URLs prove those versions were already published. This makes the first upgrade
     * restart restore ropes/springs/vehicles without requiring a player to load them once.
     */
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

    private static void flushIfDue() {
        long now = System.nanoTime();
        if (!dirty || now - lastWriteNanos < SAVE_INTERVAL_NANOS) return;
        synchronized (LOCK) {
            if (dirty && now - lastWriteNanos >= SAVE_INTERVAL_NANOS) {
                writeLocked();
            }
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
