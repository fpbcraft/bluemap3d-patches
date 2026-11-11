package dev.duzo.bluemap3d.api;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One transform inside an {@link InstancedSceneObject} group. */
public record SceneInstance(Vec3 position, Quaternionf rotation, Vector3f scale) {

    private static final double EPSILON = 1.0e-8;
    private static final float DEFAULT_OVERLAP = 1f / 64f;

    public SceneInstance {
        position = new Vec3(position.x, position.y, position.z);
        rotation = new Quaternionf(rotation);
        scale = new Vector3f(scale);
    }

    public static SceneInstance at(Vec3 position) {
        return new SceneInstance(position, new Quaternionf(), new Vector3f(1f, 1f, 1f));
    }

    /**
     * Transform for a one-block prototype authored along local +Y.
     */
    public static SceneInstance between(Vec3 start, Vec3 end, float crossSectionScale) {
        Vec3 delta = end.subtract(start);
        double length = delta.length();
        if (!Double.isFinite(length) || length <= EPSILON) {
            throw new IllegalArgumentException("segment endpoints must be finite and distinct");
        }

        Vec3 direction = delta.scale(1.0 / length);
        Quaternionf rotation = rotationFromUp(direction);
        Vec3 midpoint = start.add(end).scale(0.5);
        return new SceneInstance(
                midpoint,
                rotation,
                new Vector3f(
                        crossSectionScale,
                        (float) length + DEFAULT_OVERLAP,
                        crossSectionScale));
    }

    private static Quaternionf rotationFromUp(Vec3 direction) {
        float x = (float) direction.x;
        float y = (float) direction.y;
        float z = (float) direction.z;

        if (y >= 0.999999f) return new Quaternionf();
        if (y <= -0.999999f) return new Quaternionf().rotationX((float) Math.PI);

        float axisLength = (float) Math.sqrt(x * x + z * z);
        float axisX = z / axisLength;
        float axisZ = -x / axisLength;
        float angle = (float) Math.acos(Math.max(-1f, Math.min(1f, y)));
        return new Quaternionf().rotationAxis(angle, axisX, 0f, axisZ);
    }
}
