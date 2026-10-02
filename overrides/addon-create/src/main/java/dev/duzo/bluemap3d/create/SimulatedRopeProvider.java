package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.InstancedSceneObject;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneInstance;
import dev.duzo.bluemap3d.api.SceneInstanceGroup;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import dev.duzo.bluemap3d.compat.CompatRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Publishes each Create: Simulated rope as one logical BlueMap3D object.
 *
 * <p>The rope body and knots are two prototype groups rendered with GPU instances in the
 * browser. A twenty-edge rope therefore costs one logical feed/history object and two
 * draw calls rather than roughly forty independently managed SceneObjects.
 */
public final class SimulatedRopeProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/SimulatedRopes");
    private static final ResourceLocation ROPE_MODEL =
            ResourceLocation.fromNamespaceAndPath("simulated", "block/rope/rope");
    private static final ResourceLocation KNOT_MODEL =
            ResourceLocation.fromNamespaceAndPath("simulated", "block/rope/knot");

    private static final BlockVolume SEGMENT_GEOMETRY = BlockVolume.attachments(
            BlockPos.ZERO,
            BlockPos.ZERO,
            new Vec3(0.5, 0.5, 0.5),
            List.of(new ModelAttachment(BlockPos.ZERO, ROPE_MODEL, Map.of())));
    private static final BlockVolume KNOT_GEOMETRY = BlockVolume.attachments(
            BlockPos.ZERO,
            BlockPos.ZERO,
            Vec3.ZERO,
            List.of(new ModelAttachment(
                    BlockPos.ZERO,
                    KNOT_MODEL,
                    Map.of(),
                    new Matrix4f().translation(-0.5f, -0.5f, -0.5f))));

    private final Map<ServerLevel, Set<String>> authoritativePrefixes =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<ServerLevel, Set<String>> deletedIds =
            new java.util.concurrent.ConcurrentHashMap<>();

    private SimulatedApi api;
    private boolean discoveryAttempted;
    private boolean warned;

    @Override
    public String id() {
        return "simulated_ropes";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!CompatRegistry.get().featureEnabled("simulated.ropeRendering", true)) {
            authoritativePrefixes.put(level, Set.of());
            return List.of();
        }

        SimulatedApi access = api();
        if (access == null) {
            authoritativePrefixes.put(level, Set.of());
            return List.of();
        }

        List<SceneObject> out = new ArrayList<>();
        Set<String> legacyPrefixes = new HashSet<>();

        Set<UUID> destroyed = SimulatedRopeRegistry.drainRemoved(level);
        Set<String> removedIds = new HashSet<>();
        for (UUID id : destroyed) {
            removedIds.add(id.toString());
            legacyPrefixes.add(id + "/");
        }
        deletedIds.put(level, Set.copyOf(removedIds));

        try {
            for (Object strand : access.strands(level)) {
                RopeSnapshot rope = access.snapshot(strand);
                if (rope == null || rope.points().size() < 2) continue;

                out.add(objectOf(level, rope));
                // Upgrade cleanup: pre-instancing builds persisted segment/knot children
                // below UUID/. Once this live logical rope is known, that old child scope
                // is authoritative-empty and can be removed from generic persistence.
                legacyPrefixes.add(rope.id() + "/");
            }
            authoritativePrefixes.put(level, Set.copyOf(legacyPrefixes));
        } catch (ReflectiveOperationException | RuntimeException error) {
            authoritativePrefixes.put(level, Set.copyOf(legacyPrefixes));
            if (!warned) {
                warned = true;
                LOGGER.warn(
                        "Could not read Create: Simulated rope state; persistent snapshots remain available: {}",
                        SimulatedReflection.rootMessage(error));
            }
        }

        return List.copyOf(out);
    }

    private static SceneObject objectOf(ServerLevel level, RopeSnapshot rope) {
        List<Vec3> points = rope.points();

        List<SceneInstance> segments = new ArrayList<>(Math.max(0, points.size() - 1));
        // Simulated winches add/remove at the beginning. Emit transforms from the stable
        // END so index 0 continues to mean the same physical edge across extension.
        for (int fromEnd = 0; fromEnd < points.size() - 1; fromEnd++) {
            int endIndex = points.size() - 1 - fromEnd;
            Vec3 start = points.get(endIndex - 1);
            Vec3 end = points.get(endIndex);
            if (start.distanceToSqr(end) < 1.0e-10) continue;
            segments.add(SceneInstance.between(start, end, 1f));
        }

        List<SceneInstance> knots = new ArrayList<>(Math.max(0, points.size() - 2));
        for (int fromEnd = 1; fromEnd <= points.size() - 2; fromEnd++) {
            knots.add(SceneInstance.at(points.get(points.size() - 1 - fromEnd)));
        }

        List<SceneInstanceGroup> groups = new ArrayList<>(2);
        groups.add(new SceneInstanceGroup(
                "segments",
                "simulated-rope-segment",
                SEGMENT_GEOMETRY,
                1L,
                segments));
        if (!knots.isEmpty()) {
            groups.add(new SceneInstanceGroup(
                    "knots",
                    "simulated-rope-knot",
                    KNOT_GEOMETRY,
                    1L,
                    knots));
        }

        return new InstancedSceneObject() {
            @Override public String id() { return rope.id().toString(); }
            @Override public String label() { return "Simulated Rope"; }
            @Override public net.minecraft.resources.ResourceKey<Level> dimension() {
                return level.dimension();
            }
            @Override public List<SceneInstanceGroup> instanceGroups() {
                return List.copyOf(groups);
            }
        };
    }

    @Override
    public Collection<String> deletedObjectIds(ServerLevel level) {
        return deletedIds.getOrDefault(level, Set.of());
    }

    @Override
    public Collection<String> authoritativeObjectPrefixes(ServerLevel level) {
        return authoritativePrefixes.getOrDefault(level, Set.of());
    }

    public void clear() {
        authoritativePrefixes.clear();
        deletedIds.clear();
        SimulatedRopeRegistry.clear();
    }

    private SimulatedApi api() {
        if (!discoveryAttempted) {
            discoveryAttempted = true;
            try {
                api = SimulatedApi.discover();
                LOGGER.info(
                        "Create: Simulated rope integration enabled (using Simulated's already-refreshed server point list)");
            } catch (ClassNotFoundException ignored) {
                LOGGER.debug("Create: Simulated is not installed; rope integration disabled");
            } catch (ReflectiveOperationException | RuntimeException error) {
                LOGGER.warn("Create: Simulated was found but its rope API shape is unsupported: {}",
                        SimulatedReflection.rootMessage(error));
            }
        }
        return api;
    }

    private record RopeSnapshot(UUID id, List<Vec3> points) {
        private RopeSnapshot {
            points = List.copyOf(points);
        }
    }

    /**
     * Reflection boundary for optional Simulated classes.
     *
     * <p>Do not call ServerRopeStrand.updatePose() here. Simulated's
     * ServerRopeTrackingSystem.neededPlayers() is invoked every Sable tracking tick and
     * already refreshes every active strand before networking decisions. A second read
     * here duplicated the native/physics-to-Java pose copy.
     */
    private static final class SimulatedApi {
        private static final String MANAGER =
                "dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerLevelRopeManager";
        private static final String STRAND =
                "dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand";

        private final Method getOrCreate;
        private final Method getAllStrands;
        private final Method getUuid;
        private final Method getPoints;

        private SimulatedApi(
                Method getOrCreate,
                Method getAllStrands,
                Method getUuid,
                Method getPoints) {
            this.getOrCreate = getOrCreate;
            this.getAllStrands = getAllStrands;
            this.getUuid = getUuid;
            this.getPoints = getPoints;
        }

        static SimulatedApi discover() throws ReflectiveOperationException {
            Class<?> managerClass = SimulatedReflection.loadClass(MANAGER);
            Class<?> strandClass = SimulatedReflection.loadClass(STRAND);
            return new SimulatedApi(
                    managerClass.getMethod("getOrCreate", Level.class),
                    managerClass.getMethod("getAllStrands"),
                    strandClass.getMethod("getUUID"),
                    strandClass.getMethod("getPoints"));
        }

        Collection<?> strands(ServerLevel level) throws ReflectiveOperationException {
            Object manager = SimulatedReflection.invoke(getOrCreate, null, level);
            if (manager == null) return List.of();
            Object value = SimulatedReflection.invoke(getAllStrands, manager);
            return value instanceof Collection<?> collection ? collection : List.of();
        }

        RopeSnapshot snapshot(Object strand) throws ReflectiveOperationException {
            Object uuidValue = SimulatedReflection.invoke(getUuid, strand);
            Object pointsValue = SimulatedReflection.invoke(getPoints, strand);
            if (!(uuidValue instanceof UUID uuid) || !(pointsValue instanceof Iterable<?> points)) {
                return null;
            }

            List<Vec3> copy = new ArrayList<>();
            for (Object point : points) {
                if (point instanceof Vector3dc vector
                        && Double.isFinite(vector.x())
                        && Double.isFinite(vector.y())
                        && Double.isFinite(vector.z())) {
                    copy.add(new Vec3(vector.x(), vector.y(), vector.z()));
                }
            }
            return new RopeSnapshot(uuid, copy);
        }

    }
}
