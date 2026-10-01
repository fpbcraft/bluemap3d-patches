package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.DynamicModelSegment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import dev.duzo.bluemap3d.compat.CompatRegistry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Publishes Create: Simulated's server-authoritative physics ropes to BlueMap3D.
 *
 * <p>Simulated already keeps every rope as a ServerRopeStrand made of world-space physics
 * points. We intentionally access that optional mod through a tiny reflection adapter so
 * the normal BlueMap: Create addon still loads when Simulated is not installed.
 *
 * <p>Each physics edge is one {@link DynamicModelSegment}. Its mesh is almost always
 * immutable while only midpoint/rotation are streamed, avoiding a whole-rope re-bake on
 * every physics update. Segment length is quantized to 1/32 block before it can affect
 * geometry, which absorbs normal solver noise while still handling winch extension.
 */
public final class SimulatedRopeProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/SimulatedRopes");
    private static final ResourceLocation ROPE_MODEL =
            ResourceLocation.fromNamespaceAndPath("simulated", "block/rope/rope");
    private static final float LENGTH_QUANTUM = 1f / 32f;

    private final Map<ServerLevel, Map<UUID, RopeSnapshot>> lastKnown = new HashMap<>();
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
            return List.of();
        }

        SimulatedApi access = api();
        if (access == null) {
            return List.of();
        }

        Map<UUID, RopeSnapshot> cache =
                lastKnown.computeIfAbsent(level, ignored -> new HashMap<>());

        try {
            Collection<?> strands = access.strands(level);
            Set<UUID> seen = new HashSet<>();

            for (Object strand : strands) {
                RopeSnapshot snapshot = access.snapshot(strand);
                if (snapshot == null || snapshot.points().size() < 2) {
                    continue;
                }

                seen.add(snapshot.id());
                cache.put(snapshot.id(), snapshot);
            }

            // ServerLevelRopeManager owns the complete rope set, including inactive ropes
            // whose physics chunks/sub-level attachments are currently unloaded. Missing
            // ids therefore mean the rope was actually removed.
            cache.keySet().retainAll(seen);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!warned) {
                warned = true;
                LOGGER.warn(
                        "Could not read Create: Simulated rope state; keeping last-known rope snapshots: {}",
                        rootMessage(error));
            }
        }

        if (cache.isEmpty()) {
            return List.of();
        }

        List<SceneObject> out = new ArrayList<>();
        for (RopeSnapshot rope : cache.values()) {
            List<Vec3> points = rope.points();
            for (int i = 1; i < points.size(); i++) {
                Vec3 start = points.get(i - 1);
                Vec3 end = points.get(i);
                if (start.distanceToSqr(end) < 1.0e-10) {
                    continue;
                }

                out.add(DynamicModelSegment.between(
                        rope.id() + "/segment-" + (i - 1),
                        level.dimension(),
                        ROPE_MODEL,
                        Map.of(),
                        start,
                        end,
                        LENGTH_QUANTUM,
                        "Simulated Rope"));
            }
        }

        return out;
    }

    public void clear() {
        lastKnown.clear();
    }

    private SimulatedApi api() {
        if (!discoveryAttempted) {
            discoveryAttempted = true;
            try {
                api = SimulatedApi.discover();
                LOGGER.info("Create: Simulated rope integration enabled");
            } catch (ClassNotFoundException ignored) {
                LOGGER.debug("Create: Simulated is not installed; rope integration disabled");
            } catch (ReflectiveOperationException | RuntimeException error) {
                LOGGER.warn("Create: Simulated was found but its rope API shape is unsupported: {}",
                        rootMessage(error));
            }
        }
        return api;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private record RopeSnapshot(UUID id, List<Vec3> points) {
        private RopeSnapshot {
            points = List.copyOf(points);
        }
    }

    /**
     * Reflection boundary for optional Simulated classes. Resolve methods once; the hot
     * publish path only performs Method.invoke and cheap vector copies.
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
        private final Method isActive;
        private final Method updatePose;

        private SimulatedApi(
                Method getOrCreate,
                Method getAllStrands,
                Method getUuid,
                Method getPoints,
                Method isActive,
                Method updatePose) {
            this.getOrCreate = getOrCreate;
            this.getAllStrands = getAllStrands;
            this.getUuid = getUuid;
            this.getPoints = getPoints;
            this.isActive = isActive;
            this.updatePose = updatePose;
        }

        static SimulatedApi discover() throws ReflectiveOperationException {
            ClassLoader loader = SimulatedRopeProvider.class.getClassLoader();
            Class<?> managerClass = Class.forName(MANAGER, false, loader);
            Class<?> strandClass = Class.forName(STRAND, false, loader);

            return new SimulatedApi(
                    managerClass.getMethod("getOrCreate", Level.class),
                    managerClass.getMethod("getAllStrands"),
                    strandClass.getMethod("getUUID"),
                    strandClass.getMethod("getPoints"),
                    strandClass.getMethod("isActive"),
                    strandClass.getMethod("updatePose"));
        }

        Collection<?> strands(ServerLevel level) throws ReflectiveOperationException {
            Object manager = invoke(getOrCreate, null, level);
            if (manager == null) {
                return List.of();
            }
            Object value = invoke(getAllStrands, manager);
            return value instanceof Collection<?> collection ? collection : List.of();
        }

        RopeSnapshot snapshot(Object strand) throws ReflectiveOperationException {
            if (Boolean.TRUE.equals(invoke(isActive, strand))) {
                // Mirrors Simulated's own ServerRopeTrackingSystem: copy the physics pose
                // into the strand's point list before reading it for rendering.
                invoke(updatePose, strand);
            }

            Object uuidValue = invoke(getUuid, strand);
            Object pointsValue = invoke(getPoints, strand);
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

        private static Object invoke(Method method, Object target, Object... args)
                throws ReflectiveOperationException {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof ReflectiveOperationException reflective) {
                    throw reflective;
                }
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                throw error;
            }
        }
    }
}
