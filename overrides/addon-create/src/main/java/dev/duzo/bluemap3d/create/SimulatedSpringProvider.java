package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.DynamicModelSegment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import dev.duzo.bluemap3d.compat.CompatRegistry;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Mirrors Create: Simulated's dynamic spring renderer in BlueMap3D.
 *
 * <p>Unlike ropes, springs do not expose a server point chain. Simulated's renderer
 * constructs a cubic Bezier spline from the two spring block entities, their facing
 * normals and any Sable sub-level transforms. This provider reproduces that spline in
 * world space and emits a fixed 8 generic live segments. Using a fixed subdivision keeps
 * segment identity stable for historical playback while segment length remains a streamed
 * scale, so compression/stretching does not trigger mesh rebuilds.
 *
 * <p>Simulated itself remains optional. Spring-specific methods are reflected after the
 * class is discovered; Sable is already a compile-only integration dependency of the
 * Create addon for moving contraptions.
 */
public final class SimulatedSpringProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/SimulatedSprings");
    private static final ResourceLocation SEGMENT_MODEL =
            ResourceLocation.fromNamespaceAndPath("bluemap3d", "block/flexible_segment");
    private static final int SLICE_COUNT = 32;

    private final ChunkTracker chunks;
    private final Map<ServerLevel, AtomicInteger> sliceIndex = new HashMap<>();
    private final Map<ServerLevel, Map<SpringKey, SpringSnapshot>> cache = new HashMap<>();

    private SpringApi api;
    private boolean discoveryAttempted;
    private boolean warned;

    public SimulatedSpringProvider(ChunkTracker chunks) {
        this.chunks = chunks;
    }

    @Override
    public String id() {
        return "simulated_springs";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!CompatRegistry.get().featureEnabled("simulated.springRendering", true)) {
            return List.of();
        }

        SpringApi access = api();
        if (access == null) {
            return List.of();
        }

        Map<SpringKey, SpringSnapshot> levelCache =
                cache.computeIfAbsent(level, ignored -> new HashMap<>());

        try {
            scanMainSlice(level, levelCache, access);
            scanSubLevels(level, levelCache, access);
            refreshKnown(level, levelCache, access);
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!warned) {
                warned = true;
                LOGGER.warn(
                        "Could not read Create: Simulated spring state; keeping last-known spring snapshots: {}",
                        rootMessage(error));
            }
        }

        if (levelCache.isEmpty()) {
            return List.of();
        }

        List<SceneObject> out = new ArrayList<>();
        for (Map.Entry<SpringKey, SpringSnapshot> entry : levelCache.entrySet()) {
            SpringSnapshot spring = entry.getValue();
            List<Vec3> points = spring.points();
            String texture = textureFor(spring.size());
            float crossSectionScale = crossSectionScale(spring.size());

            for (int i = 1; i < points.size(); i++) {
                Vec3 start = points.get(i - 1);
                Vec3 end = points.get(i);
                if (start.distanceToSqr(end) < 1.0e-10) continue;

                out.add(DynamicModelSegment.betweenScaled(
                        entry.getKey().id() + "/segment-" + (i - 1),
                        level.dimension(),
                        SEGMENT_MODEL,
                        Map.of("1", texture),
                        start,
                        end,
                        crossSectionScale,
                        "Simulated Spring"));
            }
        }

        return out;
    }

    public void clear() {
        sliceIndex.clear();
        cache.clear();
    }

    private void scanMainSlice(
            ServerLevel level,
            Map<SpringKey, SpringSnapshot> levelCache,
            SpringApi access) throws ReflectiveOperationException {
        List<ChunkPos> loaded = new ArrayList<>(chunks.loadedChunks(level));
        if (loaded.isEmpty()) return;

        int slice = sliceIndex.computeIfAbsent(level, ignored -> new AtomicInteger())
                .getAndUpdate(value -> (value + 1) % SLICE_COUNT);

        for (int i = slice; i < loaded.size(); i += SLICE_COUNT) {
            ChunkPos chunkPos = loaded.get(i);
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) continue;

            for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                update(blockEntity, levelCache, access);
            }
        }
    }

    private void scanSubLevels(
            ServerLevel level,
            Map<SpringKey, SpringSnapshot> levelCache,
            SpringApi access) throws ReflectiveOperationException {
        SubLevelContainer rawContainer = SubLevelContainer.getContainer(level);
        if (!(rawContainer instanceof ServerSubLevelContainer container)) return;

        for (ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved()) continue;
            for (Object actor : subLevel.getPlot().getBlockEntityActors()) {
                if (actor instanceof BlockEntity blockEntity) {
                    update(blockEntity, levelCache, access);
                }
            }
        }
    }

    private void refreshKnown(
            ServerLevel level,
            Map<SpringKey, SpringSnapshot> levelCache,
            SpringApi access) throws ReflectiveOperationException {
        for (SpringKey key : new ArrayList<>(levelCache.keySet())) {
            BlockPos pos = key.pos();
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk == null) {
                // Preserve the last-known spline while an ordinary chunk or Sable plot is
                // unloaded. A later loaded scan either refreshes or removes it.
                continue;
            }

            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (!access.isSpring(blockEntity) || !access.isController(blockEntity)) {
                levelCache.remove(key);
                continue;
            }

            SpringSnapshot snapshot = access.snapshot(blockEntity);
            if (snapshot != null) {
                levelCache.put(keyOf(blockEntity), snapshot);
                if (!key.equals(keyOf(blockEntity))) {
                    levelCache.remove(key);
                }
            }
        }
    }

    private void update(
            BlockEntity blockEntity,
            Map<SpringKey, SpringSnapshot> levelCache,
            SpringApi access) throws ReflectiveOperationException {
        if (!access.isSpring(blockEntity) || !access.isController(blockEntity)) return;

        SpringSnapshot snapshot = access.snapshot(blockEntity);
        if (snapshot != null) {
            levelCache.put(keyOf(blockEntity), snapshot);
        }
    }

    private static SpringKey keyOf(BlockEntity blockEntity) {
        SubLevel subLevel = Sable.HELPER.getContaining(blockEntity);
        UUID subLevelId = subLevel == null ? null : subLevel.getUniqueId();
        return new SpringKey(subLevelId, blockEntity.getBlockPos().immutable());
    }

    private static String textureFor(String size) {
        return switch (size) {
            case "small" -> "simulated:block/spring/small_spring";
            case "large" -> "simulated:block/spring/large_spring";
            default -> "simulated:block/spring/spring";
        };
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
                LOGGER.info("Create: Simulated spring integration enabled");
            } catch (ClassNotFoundException ignored) {
                LOGGER.debug("Create: Simulated is not installed; spring integration disabled");
            } catch (ReflectiveOperationException | RuntimeException error) {
                LOGGER.warn("Create: Simulated was found but its spring API shape is unsupported: {}",
                        rootMessage(error));
            }
        }
        return api;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static Vec3 projectPosition(Vector3dc local, SubLevel subLevel) {
        Vector3d world = new Vector3d(local);
        if (subLevel != null) {
            subLevel.logicalPose().transformPosition(world);
        }
        return new Vec3(world.x, world.y, world.z);
    }

    private static Vec3 projectNormal(Direction facing, SubLevel subLevel) {
        Vector3d world = new Vector3d(
                facing.getStepX(),
                facing.getStepY(),
                facing.getStepZ());
        if (subLevel != null) {
            subLevel.logicalPose().transformNormal(world);
        }
        world.normalize();
        return new Vec3(world.x, world.y, world.z);
    }

    /**
     * Same cubic spline construction as SpringRenderer.generateSpline(), but directly in
     * world space. Bezier curves commute with the rigid Sable pose transform.
     */
    private static List<Vec3> generateSpline(Vec3 a, Vec3 b, Vec3 normalA, Vec3 normalB) {
        double distance = a.distanceTo(b);
        double influence = distance / 5.0 + 0.25;
        Vec3 controlA = a.add(normalA.scale(influence));
        Vec3 controlB = b.add(normalB.scale(influence));
        // Simulated's client renderer varies this between 5 and 8 based on distance.
        // That is fine for one live frame, but it changes which physical spline interval
        // a given segment-N represents as the spring crosses an integer distance. Keep
        // the maximum subdivision instead so history can interpolate stable identities.
        int segments = 8;

        List<Vec3> points = new ArrayList<>(segments + 1);
        for (int i = 0; i <= segments; i++) {
            double t = (double) i / segments;
            double u = 1.0 - t;
            points.add(new Vec3(
                    u * u * u * a.x
                            + 3.0 * u * u * t * controlA.x
                            + 3.0 * u * t * t * controlB.x
                            + t * t * t * b.x,
                    u * u * u * a.y
                            + 3.0 * u * u * t * controlA.y
                            + 3.0 * u * t * t * controlB.y
                            + t * t * t * b.y,
                    u * u * u * a.z
                            + 3.0 * u * u * t * controlA.z
                            + 3.0 * u * t * t * controlB.z
                            + t * t * t * b.z));
        }
        return List.copyOf(points);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object stateProperty(BlockState state, String name) {
        for (Property property : state.getProperties()) {
            if (name.equals(property.getName())) {
                return state.getValue(property);
            }
        }
        return null;
    }

    private static Direction facingOf(BlockState state) {
        Object value = stateProperty(state, "facing");
        return value instanceof Direction direction ? direction : Direction.UP;
    }

    private static String sizeOf(BlockState state) {
        Object value = stateProperty(state, "size");
        if (value instanceof StringRepresentable serialized) {
            return serialized.getSerializedName();
        }
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
            Class<?> springClass = Class.forName(
                    SPRING, false, SimulatedSpringProvider.class.getClassLoader());
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
            return Boolean.TRUE.equals(invoke(isController, spring));
        }

        SpringSnapshot snapshot(BlockEntity controller) throws ReflectiveOperationException {
            Object paired = invoke(getPairedSpring, controller);
            if (!(paired instanceof BlockEntity partner) || !isSpring(partner)) {
                return null;
            }

            Object aValue = invoke(getCenter, controller);
            Object bValue = invoke(getCenter, partner);
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

        private static Object invoke(Method method, Object target, Object... args)
                throws ReflectiveOperationException {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause();
                if (cause instanceof ReflectiveOperationException reflective) throw reflective;
                if (cause instanceof RuntimeException runtime) throw runtime;
                throw error;
            }
        }
    }
}
