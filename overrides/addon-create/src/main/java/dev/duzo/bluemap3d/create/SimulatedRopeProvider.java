package dev.duzo.bluemap3d.create;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import dev.duzo.bluemap3d.compat.CompatRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes Create: Simulated's server-authoritative physics ropes to BlueMap3D.
 *
 * <p>Each physics interval becomes one rigid {@link SceneObject}. The segment mesh is
 * therefore baked once and normal rope motion only changes position/rotation in the live
 * feed. This deliberately avoids rebuilding a whole rope mesh every publish tick.
 *
 * <p>The integration is reflective so Create: Simulated remains an optional dependency of
 * the Create addon. When the mod is absent this provider is a cheap no-op.
 */
public final class SimulatedRopeProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/SimulatedRopes");
    private static final ResourceLocation ROPE_MODEL =
            ResourceLocation.fromNamespaceAndPath("simulated", "block/rope/rope");
    private static final ResourceLocation KNOT_MODEL =
            ResourceLocation.fromNamespaceAndPath("simulated", "block/rope/knot");
    private static final long GEOMETRY_VERSION_PLAIN = 1L;
    private static final long GEOMETRY_VERSION_KNOTTED = 2L;
    private static final double MIN_SEGMENT_LENGTH_SQUARED = 1.0e-6;

    private static final BlockVolume PLAIN_SEGMENT = segmentGeometry(false);
    private static final BlockVolume KNOTTED_SEGMENT = segmentGeometry(true);

    private RopeAccess access;
    private boolean accessAttempted;

    @Override
    public String id() {
        return "simulated_ropes";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!CompatRegistry.get().featureEnabled("simulated.ropeRendering", true)) {
            return List.of();
        }
        if (!ModList.get().isLoaded("simulated")) {
            return List.of();
        }

        RopeAccess ropeAccess = access();
        if (ropeAccess == null) {
            return List.of();
        }

        try {
            Object manager = ropeAccess.getOrCreate.invoke(null, level);
            if (manager == null) return List.of();

            Object value = ropeAccess.getAllStrands.invoke(manager);
            if (!(value instanceof Iterable<?> strands)) return List.of();

            List<SceneObject> out = new ArrayList<>();
            for (Object strand : strands) {
                appendStrand(level, ropeAccess, strand, out);
            }
            return out;
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warn("Could not read Create: Simulated rope state", error);
            return List.of();
        }
    }

    public void clear() {
        // Rope state belongs to Simulated's ServerLevelRopeManager. We intentionally do
        // not keep a second live cache here; inactive/unloaded ropes retain their last
        // physics points in that manager until the strand itself is removed.
    }

    private void appendStrand(
            ServerLevel level,
            RopeAccess ropeAccess,
            Object strand,
            List<SceneObject> out) throws ReflectiveOperationException {
        Object uuidValue = ropeAccess.getUuid.invoke(strand);
        Object pointsValue = ropeAccess.getPoints.invoke(strand);
        if (!(uuidValue instanceof UUID uuid) || !(pointsValue instanceof List<?> points)) {
            return;
        }
        if (points.size() < 2) return;

        ResourceKey<Level> dimension = level.dimension();
        for (int i = 1; i < points.size(); i++) {
            Object aValue = points.get(i - 1);
            Object bValue = points.get(i);
            if (!(aValue instanceof Vector3dc a) || !(bValue instanceof Vector3dc b)) {
                continue;
            }

            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double dz = b.z() - a.z();
            double lengthSquared = dx * dx + dy * dy + dz * dz;
            if (lengthSquared < MIN_SEGMENT_LENGTH_SQUARED) {
                continue;
            }

            double invLength = 1.0 / Math.sqrt(lengthSquared);
            Quaternionf rotation = new Quaternionf().rotationTo(
                    0f, 1f, 0f,
                    (float) (dx * invLength),
                    (float) (dy * invLength),
                    (float) (dz * invLength));

            // Simulated's renderer starts adding rope knots at the second internal joint.
            boolean knotted = i > 1;
            String objectId = uuid + "/segment/" + i;
            Vec3 position = new Vec3(a.x(), a.y(), a.z());

            out.add(segmentObject(
                    objectId,
                    dimension,
                    position,
                    rotation,
                    knotted ? KNOTTED_SEGMENT : PLAIN_SEGMENT,
                    knotted ? GEOMETRY_VERSION_KNOTTED : GEOMETRY_VERSION_PLAIN));
        }
    }

    private RopeAccess access() {
        if (accessAttempted) return access;
        accessAttempted = true;

        try {
            Class<?> managerClass = Class.forName(
                    "dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerLevelRopeManager");
            Class<?> strandClass = Class.forName(
                    "dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand");

            access = new RopeAccess(
                    managerClass.getMethod("getOrCreate", Level.class),
                    managerClass.getMethod("getAllStrands"),
                    strandClass.getMethod("getUUID"),
                    strandClass.getMethod("getPoints"));
            LOGGER.info("Create: Simulated rope integration enabled");
        } catch (ReflectiveOperationException | LinkageError error) {
            LOGGER.warn(
                    "Create: Simulated is installed but its rope API is not compatible with this BlueMap3D build",
                    error);
            access = null;
        }

        return access;
    }

    private static BlockVolume segmentGeometry(boolean knot) {
        List<ModelAttachment> attachments = new ArrayList<>(knot ? 2 : 1);

        // RopeStrandRenderer translates the 0..1 model by (-.5, 0, -.5) after orienting
        // +Y along the physics segment. Segment lengths are nominally one block in
        // Simulated, so keeping the baked model at unit length lets every motion update
        // remain a transform-only publish.
        attachments.add(new ModelAttachment(
                BlockPos.ZERO,
                ROPE_MODEL,
                Map.of(),
                new Matrix4f().translation(-0.5f, 0f, -0.5f)));

        if (knot) {
            attachments.add(new ModelAttachment(
                    BlockPos.ZERO,
                    KNOT_MODEL,
                    Map.of(),
                    new Matrix4f().translation(-0.5f, -0.5f, -0.5f)));
        }

        return BlockVolume.attachments(
                BlockPos.ZERO,
                BlockPos.ZERO,
                Vec3.ZERO,
                attachments);
    }

    private static SceneObject segmentObject(
            String id,
            ResourceKey<Level> dimension,
            Vec3 position,
            Quaternionf rotation,
            BlockVolume geometry,
            long geometryVersion) {
        return new SceneObject() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public BlockVolume geometry() {
                return geometry;
            }

            @Override
            public long geometryVersion() {
                return geometryVersion;
            }

            @Override
            public Vec3 position() {
                return position;
            }

            @Override
            public Quaternionf rotation() {
                return rotation;
            }

            @Override
            public String label() {
                return "Simulated Rope";
            }

            @Override
            public ResourceKey<Level> dimension() {
                return dimension;
            }
        };
    }

    private record RopeAccess(
            Method getOrCreate,
            Method getAllStrands,
            Method getUuid,
            Method getPoints) {
    }
}
