package dev.duzo.bluemapcompat;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies whole-block model aliases from compatibility config.
 *
 * <p>Aliases rewrite BlueMap's block-id -> parsed-blockstate resource mapping. The
 * original mapping is snapshotted once and restored before every application, so hot
 * reloads are deterministic and alias chains/cycles cannot accumulate.
 */
final class ConfiguredModelAliasHook {

    private final Map<String,
            ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
            originalPaths;
    private final Map<String,
            ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
            livePaths;
    private final Set<String> warnedMissingSources = ConcurrentHashMap.newKeySet();
    private final Set<String> appliedTargets = ConcurrentHashMap.newKeySet();

    private ConfiguredModelAliasHook(
            Map<String,
                    ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
                    livePaths) {
        this.livePaths = livePaths;
        this.originalPaths = new LinkedHashMap<>(livePaths);
    }

    @SuppressWarnings("unchecked")
    static ConfiguredModelAliasHook install(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            Logger.global.logWarning(String.format(
                    "Model alias compatibility requires BlueMap 5.7 common API implementation; got %s",
                    api.getClass().getName()));
            return null;
        }

        ResourcePack resourcePack = impl.blueMapService().getResourcePack();
        if (resourcePack == null) {
            Logger.global.logWarning("Model alias compatibility could not access BlueMap resource pack");
            return null;
        }

        try {
            Field pathsField = ResourcePack.class.getDeclaredField("blockStatePaths");
            pathsField.setAccessible(true);

            var paths = (Map<String,
                    ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>)
                    pathsField.get(resourcePack);

            ConfiguredModelAliasHook hook = new ConfiguredModelAliasHook(paths);
            hook.apply();
            CompatManager.onReload(hook::apply);
            return hook;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError("Failed to install config-driven model aliases", error);
            return null;
        }
    }

    private synchronized void apply() {
        // Restore only entries previously touched by this hook. Do not clear the whole
        // table: another BlueMap addon may legitimately mutate unrelated mappings.
        for (String target : appliedTargets) {
            var original = originalPaths.get(target);
            if (original == null) {
                livePaths.remove(target);
            } else {
                livePaths.put(target, original);
            }
        }
        appliedTargets.clear();

        int applied = 0;
        for (String blockId : originalPaths.keySet()) {
            CompatRuleSet.ModelMatch match =
                    CompatManager.rules().model(blockId, Map.of(), "terrain");
            if (match == null) continue;

            String sourceId = match.model().resolveSourceBlock(blockId);
            if (sourceId == null || sourceId.equals(blockId)) continue;

            var sourcePath = originalPaths.get(sourceId);
            if (sourcePath == null) {
                String warningKey = match.rule().id + "|" + blockId + "|" + sourceId;
                if (warnedMissingSources.add(warningKey)) {
                    Logger.global.logWarning(String.format(
                            "Compatibility model alias '%s' matched %s but source block %s is not loaded",
                            match.rule().id, blockId, sourceId));
                }
                continue;
            }

            livePaths.put(blockId, sourcePath);
            appliedTargets.add(blockId);
            applied++;
        }

        Logger.global.logInfo(String.format(
                "Config-driven model aliases applied to %s loaded block id(s)", applied));
    }
}
