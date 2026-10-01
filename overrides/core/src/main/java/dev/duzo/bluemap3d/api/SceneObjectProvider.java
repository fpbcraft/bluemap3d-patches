package dev.duzo.bluemap3d.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.List;

/**
 * Supplies the live set of {@link SceneObject}s of one kind for one level.
 *
 * <p>Persistence and historical recording are core capabilities, not responsibilities
 * of individual Create/Sable integrations. Providers are persistent + recordable by
 * default and may explicitly opt out through {@link #lifecycle()}.
 */
public interface SceneObjectProvider {

    String id();

    Collection<? extends SceneObject> objects(ServerLevel level);

    /**
     * Lifecycle policy for every object returned by this provider.
     *
     * <p>The default intentionally restores last-known objects across unload/restart and
     * exposes them to history consumers.
     */
    default SceneObjectLifecycle lifecycle() {
        return SceneObjectLifecycle.PERSISTENT;
    }

    /**
     * Positive deletion evidence for persistent objects.
     *
     * <p>Absence from {@link #objects(ServerLevel)} is <strong>not</strong> deletion:
     * the backing chunk/entity/sublevel may merely be unloaded. Return ids here only
     * when the provider knows the object was actually destroyed/disassembled. Core then
     * removes its persisted last-known snapshot.
     */
    default Collection<String> deletedObjectIds(ServerLevel level) {
        return List.of();
    }

    default Collection<ResourceLocation> hiddenBlocks() {
        return List.of();
    }
}
