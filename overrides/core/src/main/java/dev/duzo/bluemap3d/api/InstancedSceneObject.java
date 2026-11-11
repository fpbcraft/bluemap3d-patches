package dev.duzo.bluemap3d.api;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * One logical object rendered as one or more GPU-instanced prototype groups.
 *
 * <p>The object's own transform is identity; each instance is already in world space.
 * This is suited to ropes, springs, cables and other repeated dynamic geometry.
 */
public interface InstancedSceneObject extends SceneObject {

    List<SceneInstanceGroup> instanceGroups();

    @Override
    default BlockVolume geometry() {
        return BlockVolume.EMPTY;
    }

    @Override
    default boolean canBakeGeometry() {
        return false;
    }

    @Override
    default long geometryVersion() {
        long hash = 0xcbf29ce484222325L;
        for (SceneInstanceGroup group : instanceGroups()) {
            hash = mix(hash, group.id().hashCode());
            hash = mix(hash, group.geometryKey().hashCode());
            hash = mix(hash, group.geometryVersion());
        }
        return hash;
    }

    @Override
    default Vec3 position() {
        return Vec3.ZERO;
    }

    @Override
    default Quaternionf rotation() {
        return new Quaternionf();
    }

    @Override
    default Vector3f scale() {
        return new Vector3f(1f, 1f, 1f);
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * 0x100000001b3L;
    }
}
