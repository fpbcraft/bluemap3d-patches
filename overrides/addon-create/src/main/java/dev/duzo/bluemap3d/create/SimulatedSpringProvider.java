package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.InstancedSceneObject;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneInstance;
import dev.duzo.bluemap3d.api.SceneInstanceGroup;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import dev.duzo.bluemap3d.compat.CompatRegistry;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mirrors Create: Simulated springs as one logical GPU-instanced scene object per spring.
 *
 * <p>Discovery is event/lifecycle driven through {@link SimulatedSpringRegistry}; this
 * provider no longer slices through all loaded chunks or every Sable sublevel.
 */
public final class SimulatedSpringProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/SimulatedSprings");
    private static final ResourceLocation SEGMENT_MODEL =
            ResourceLocation.fromNamespaceAndPath("bluemap3d", "block/flexible_segment");

    private static final Map<String, BlockVolume> GEOMETRY = Map.of(
            "small", segmentGeometry("simulated:block/spring/small_spring"),
            "medium", segmentGeometry("simulated:block/spring/spring"),
            "large", segmentGeometry("simulated:block/spring/large_spring"));

    private final Map<ServerLevel, Set<String>> authoritativePrefixes =
            new ConcurrentHashMap<>();
    private final Map<ServerLevel, Set<String>> deletedIds =
            new ConcurrentHashMap<>();

    private SpringApi api;
    private boolean discoveryAttempted;
    private boolean warned;

    public SimulatedSpringProvider(ChunkTracker ignoredChunks) {
        // Kept in the constructor signature so CreateAddon wiring remains source-compatible.
        // Discovery now comes from SimulatedSpringRegistry instead of ChunkTracker.
    }

    @Override
    public String id() {
        return "simulated_springs";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!CompatRegistry.get().featureEnabled("simulated.springRendering", true)) {
            authoritativePrefixes.put(level, Set.of());
            deletedIds.put(level, Set.of());
            return List.of();
        }

        SpringApi access = api();
        if (access == null) {
            authoritativePrefixes.put(level, Set.of());
            deletedIds.put(level, Set.of());
            return List.of();
        }

        List<SceneObject> out = new ArrayList<>();
        Set<String> legacyPrefixes = new HashSet<>();
        Set<String> removedIds = SimulatedSpringRegistry.drainRemoved(level);
        deletedIds.put(level, removedIds);
        for (String removedId : removedIds) {
            // Also prune the pre-instancing segment children from 1.1.x persistence.
            legacyPrefixes.add(removedId + "/");
        }

        try {
            for (BlockEntity blockEntity : SimulatedSpringRegistry.loaded(level)) {
                if (!access.isSpring(blockEntity) || !access.isController(blockEntity)) continue;

                SpringKey key = keyOf(blockEntity);
                legacyPrefixes.add(key.id() + "/");

                SpringSnapshot spring = access.snapshot(blockEntity);
                // A currently loaded controller with no valid pair is authoritative empty
                // state, so its old segmented children are pruned even though no new object
                // is emitted.
                if (spring == null || spring.points().size() < 2) {
                    deletedIds.put(level, union(deletedIds.get(level), Set.of(key.id())));
                    continue;
                }

                out.add(objectOf(level, key, spring));
            }
            authoritativePrefixes.put(level, Set.copyOf(legacyPrefixes));
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Removal events are independent positive evidence; keep those even if one
            // live spring failed to snapshot during this publish.
            authoritativePrefixes.put(level, Set.copyOf(legacyPrefixes));
            if (!warned) {
                warned = true;
                LOGGER.warn(
                        "Could not read Create: Simulated spring state; persistent snapshots remain available: {}",
                        SimulatedReflection.rootMessage(error));
            }
        }

        return List.copyOf(out);
    }

    private static SceneObject objectOf(
            ServerLevel level,
            SpringKey key,
            SpringSnapshot spring) {
        List<SceneInstance> instances = new ArrayList<>(8);
        List<Vec3> points = spring.points();
        float width = crossSectionScale(spring.size());

        for (int i = 1; i < points.size(); i++) {
            Vec3 start = points.get(i - 1);
            Vec3 end = points.get(i);
            if (start.distanceToSqr(end) < 1.0e-10) continue;
            instances.add(SceneInstance.between(start, end, width));
        }

        String size = spring.size();
        SceneInstanceGroup segments = new SceneInstanceGroup(
                "segments",
                "simulated-spring-" + size,
                GEOMETRY.getOrDefault(size, GEOMETRY.get("medium")),
                1L,
                instances);

        return new InstancedSceneObject() {
            @Override public String id() { return key.id(); }
            @Override public String label() { return "Simulated Spring"; }
            @Override public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension() {
                return level.dimension();
            }
            @Override public List<SceneInstanceGroup> instanceGroups() {
                return List.of(segments);
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

    private static Set<String> union(Set<String> left, Set<String> right) {
        if (left == null || left.isEmpty()) return Set.copyOf(right);
        if (right == null || right.isEmpty()) return Set.copyOf(left);
        Set<String> result = new HashSet<>(left);
        result.addAll(right);
        return Set.copyOf(result);
    }

    public void clear() {
        authoritativePrefixes.clear();
        deletedIds.clear();
        SimulatedSpringRegistry.clear();
    }

    private static BlockVolume segmentGeometry(String texture) {
        return BlockVolume.attachments(
                BlockPos.ZERO,
                BlockPos.ZERO,
                new Vec3(0.5, 0.5, 0.5),
                List.of(new ModelAttachment(
                        BlockPos.ZERO,
                        SEGMENT_MODEL,
                        Map.of("1", texture))));
    }

    private static SpringKey keyOf(BlockEntity blockEntity) {
        SubLevel subLevel = Sable.HELPER.getContaining(blockEntity);
        UUID subLevelId = subLevel == null ? null : subLevel.getUniqueId();
        return new SpringKey(subLevelId, blockEntity.getBlockPos().immutable());
    }

    private static float crossSectionScale(String size) {
        return switch (size) {
            case "small" -> 6f / 8f;
            case "large" -> 10f / 8f;
            default -> 1f;
        };
    }

    private SpringApi api() {
        if (!discoveryAttempted) {
            discoveryAttempted = true;
            try {
                api = SpringApi.discover();
                LOGGER.info("Create: Simulated spring integration enabled (event-tracked, instanced)");
            } catch (ClassNotFoundException ignored) {
                LOGGER.debug("Create: Simulated is not installed; spring integration disabled");
            } catch (ReflectiveOperationException | RuntimeException error) {
                LOGGER.warn("Create: Simulated was found but its spring API shape is unsupported: {}",
                        SimulatedReflection.rootMessage(error));
            }
        }
        return api;
    }

    private static Vec3 projectPosition(Vector3dc local, SubLevel subLevel) {
        Vector3d world = new Vector3d(local);
        if (subLevel != null) subLevel.logicalPose().transformPosition(world);
        return new Vec3(world.x, world.y, world.z);
    }

    private static Vec3 projectNormal(Direction facing, SubLevel subLevel) {
        Vector3d world = new Vector3d(facing.getStepX(), facing.getStepY(), facing.getStepZ());
        if (subLevel != null) subLevel.logicalPose().transformNormal(world);
        world.normalize();
        return new Vec3(world.x, world.y, world.z);
    }

    private static List<Vec3> generateSpline(Vec3 a, Vec3 b, Vec3 normalA, Vec3 normalB) {
        double distance = a.distanceTo(b);
        double influence = distance / 5.0 + 0.25;
        Vec3 controlA = a.add(normalA.scale(influence));
        Vec3 controlB = b.add(normalB.scale(influence));
        int segments = 8;

        List<Vec3> points = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            double u = 1.0 - t;
            points.add(new Vec3(
                    u * u * u * a.x + 3.0 * u * u * t * controlA.x
                            + 3.0 * u * t * t * controlB.x + t * t * t * b.x,
                    u * u * u * a.y + 3.0 * u * u * t * controlA.y
                            + 3.0 * u * t * t * controlB.y + t * t * t * b.y,
                    u * u * u * a.z + 3.0 * u * u * t * controlA.z
                            + 3.0 * u * t * t * controlB.z + t * t * t * b.z));
        }
        return List.copyOf(points);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object stateProperty(BlockState state, String name) {
        for (Property property : state.getProperties()) {
            if (name.equals(property.getName())) return state.getValue(property);
        }
        return null;
    }

    private static Direction facingOf(BlockState state) {
        Object value = stateProperty(state, "facing");
        return value instanceof Direction direction ? direction : Direction.UP;
    }

    private static String sizeOf(BlockState state) {
        Object value = stateProperty(state, "size");
        if (value instanceof StringRepresentable serialized) return serialized.getSerializedName();
        return value == null ? "medium" : value.toString().toLowerCase(java.util.Locale.ROOT);
    }

    private record SpringKey(UUID subLevelId, BlockPos pos) {
        String id() {
            return (subLevelId == null ? "world" : subLevelId.toString())
                    + "/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        }
    }

    private record SpringSnapshot(List<Vec3> points, String size) {
        private SpringSnapshot {
            points = List.copyOf(points);
        }
    }

    private static final class SpringApi {
        private static final String SPRING =
                "dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity";

        private final Class<?> springClass;
        private final Method isController;
        private final Method getPairedSpring;
        private final Method getCenter;

        private SpringApi(
                Class<?> springClass,
                Method isController,
                Method getPairedSpring,
                Method getCenter) {
            this.springClass = springClass;
            this.isController = isController;
            this.getPairedSpring = getPairedSpring;
            this.getCenter = getCenter;
        }

        static SpringApi discover() throws ReflectiveOperationException {
            Class<?> springClass = SimulatedReflection.loadClass(SPRING);
            return new SpringApi(
                    springClass,
                    springClass.getMethod("isController"),
                    springClass.getMethod("getPairedSpring"),
                    springClass.getMethod("getCenter"));
        }

        boolean isSpring(Object value) {
            return value != null && springClass.isInstance(value);
        }

        boolean isController(Object spring) throws ReflectiveOperationException {
            return Boolean.TRUE.equals(SimulatedReflection.invoke(isController, spring));
        }

        SpringSnapshot snapshot(BlockEntity controller) throws ReflectiveOperationException {
            Object paired = SimulatedReflection.invoke(getPairedSpring, controller);
            if (!(paired instanceof BlockEntity partner) || !isSpring(partner)) return null;

            Object aValue = SimulatedReflection.invoke(getCenter, controller);
            Object bValue = SimulatedReflection.invoke(getCenter, partner);
            if (!(aValue instanceof Vector3dc aLocal) || !(bValue instanceof Vector3dc bLocal)) {
                return null;
            }

            SubLevel aSubLevel = Sable.HELPER.getContaining(controller);
            SubLevel bSubLevel = Sable.HELPER.getContaining(partner);

            Vec3 a = projectPosition(aLocal, aSubLevel);
            Vec3 b = projectPosition(bLocal, bSubLevel);
            Vec3 normalA = projectNormal(facingOf(controller.getBlockState()), aSubLevel);
            Vec3 normalB = projectNormal(facingOf(partner.getBlockState()), bSubLevel);

            return new SpringSnapshot(
                    generateSpline(a, b, normalA, normalB),
                    sizeOf(controller.getBlockState()));
        }

    }
}
