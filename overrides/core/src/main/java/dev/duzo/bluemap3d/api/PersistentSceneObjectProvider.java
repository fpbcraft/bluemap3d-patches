package dev.duzo.bluemap3d.api;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Persistence");

    private final SceneObjectProvider delegate;
    /**
     * Diagnostic only: last source reported for each Create contraption object. Logging is
     * transition-based, so the 500 ms scene poll does not spam the server log.
     */
    private final Map<String, ObjectSource> createObjectSources = new ConcurrentHashMap<>();

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

        Set<String> cachedIds = new HashSet<>();
        for (SceneObject cached
                : SceneObjectPersistenceStore.cached(id(), dimension, level, liveIds)) {
            cachedIds.add(cached.id());
            merged.putIfAbsent(cached.id(), cached);
        }

        logCreateSources(level, merged, liveIds, cachedIds);

        SceneObjectPersistenceStore.flushIfDue();
        return List.copyOf(merged.values());
    }

    private void logCreateSources(
            ServerLevel level,
            Map<String, SceneObject> merged,
            Set<String> liveIds,
            Set<String> cachedIds) {
        if (!"create_contraptions".equals(id())) return;

        Set<String> present = new HashSet<>(liveIds);
        present.addAll(cachedIds);
        String dimension = level.dimension().location().toString();
        String dimensionPrefix = dimension + "|";

        for (String objectId : present) {
            ObjectSource source = liveIds.contains(objectId)
                    ? ObjectSource.LIVE
                    : ObjectSource.PERSISTED;
            String sourceKey = dimensionPrefix + objectId;
            ObjectSource previous = createObjectSources.put(sourceKey, source);
            if (previous == source) continue;

            SceneObject object = merged.get(objectId);
            LOGGER.info(
                    "CONTRAPTION-SOURCE-DIAG source={} previous={} id={} dimension={} pos={}",
                    source,
                    previous == null ? "NONE" : previous,
                    objectId,
                    level.dimension().location(),
                    object == null ? "unknown" : object.position());
        }

        for (String sourceKey : Set.copyOf(createObjectSources.keySet())) {
            if (!sourceKey.startsWith(dimensionPrefix)) continue;
            String objectId = sourceKey.substring(dimensionPrefix.length());
            if (present.contains(objectId)) continue;
            ObjectSource previous = createObjectSources.remove(sourceKey);
            LOGGER.info(
                    "CONTRAPTION-SOURCE-DIAG source=ABSENT previous={} id={} dimension={}",
                    previous, objectId, level.dimension().location());
        }
    }

    private enum ObjectSource {
        LIVE,
        PERSISTED
    }
}
