package dev.duzo.bluemap3d.api;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * One rigid body to draw in BlueMap's 3D scene.
 *
 * <p>Geometry is expensive and cached. Position, rotation and scale are cheap live
 * transforms streamed every publish interval and interpolated by the browser.
 */
public interface SceneObject {

    String id();

    BlockVolume geometry();

    /**
     * Whether core may ask this object to rebuild its geometry.
     *
     * <p>Normally true. A persisted object may have a valid published mesh on disk while
     * its live backing level is unavailable and can return false.
     */
    default boolean canBakeGeometry() {
        return true;
    }

    long geometryVersion();

    Vec3 position();

    Quaternionf rotation();

    /**
     * Per-axis live scale of the baked mesh.
     *
     * <p>Keep structural shape in geometry whenever possible. This live scale exists for
     * genuinely deforming primitives such as rope/spring segments whose length changes
     * continuously and must not trigger a mesh bake every frame.
     */
    default Vector3f scale() {
        return new Vector3f(1f, 1f, 1f);
    }

    default String label() {
        return null;
    }

    ResourceKey<Level> dimension();
}
