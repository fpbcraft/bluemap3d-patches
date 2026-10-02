package dev.duzo.bluemap3d.runtime;

import com.google.gson.stream.JsonWriter;
import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.BlueMap3D;
import dev.duzo.bluemap3d.api.InstancedSceneObject;
import dev.duzo.bluemap3d.api.SceneInstance;
import dev.duzo.bluemap3d.api.SceneInstanceGroup;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import dev.duzo.bluemap3d.bake.BakedMesh;
import dev.duzo.bluemap3d.bake.VolumeMesher;
import dev.duzo.bluemap3d.publish.WebRootPublisher;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks logical scene objects separately from immutable mesh resources.
 *
 * <p>Many objects may share one geometryKey. InstancedSceneObject goes further: one
 * logical object owns compact arrays of transforms for one or more shared prototypes,
 * which the browser renders with THREE.InstancedMesh.
 */
public final class SceneObjectTracker {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Tracker");

    private final WebRootPublisher publisher;
    private final VolumeMesher mesher;
    private final MapLookup mapLookup;
    private final ExecutorService baker;

    @FunctionalInterface
    public interface MapLookup {
        List<String> mapIdsFor(ServerLevel level);
    }

    /** provider + NUL + geometryKey -> latest immutable shared mesh. */
    private final Map<String, Ready> geometryReady = new ConcurrentHashMap<>();
    /** provider/object -> publication associations announced to history consumers. */
    private final Map<String, Map<String, Ready>> objectPublications = new ConcurrentHashMap<>();
    /** provider + geometry key + version currently being baked. */
    private final Set<String> baking = ConcurrentHashMap.newKeySet();

    private final AtomicLong bakeCount = new AtomicLong();
    private final AtomicLong bakeNanos = new AtomicLong();

    private int tickCounter;
    private int lastPublishedCount = -1;
    private int lastFeedBytes;
    private volatile Map<String, ProviderPerf> lastProviderPerf = Map.of();

    private record Ready(long version, String meshUrl) {}
    private record GroupRow(String id, String meshUrl, List<SceneInstance> instances) {}
    private record Row(
            String provider,
            String id,
            String label,
            String dimension,
            String meshUrl,
            Vec3 position,
            Quaternionf rotation,
            Vector3f scale,
            List<GroupRow> groups) {}
    private record ProviderPerf(long enumerateNanos, int objects, int groups, int instances) {}
    private static final class MutablePerf {
        long nanos;
        int objects;
        int groups;
        int instances;

        ProviderPerf freeze() {
            return new ProviderPerf(nanos, objects, groups, instances);
        }
    }

    private java.util.function.IntSupplier tilesVersion = () -> 0;
    private java.util.function.Supplier<Map<String, List<int[]>>> dirtyTiles = Map::of;

    public void setTilesVersionSupplier(java.util.function.IntSupplier supplier) {
        this.tilesVersion = supplier;
    }

    public void setDirtyTilesSupplier(java.util.function.Supplier<Map<String, List<int[]>>> supplier) {
        this.dirtyTiles = supplier;
    }

