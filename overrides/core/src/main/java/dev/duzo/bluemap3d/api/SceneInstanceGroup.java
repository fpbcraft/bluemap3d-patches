package dev.duzo.bluemap3d.api;

import java.util.List;
import java.util.Objects;

/**
 * Repeated copies of one immutable prototype mesh inside one logical scene object.
 *
 * <p>geometryKey is global within a provider: objects using the same key+version share
 * one .bm3d file and one browser geometry/material allocation.
 */
public record SceneInstanceGroup(
        String id,
        String geometryKey,
        BlockVolume geometry,
        long geometryVersion,
        boolean canBakeGeometry,
        List<SceneInstance> instances) {

    public SceneInstanceGroup {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(geometryKey, "geometryKey");
        Objects.requireNonNull(geometry, "geometry");
        instances = List.copyOf(instances);
    }

    public SceneInstanceGroup(
            String id,
            String geometryKey,
            BlockVolume geometry,
            long geometryVersion,
            List<SceneInstance> instances) {
        this(id, geometryKey, geometry, geometryVersion, true, instances);
    }

    public SceneInstanceGroup cached(List<SceneInstance> cachedInstances) {
        return new SceneInstanceGroup(
                id,
                geometryKey,
                BlockVolume.EMPTY,
                geometryVersion,
                false,
                cachedInstances);
    }
}
