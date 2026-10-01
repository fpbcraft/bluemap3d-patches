package dev.duzo.bluemap3d.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A cheap live scene object for ropes, springs, hoses, cables and similar flexible
 * geometry.
 *
 * <p>The expensive mesh is a one-block straight model authored along local +Y.
 * Midpoint, orientation and length are all streamed transforms. Bending or stretching
 * therefore never re-bakes the mesh.
 */
public final class DynamicModelSegment implements SceneObject {

    private static final Vec3 LOCAL_PIVOT = new Vec3(0.5, 0.5, 0.5);
    private static final double EPSILON = 1.0e-8;
    /** Tiny total extension shared across both ends to hide raster/rounding seams. */
    private static final float DEFAULT_OVERLAP = 1f / 64f;

    private final String id;
    private final ResourceKey<Level> dimension;
    private final ResourceLocation model;
    private final Map<String, String> textures;
    private final Vec3 position;
    private final Quaternionf rotation;
    private final Vector3f scale;
    private final long geometryVersion;
    private final String label;

    private DynamicModelSegment(
            String id,
            ResourceKey<Level> dimension,
            ResourceLocation model,
            Map<String, String> textures,
            Vec3 position,
            Quaternionf rotation,
            Vector3f scale,
            long geometryVersion,
            String label) {
        this.id = Objects.requireNonNull(id, "id");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.model = Objects.requireNonNull(model, "model");
        this.textures = textures == null ? Map.of() : Map.copyOf(textures);
        this.position = Objects.requireNonNull(position, "position");
        this.rotation = new Quaternionf(Objects.requireNonNull(rotation, "rotation"));
        this.scale = new Vector3f(Objects.requireNonNull(scale, "scale"));
        this.geometryVersion = geometryVersion;
        this.label = label;
    }

    /** One-block cross section; useful for models that already encode their own width. */
    public static DynamicModelSegment between(
            String id,
            ResourceKey<Level> dimension,
            ResourceLocation model,
            Map<String, String> textures,
            Vec3 start,
            Vec3 end,
            float ignoredLengthQuantum,
            String label) {
        return between(id, dimension, model, textures, start, end, 1f, label);
    }

    /**
     * Creates one deforming segment between two world-space endpoints.
     *
     * @param crossSectionScale X/Z scale relative to the authored model
     */
    public static DynamicModelSegment between(
            String id,
            ResourceKey<Level> dimension,
            ResourceLocation model,
            Map<String, String> textures,
            Vec3 start,
            Vec3 end,
            float crossSectionScale,
            String label) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");

        Vec3 delta = end.subtract(start);
        double length = delta.length();
        if (!Double.isFinite(length) || length <= EPSILON) {
            throw new IllegalArgumentException("segment endpoints must be finite and distinct");
        }
        if (!Float.isFinite(crossSectionScale) || crossSectionScale <= 0f) {
            throw new IllegalArgumentException("crossSectionScale must be finite and positive");
        }

        Vec3 midpoint = start.add(end).scale(0.5);
        Quaternionf rotation = rotationFromUp(delta.scale(1.0 / length));

        // Geometry no longer depends on length. Only model/texture/cross-section changes
        // require a bake; length is a live Y scale in the feed.
        long version = 0xcbf29ce484222325L;
        version = mix(version, model.toString().hashCode());
        version = mix(version, textures == null ? 0 : textures.hashCode());
        version = mix(version, Float.floatToIntBits(crossSectionScale));

        return new DynamicModelSegment(
                id,
                dimension,
                model,
                textures,
                midpoint,
                rotation,
                new Vector3f(
                        crossSectionScale,
                        (float) length + DEFAULT_OVERLAP,
                        crossSectionScale),
                version,
                label);
    }

    private static Quaternionf rotationFromUp(Vec3 direction) {
        float x = (float) direction.x;
        float y = (float) direction.y;
        float z = (float) direction.z;

        if (y >= 0.999999f) {
            return new Quaternionf();
        }
        if (y <= -0.999999f) {
            return new Quaternionf().rotationX((float) Math.PI);
        }

        // cross((0, 1, 0), direction) = (z, 0, -x)
        float axisLength = (float) Math.sqrt(x * x + z * z);
        float axisX = z / axisLength;
        float axisZ = -x / axisLength;
        float angle = (float) Math.acos(Math.max(-1f, Math.min(1f, y)));
        return new Quaternionf().rotationAxis(angle, axisX, 0f, axisZ);
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public BlockVolume geometry() {
        ModelAttachment attachment =
                new ModelAttachment(BlockPos.ZERO, model, textures);

        return BlockVolume.attachments(
                BlockPos.ZERO,
                BlockPos.ZERO,
                LOCAL_PIVOT,
                List.of(attachment));
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
        return new Quaternionf(rotation);
    }

    @Override
    public Vector3f scale() {
        return new Vector3f(scale);
    }

    @Override
    public String label() {
        return label;
    }

    @Override
    public ResourceKey<Level> dimension() {
        return dimension;
    }
}
