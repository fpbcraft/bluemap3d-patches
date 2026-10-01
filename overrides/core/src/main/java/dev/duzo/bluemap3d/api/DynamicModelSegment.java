package dev.duzo.bluemap3d.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A cheap live scene object for flexible ropes, hoses, cables and similar geometry.
 *
 * <p>The expensive mesh is a straight model authored along local +Y. Runtime motion is
 * represented entirely by the object's midpoint and quaternion, so bending a rope does
 * not force a re-bake. The only geometry change is a quantized segment-length change.
 * Physics ropes normally keep every interior segment at a fixed length, which means their
 * meshes are effectively immutable while they swing.
 */
public final class DynamicModelSegment implements SceneObject {

    private static final Vec3 LOCAL_PIVOT = new Vec3(0.5, 0.5, 0.5);
    private static final double EPSILON = 1.0e-8;

    private final String id;
    private final ResourceKey<Level> dimension;
    private final ResourceLocation model;
    private final Map<String, String> textures;
    private final Vec3 position;
    private final Quaternionf rotation;
    private final float bakedLength;
    private final long geometryVersion;
    private final String label;

    private DynamicModelSegment(
            String id,
            ResourceKey<Level> dimension,
            ResourceLocation model,
            Map<String, String> textures,
            Vec3 position,
            Quaternionf rotation,
            float bakedLength,
            long geometryVersion,
            String label) {
        this.id = Objects.requireNonNull(id, "id");
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        this.model = Objects.requireNonNull(model, "model");
        this.textures = textures == null ? Map.of() : Map.copyOf(textures);
        this.position = Objects.requireNonNull(position, "position");
        this.rotation = new Quaternionf(Objects.requireNonNull(rotation, "rotation"));
        this.bakedLength = bakedLength;
        this.geometryVersion = geometryVersion;
        this.label = label;
    }

    /**
     * Creates one segment between two world-space endpoints.
     *
     * @param lengthQuantum how coarsely length changes should trigger a mesh rebuild.
     *                      1/32 block is a useful rope default.
     */
    public static DynamicModelSegment between(
            String id,
            ResourceKey<Level> dimension,
            ResourceLocation model,
            Map<String, String> textures,
            Vec3 start,
            Vec3 end,
            float lengthQuantum,
            String label) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");

        Vec3 delta = end.subtract(start);
        double length = delta.length();
        if (!Double.isFinite(length) || length <= EPSILON) {
            throw new IllegalArgumentException("segment endpoints must be finite and distinct");
        }
        if (!Float.isFinite(lengthQuantum) || lengthQuantum <= 0f) {
            throw new IllegalArgumentException("lengthQuantum must be finite and positive");
        }

        int lengthBucket = Math.max(1, Math.round((float) length / lengthQuantum));
        float bakedLength = lengthBucket * lengthQuantum;
        Vec3 midpoint = start.add(end).scale(0.5);
        Quaternionf rotation = rotationFromUp(delta.scale(1.0 / length));

        long version = 0xcbf29ce484222325L;
        version = mix(version, model.toString().hashCode());
        version = mix(version, textures == null ? 0 : textures.hashCode());
        version = mix(version, lengthBucket);

        return new DynamicModelSegment(
                id,
                dimension,
                model,
                textures,
                midpoint,
                rotation,
                bakedLength,
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
        Matrix4f transform = new Matrix4f()
                .translation(0.5f, 0.5f, 0.5f)
                .scale(1f, bakedLength, 1f)
                .translate(-0.5f, -0.5f, -0.5f);

        ModelAttachment attachment =
                new ModelAttachment(BlockPos.ZERO, model, textures, transform);

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
    public String label() {
        return label;
    }

    @Override
    public ResourceKey<Level> dimension() {
        return dimension;
    }
}
