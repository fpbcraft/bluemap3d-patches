package dev.duzo.bluemap3d.create;

import com.simibubi.create.content.trains.track.BezierConnection;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side approximation of Coasters Simulated's dynamically rendered Bézier tracks.
 *
 * <p>One logical persistent scene object per primary curve. Shared GPU-instanced
 * narrow rails, center beam and ties follow Coasters' actual curve and banking
 * functions. The addon remains optional and no Coasters class is linked directly.
 *
 * <p>Coaster carts are Sable sublevels and remain owned by the existing Sable
 * provider; duplicating them here would produce overlapping meshes.
 */
public final class SimulatedCoasterTrackProvider implements SceneObjectProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/CoasterTracks");
    private static final ResourceLocation SEGMENT_MODEL =
            ResourceLocation.fromNamespaceAndPath("bluemap3d", "block/flexible_segment");

    private static final BlockVolume STEEL = segment("minecraft:block/iron_block");
    private static final BlockVolume BEAM = segment("create:block/industrial_iron_block");

    private final Map<ServerLevel, Set<String>> deleted = new ConcurrentHashMap<>();
    private CoasterApi api;
    private boolean attempted;
    private boolean warned;

    @Override public String id() { return "simulated_coaster_tracks"; }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!CompatRegistry.get().featureEnabled("simulated.coasterTrackRendering", true)) {
            deleted.put(level, Set.of());
            return List.of();
        }

        CoasterApi access = api();
        if (access == null) return List.of();

        deleted.put(level, SimulatedCoasterTrackRegistry.drainDeleted(level));
        List<SceneObject> result = new ArrayList<>();
        try {
            for (BlockEntity anchor : SimulatedCoasterTrackRegistry.loaded(level)) {
                Map<?, ?> curves = access.curves(anchor);
                for (Map.Entry<?, ?> entry : curves.entrySet()) {
                    if (!(entry.getKey() instanceof BlockPos peer)
                            || !(entry.getValue() instanceof BezierConnection curve)
                            || !curve.isPrimary()
                            || !isCoaster(curve)) continue;

                    SceneObject track = createTrack(level, anchor, peer, curve, access);
                    if (track != null) result.add(track);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (!warned) {
                warned = true;
                LOGGER.warn("Could not inspect Coasters Simulated track geometry: {}",
                        SimulatedReflection.rootMessage(error));
            }
        }
        return List.copyOf(result);
    }

    private static boolean isCoaster(BezierConnection curve) {
        return "simulatedcoasters:coaster_track".equals(curve.getMaterial().id.toString());
    }

    private static SceneObject createTrack(ServerLevel level, BlockEntity anchor, BlockPos peer,
            BezierConnection curve, CoasterApi api) throws ReflectiveOperationException {
        // Each Create curve has primary and reciprocal entries: only the primary is rendered.
        int segments = Math.max(8, Math.min(128, curve.getSegmentCount() * 2));
        SubLevel subLevel = Sable.HELPER.getContaining(anchor);
        List<Vec3> points = new ArrayList<>(segments + 1);
        List<Vec3> lateral = new ArrayList<>(segments + 1);

        for (int i = 0; i <= segments; i++) {
            float t = (float) i / segments;
            Vec3 local = api.position(curve, t);
            Vec3 rail = api.lateral(curve, t, anchor.getLevel());
            if (!valid(local) || !valid(rail) || rail.lengthSqr() < 1.0e-8) return null;
            if (subLevel != null) {
                local = subLevel.logicalPose().transformPosition(local);
                Vector3d worldNormal = new Vector3d(rail.x, rail.y, rail.z);
                subLevel.logicalPose().transformNormal(worldNormal);
                rail = new Vec3(worldNormal.x, worldNormal.y, worldNormal.z);
            }
            if (!valid(local) || !valid(rail) || rail.lengthSqr() < 1.0e-8) return null;
            points.add(local);
            lateral.add(rail.normalize());
        }

        List<SceneInstance> rails = new ArrayList<>(segments * 2);
        List<SceneInstance> spine = new ArrayList<>(segments);
        List<SceneInstance> ties = new ArrayList<>(segments / 3 + 2);
        final double halfGauge = 0.40;
        for (int i = 1; i < points.size(); i++) {
            Vec3 from = points.get(i - 1);
            Vec3 to = points.get(i);
            if (from.distanceToSqr(to) < 1.0e-8) continue;
            for (int side : new int[] {-1, 1}) {
                Vec3 a = from.add(lateral.get(i - 1).scale(halfGauge * side));
                Vec3 b = to.add(lateral.get(i).scale(halfGauge * side));
                if (a.distanceToSqr(b) > 1.0e-8) rails.add(SceneInstance.between(a, b, 0.24f));
            }
            spine.add(SceneInstance.between(from, to, 0.30f));
            // Sparse sleepers give top-down readability without sending one mesh per tie.
            if (i == 1 || i == segments || i % 4 == 0) {
                Vec3 rail = lateral.get(i);
                Vec3 offset = rail.scale(0.51);
                Vec3 tangent = to.subtract(from).normalize();
                Vec3 up = tangent.cross(rail).normalize();
                Vec3 center = to.subtract(up.scale(0.08));
                ties.add(SceneInstance.between(center.subtract(offset), center.add(offset), 0.24f));
            }
        }
        if (rails.isEmpty()) return null;

        List<SceneInstanceGroup> groups = List.of(
                new SceneInstanceGroup("rails", "coaster-rails", STEEL, 1L, rails),
                new SceneInstanceGroup("beam", "coaster-beam", BEAM, 1L, spine),
                new SceneInstanceGroup("ties", "coaster-ties", BEAM, 1L, ties));
        String key = SimulatedCoasterTrackRegistry.edgeId(anchor, peer);

        return new InstancedSceneObject() {
            @Override public String id() { return key; }
            @Override public net.minecraft.resources.ResourceKey<Level> dimension() {
                return level.dimension();
            }
            @Override public List<SceneInstanceGroup> instanceGroups() { return groups; }
        };
    }

    private static boolean valid(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    private static BlockVolume segment(String texture) {
        return BlockVolume.attachments(BlockPos.ZERO, BlockPos.ZERO,
                new Vec3(0.5, 0.5, 0.5),
                List.of(new ModelAttachment(BlockPos.ZERO, SEGMENT_MODEL, Map.of("1", texture))));
    }

    @Override public Collection<String> deletedObjectIds(ServerLevel level) {
        return deleted.getOrDefault(level, Set.of());
    }

    public void clear() {
        deleted.clear();
        SimulatedCoasterTrackRegistry.clear();
    }

    private CoasterApi api() {
        if (!attempted) {
            attempted = true;
            try {
                api = CoasterApi.discover();
                LOGGER.info("Coasters Simulated track integration enabled (instanced Bézier rails)");
            } catch (ClassNotFoundException ignored) {
                LOGGER.debug("Coasters Simulated is not installed");
            } catch (ReflectiveOperationException | RuntimeException error) {
                LOGGER.warn("Unsupported Coasters Simulated track API: {}",
                        SimulatedReflection.rootMessage(error));
            }
        }
        return api;
    }

    /** Optional reflection boundary tested against the public 1.21.1 Coasters API. */
    private record CoasterApi(Method curves, Method position, Method lateral) {
        static CoasterApi discover() throws ReflectiveOperationException {
            Class<?> anchor = SimulatedReflection.loadClass(
                    "dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity");
            Class<?> graph = SimulatedReflection.loadClass(
                    "dev.silvergold.simulatedcoasters.track.graph.CoasterPathGraphManager");
            Class<?> frames = SimulatedReflection.loadClass(
                    "dev.silvergold.simulatedcoasters.track.CoasterBezierRailFrames");
            return new CoasterApi(
                    anchor.getMethod("getAnchorPeerCurvesView"),
                    graph.getMethod("coasterBezierParametricSpineRaw", BezierConnection.class, double.class),
                    frames.getMethod("lateralAt", BezierConnection.class, float.class, Level.class));
        }

        Map<?, ?> curves(BlockEntity anchor) throws ReflectiveOperationException {
            Object value = SimulatedReflection.invoke(curves, anchor);
            return value instanceof Map<?, ?> map ? map : Map.of();
        }

        Vec3 position(BezierConnection curve, float t) throws ReflectiveOperationException {
            Object value = SimulatedReflection.invoke(position, null, curve, (double) t);
            return value instanceof Vec3 vector ? vector : Vec3.ZERO;
        }

        Vec3 lateral(BezierConnection curve, float t, Level level) throws ReflectiveOperationException {
            Object value = SimulatedReflection.invoke(lateral, null, curve, t, level);
            return value instanceof Vec3 vector ? vector : Vec3.ZERO;
        }
    }
}