    public SceneObjectTracker(WebRootPublisher publisher, VolumeMesher mesher, MapLookup mapLookup) {
        this.publisher = publisher;
        this.mesher = mesher;
        this.mapLookup = mapLookup;
        this.baker = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "BlueMap3D-Baker");
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 2);
            return thread;
        });
    }

    public void tick(MinecraftServer server, int intervalTicks) {
        if (!BlueMap3D.hasProviders()) return;
        if (++tickCounter < intervalTicks) return;
        tickCounter = 0;

        List<Row> rows = new ArrayList<>();
        Set<String> seenObjects = new HashSet<>();
        Map<String, List<String>> dimensionMaps = new LinkedHashMap<>();
        Map<String, MutablePerf> perf = new LinkedHashMap<>();

        for (ServerLevel level : server.getAllLevels()) {
            boolean any = false;
            for (SceneObjectProvider provider : BlueMap3D.providers()) {
                MutablePerf providerPerf =
                        perf.computeIfAbsent(provider.id(), ignored -> new MutablePerf());
                any |= collect(provider, level, rows, seenObjects, providerPerf);
            }
            if (any) {
                dimensionMaps.computeIfAbsent(
                        level.dimension().location().toString(),
                        ignored -> mapLookup.mapIdsFor(level));
            }
        }

        // Publication identity is logical-object scoped. Shared mesh files themselves are
        // deliberately retained: another live object or historical replay may still use
        // the same geometryKey/version.
        for (String objectKey : List.copyOf(objectPublications.keySet())) {
            if (seenObjects.contains(objectKey)) continue;
            int slash = objectKey.indexOf('/');
            if (slash > 0) {
                String provider = objectKey.substring(0, slash);
                for (String publicationId : objectPublications
                        .getOrDefault(objectKey, Map.of()).keySet()) {
                    BlueMap3D.meshRemoved(provider, publicationId);
                }
            }
            objectPublications.remove(objectKey);
        }

        Map<String, ProviderPerf> frozen = new LinkedHashMap<>();
        perf.forEach((id, value) -> frozen.put(id, value.freeze()));
        lastProviderPerf = Map.copyOf(frozen);

        publish(rows, dimensionMaps, intervalTicks);
    }

    private boolean collect(
            SceneObjectProvider provider,
            ServerLevel level,
            List<Row> rows,
            Set<String> seen,
            MutablePerf perf) {
        String providerId = provider.id();
        long started = System.nanoTime();
        Iterable<? extends SceneObject> objects;

        try {
            objects = provider.objects(level);
        } catch (RuntimeException error) {
            perf.nanos += System.nanoTime() - started;
            LOGGER.error("Provider '{}' threw while listing objects", providerId, error);
            return false;
        }

        boolean any = false;
        if (objects != null) {
            for (SceneObject object : objects) {
                if (object == null) continue;
                any = true;
                perf.objects++;

                String objectKey = providerId + "/" + object.id();
                seen.add(objectKey);

                if (object instanceof InstancedSceneObject instanced) {
                    collectInstanced(providerId, objectKey, instanced, rows, perf);
                } else {
                    collectRigid(providerId, objectKey, object, rows);
                }
            }
        }

        perf.nanos += System.nanoTime() - started;
        return any;
    }

    private void collectRigid(
            String providerId,
            String objectKey,
            SceneObject object,
            List<Row> rows) {
        Ready ready = ensureGeometry(
                providerId,
                object.geometryKey(),
                object.geometryVersion(),
                object.canBakeGeometry(),
                object::geometry);

        if (ready == null) return;

        if (ready.version() == object.geometryVersion()) {
            publishAssociation(
                    providerId,
                    objectKey,
                    object.id(),
                    ready);
        }

        rows.add(new Row(
                providerId,
                object.id(),
                object.label(),
                object.dimension().location().toString(),
                ready.meshUrl(),
                object.position(),
                object.rotation(),
                object.scale(),
                List.of()));
    }

    private void collectInstanced(
            String providerId,
            String objectKey,
            InstancedSceneObject object,
            List<Row> rows,
            MutablePerf perf) {
        List<GroupRow> groups = new ArrayList<>();
        Set<String> currentPublicationIds = new HashSet<>();

        for (SceneInstanceGroup group : object.instanceGroups()) {
            perf.groups++;
            perf.instances += group.instances().size();

            Ready ready = ensureGeometry(
                    providerId,
                    group.geometryKey(),
                    group.geometryVersion(),
                    group.canBakeGeometry(),
                    group::geometry);
            if (ready == null) continue;

            String publicationId = object.id() + "/@group/" + group.id();
            currentPublicationIds.add(publicationId);
            if (ready.version() == group.geometryVersion()) {
                publishAssociation(providerId, objectKey, publicationId, ready);
            }
            groups.add(new GroupRow(group.id(), ready.meshUrl(), group.instances()));
        }

        Map<String, Ready> publications = objectPublications.get(objectKey);
        if (publications != null) {
            for (String previousId : List.copyOf(publications.keySet())) {
                if (currentPublicationIds.contains(previousId)) continue;
                publications.remove(previousId);
                BlueMap3D.meshRemoved(providerId, previousId);
            }
            if (publications.isEmpty()) objectPublications.remove(objectKey, publications);
        }

        if (groups.isEmpty()) return;

        rows.add(new Row(
                providerId,
                object.id(),
                object.label(),
                object.dimension().location().toString(),
                null,
                object.position(),
                object.rotation(),
                object.scale(),
                List.copyOf(groups)));
    }

    private void publishAssociation(
            String providerId,
            String objectKey,
            String publicationId,
            Ready ready) {
        Map<String, Ready> publications =
                objectPublications.computeIfAbsent(objectKey, ignored -> new ConcurrentHashMap<>());
        Ready previous = publications.put(publicationId, ready);
        if (previous == null
                || previous.version() != ready.version()
                || !previous.meshUrl().equals(ready.meshUrl())) {
            BlueMap3D.meshPublished(
                    providerId,
                    publicationId,
                    ready.version(),
                    ready.meshUrl());
        }
    }

    private Ready ensureGeometry(
            String providerId,
            String geometryKey,
            long version,
            boolean canBake,
            java.util.function.Supplier<BlockVolume> geometry) {
        String resourceKey = providerId + "\u0000" + geometryKey;
        Ready current = geometryReady.get(resourceKey);
        if (current != null && current.version() == version) {
            return current;
        }

        String existing = publisher.existingMeshUrl(providerId, geometryKey, version);
        if (existing != null) {
            Ready restored = new Ready(version, existing);
            geometryReady.put(resourceKey, restored);
            return restored;
        }

        if (canBake) {
            requestBake(providerId, geometryKey, resourceKey, version, geometry);
        }

        // A different version is known-stale topology. Do not keep it in the feed while
        // the replacement bakes: for a Sable hull that would overlap a newly assembled
        // child contraption. The logical object reappears when the requested version is ready.
        return current != null && current.version() == version ? current : null;
    }

    private void requestBake(
            String providerId,
            String geometryKey,
            String resourceKey,
            long version,
            java.util.function.Supplier<BlockVolume> geometry) {
        String bakeKey = resourceKey + "\u0000" + version;
        if (!baking.add(bakeKey)) return;

        BlockVolume volume;
        try {
            volume = geometry.get();
        } catch (RuntimeException error) {
            LOGGER.error(
                    "Provider '{}' threw building shared geometry '{}'",
                    providerId,
                    geometryKey,
                    error);
            baking.remove(bakeKey);
            return;
        }

        baker.execute(() -> {
            long started = System.nanoTime();
            try {
                BakedMesh mesh = mesher.mesh(volume);
                if (mesh.isEmpty()) {
                    LOGGER.debug("'{}:{}' meshed to nothing", providerId, geometryKey);
                    return;
                }
                String url = publisher.writeMesh(providerId, geometryKey, version, mesh);
                geometryReady.put(resourceKey, new Ready(version, url));
                bakeCount.incrementAndGet();
                LOGGER.debug(
                        "Published shared geometry {}/{} v{}: {} triangles",
                        providerId,
                        geometryKey,
                        version,
                        mesh.triangleCount());
            } catch (IOException | RuntimeException error) {
                LOGGER.error(
                        "Failed to bake or publish shared geometry '{}/{}'",
                        providerId,
                        geometryKey,
                        error);
            } finally {
                bakeNanos.addAndGet(System.nanoTime() - started);
                baking.remove(bakeKey);
            }
        });
    }

    private void publish(
            List<Row> rows,
            Map<String, List<String>> dimensionMaps,
            int intervalTicks) {
        try {
            String json = toJson(
                    rows,
                    dimensionMaps,
                    intervalTicks,
                    tilesVersion.getAsInt(),
                    dev.duzo.bluemap3d.Config.TILE_RELOAD_MIN_SECONDS.get(),
                    dirtyTiles.get(),
                    lastProviderPerf,
                    lastFeedBytes,
                    bakeCount.get(),
                    bakeNanos.get(),
                    baking.size());
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            publisher.writeFeed(json);
            lastFeedBytes = bytes.length;

            if (rows.size() != lastPublishedCount) {
                int instances = rows.stream()
                        .flatMap(row -> row.groups().stream())
                        .mapToInt(group -> group.instances().size())
                        .sum();
                LOGGER.info(
                        "Publishing {} logical 3D object(s), {} instance(s), {} bytes",
                        rows.size(),
                        instances,
                        bytes.length);
                lastPublishedCount = rows.size();
            }
        } catch (IOException error) {
            LOGGER.error("Could not write the live feed", error);
        }
    }

    private static String toJson(
            List<Row> rows,
            Map<String, List<String>> dimensionMaps,
            int intervalTicks,
            int tilesVersion,
            int tileReloadMinSeconds,
            Map<String, List<int[]>> dirtyTiles,
            Map<String, ProviderPerf> providerPerf,
            int previousFeedBytes,
            long bakeCount,
            long bakeNanos,
            int bakesInFlight) throws IOException {
        StringWriter out = new StringWriter(256 + rows.size() * 180);
        try (JsonWriter json = new JsonWriter(out)) {
            json.beginObject();
            json.name("intervalMs").value(intervalTicks * 50L);
            json.name("tilesVersion").value(tilesVersion);
            json.name("tileReloadMinMs").value(tileReloadMinSeconds * 1000L);

            json.name("dirtyTiles").beginObject();
            for (Map.Entry<String, List<int[]>> entry : dirtyTiles.entrySet()) {
                json.name(entry.getKey()).beginArray();
                for (int[] tile : entry.getValue()) {
                    json.beginArray().value(tile[0]).value(tile[1]).endArray();
                }
                json.endArray();
            }
            json.endObject();

            json.name("maps").beginObject();
            for (Map.Entry<String, List<String>> entry : dimensionMaps.entrySet()) {
                json.name(entry.getKey()).beginArray();
                for (String mapId : entry.getValue()) json.value(mapId);
                json.endArray();
            }
            json.endObject();

            json.name("perf").beginObject();
            json.name("previousFeedBytes").value(previousFeedBytes);
            json.name("bakeCount").value(bakeCount);
            json.name("bakeMs").value(round(bakeNanos / 1_000_000.0));
            json.name("bakesInFlight").value(bakesInFlight);
            json.name("providers").beginObject();
            for (Map.Entry<String, ProviderPerf> entry : providerPerf.entrySet()) {
                ProviderPerf perf = entry.getValue();
                json.name(entry.getKey()).beginObject();
                json.name("enumerateMs").value(round(perf.enumerateNanos() / 1_000_000.0));
                json.name("objects").value(perf.objects());
                json.name("groups").value(perf.groups());
                json.name("instances").value(perf.instances());
                // Allocation proxy: Java scene wrappers + instance transform records.
                json.name("allocationProxy").value(
                        perf.objects() + perf.groups() + perf.instances());
                json.endObject();
            }
            json.endObject();
            json.endObject();

            json.name("objects").beginArray();
            for (Row row : rows) {
                json.beginObject();
                json.name("id").value(row.provider() + "/" + row.id());
                json.name("provider").value(row.provider());
                json.name("dimension").value(row.dimension());
                if (row.meshUrl() != null) json.name("mesh").value(row.meshUrl());
                if (row.label() != null) json.name("label").value(row.label());

                json.name("pos").beginArray()
                        .value(round(row.position().x))
                        .value(round(row.position().y))
                        .value(round(row.position().z))
                        .endArray();
                Quaternionf rotation = row.rotation();
                json.name("rot").beginArray()
                        .value(round(rotation.x))
                        .value(round(rotation.y))
                        .value(round(rotation.z))
                        .value(round(rotation.w))
                        .endArray();
                Vector3f scale = row.scale();
                json.name("scale").beginArray()
                        .value(round(scale.x))
                        .value(round(scale.y))
                        .value(round(scale.z))
                        .endArray();

                if (!row.groups().isEmpty()) {
                    json.name("groups").beginArray();
                    for (GroupRow group : row.groups()) {
                        json.beginObject();
                        json.name("id").value(group.id());
                        json.name("mesh").value(group.meshUrl());
                        json.name("instances").beginArray();
                        for (SceneInstance instance : group.instances()) {
                            json.beginArray();
                            Vec3 p = instance.position();
                            Quaternionf q = instance.rotation();
                            Vector3f sc = instance.scale();
                            json.value(round(p.x)).value(round(p.y)).value(round(p.z));
                            json.value(round(q.x)).value(round(q.y))
                                    .value(round(q.z)).value(round(q.w));
                            json.value(round(sc.x)).value(round(sc.y)).value(round(sc.z));
                            json.endArray();
                        }
                        json.endArray();
                        json.endObject();
                    }
                    json.endArray();
                }

                json.endObject();
            }
            json.endArray();
            json.endObject();
        }
        return out.toString();
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    public void shutdown() {
        baker.shutdownNow();
        geometryReady.clear();
        baking.clear();
        objectPublications.clear();
        BlueMap3D.clearPublishedMeshes();
    }
}
