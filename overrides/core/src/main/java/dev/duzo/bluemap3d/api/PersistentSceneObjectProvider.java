package dev.duzo.bluemap3d.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Core-owned persistence wrapper for normal scene providers.
 *
 * <p>Every provider whose lifecycle has restoreOnLaunch=true is wrapped automatically
 * by {@link BlueMap3D#register(SceneObjectProvider)}. A missing live object is treated
 * as temporarily unavailable; only {@link SceneObjectProvider#deletedObjectIds(ServerLevel)}
 * removes a saved object.
 *
 * <p>Snapshots are committed only after the matching mesh version is known to be
 * published, guaranteeing restart can reuse an existing BM3D file without touching
 * unloaded game state.
 */
final class PersistentSceneObjectProvider implements SceneObjectProvider {

    private final SceneObjectProvider delegate;

    private PersistentSceneObjectProvider(SceneObjectProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    static SceneObjectProvider wrap(SceneObjectProvider provider) {
        if (!provider.lifecycle().restoreOnLaunch()) return provider;
        return provider instanceof PersistentSceneObjectProvider
                ? provider
                : new PersistentSceneObjectProvider(provider);
    }

    static SceneObjectProvider unwrap(SceneObjectProvider provider) {
        return provider instanceof PersistentSceneObjectProvider persistent
                ? persistent.delegate
                : provider;
    }

    static boolean wraps(SceneObjectProvider registered, SceneObjectProvider original) {
        return registered == original
                || (registered instanceof PersistentSceneObjectProvider persistent
                    && persistent.delegate == original);
    }

    static void meshPublished(String provider, String objectId, long version) {
        SceneObjectPersistenceStore.meshPublished(provider, objectId, version);
    }

    static void flushNow() {
        SceneObjectPersistenceStore.flushNow();
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public SceneObjectLifecycle lifecycle() {
        return delegate.lifecycle();
    }

    @Override
    public Collection<ResourceLocation> hiddenBlocks() {
        return delegate.hiddenBlocks();
    }

    @Override
    public Collection<String> deletedObjectIds(ServerLevel level) {
        return delegate.deletedObjectIds(level);
    }

    @Override
    public Collection<String> authoritativeObjectPrefixes(ServerLevel level) {
        return delegate.authoritativeObjectPrefixes(level);
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        SceneObjectPersistenceStore.ensureLoaded();

        Collection<? extends SceneObject> live = delegate.objects(level);
        Map<String, SceneObject> merged = new LinkedHashMap<>();
        Set<String> liveIds = new HashSet<>();

        if (live != null) {
            for (SceneObject object : live) {
                if (object == null) continue;
                merged.put(object.id(), object);
                liveIds.add(object.id());
                SceneObjectPersistenceStore.stage(id(), object);
            }
        }

        Collection<String> deleted = delegate.deletedObjectIds(level);
        if (deleted != null) {
            for (String objectId : deleted) {
                if (objectId == null) continue;
                SceneObjectPersistenceStore.remove(id(), objectId);
            }
        }

        String dimension = level.dimension().location().toString();
        SceneObjectPersistenceStore.pruneAuthoritativelyMissing(
                id(),
                dimension,
                delegate.authoritativeObjectPrefixes(level),
                liveIds);

        for (SceneObject cached
                : SceneObjectPersistenceStore.cached(id(), dimension, level, liveIds)) {
            merged.putIfAbsent(cached.id(), cached);
        }

        SceneObjectPersistenceStore.flushIfDue();
        return List.copyOf(merged.values());
    }
}
