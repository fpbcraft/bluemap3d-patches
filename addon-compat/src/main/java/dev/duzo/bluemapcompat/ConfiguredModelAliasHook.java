package dev.duzo.bluemapcompat;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.adapter.ResourcesGson;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Applies config-driven blockstate and model-resource aliases.
 *
 * <p>Block aliases rewrite BlueMap's block-id -> parsed-blockstate mapping. Resource
 * aliases instead replace the model referenced by the target blockstate while leaving
 * that blockstate intact. The latter is important for custom-loader blocks such as
 * Dynamic Trees branches: their blockstate has a valid default variant, while the
 * primitive log blockstate expects unrelated properties such as {@code axis}.
 *
 * <p>Original mappings/resources are snapshotted once and restored before every
 * application, so hot reloads are deterministic and alias chains cannot accumulate.
 */
final class ConfiguredModelAliasHook {

    private final ResourcePack resourcePack;

    private final Map<String,
            ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
            originalPaths;
    private final Map<String,
            ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
            livePaths;

    private final Map<ResourcePath<Model>, Model> originalModels;
    private final Map<ResourcePath<Model>, Model> liveModels;

    private final Set<String> warnedMissingSources = ConcurrentHashMap.newKeySet();
    private final Set<String> appliedBlockTargets = ConcurrentHashMap.newKeySet();
    private final Set<ResourcePath<Model>> appliedModelTargets = ConcurrentHashMap.newKeySet();

    private ConfiguredModelAliasHook(
            ResourcePack resourcePack,
            Map<String,
                    ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
                    livePaths,
            Map<ResourcePath<Model>, Model> liveModels) {
        this.resourcePack = resourcePack;
        this.livePaths = livePaths;
        this.originalPaths = new LinkedHashMap<>(livePaths);
        this.liveModels = liveModels;
        this.originalModels = new LinkedHashMap<>(liveModels);
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
            Field modelsField = ResourcePack.class.getDeclaredField("models");
            modelsField.setAccessible(true);

            var paths = (Map<String,
                    ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>)
                    pathsField.get(resourcePack);
            var models = (Map<ResourcePath<Model>, Model>) modelsField.get(resourcePack);

            ConfiguredModelAliasHook hook =
                    new ConfiguredModelAliasHook(resourcePack, paths, models);
            hook.apply();
            CompatManager.onReload(hook::apply);
            return hook;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError("Failed to install config-driven model aliases", error);
            return null;
        }
    }

    private synchronized void apply() {
        restore();

        int blockAliases = 0;
        int modelAliases = 0;
        int inlineModels = 0;
        Map<ResourcePath<Model>, Model> inlineToBake = new LinkedHashMap<>();

        for (String blockId : originalPaths.keySet()) {
            CompatRuleSet.ModelMatch match =
                    CompatManager.rules().model(blockId, Map.of(), "terrain");
            if (match == null) continue;

            if ("alias".equals(match.model().type)) {
                String sourceId = match.resolveSourceBlock(blockId);
                if (sourceId == null || sourceId.equals(blockId)) continue;

                var sourcePath = originalPaths.get(sourceId);
                if (sourcePath == null) {
                    warnMissing(
                            match.rule().id,
                            blockId,
                            sourceId,
                            "source block");
                    continue;
                }

                livePaths.put(blockId, sourcePath);
                appliedBlockTargets.add(blockId);
                blockAliases++;
                continue;
            }

            if ("resource_alias".equals(match.model().type)) {
                String sourceId = match.resolveSourceModel(blockId);
                String targetId = match.resolveTargetModel(blockId);
                if (sourceId == null || targetId == null || sourceId.equals(targetId)) continue;

                ResourcePath<Model> sourcePath = new ResourcePath<>(sourceId);
                ResourcePath<Model> targetPath = new ResourcePath<>(targetId);
                Model sourceModel = originalModels.get(sourcePath);
                if (sourceModel == null) {
                    warnMissing(
                            match.rule().id,
                            blockId,
                            sourceId,
                            "source model");
                    continue;
                }

                liveModels.put(targetPath, sourceModel);
                appliedModelTargets.add(targetPath);
                modelAliases++;
            }
        }

        // Model-resource rules are matched directly against model ids. This covers assets
        // that have no one-to-one block id (Dynamic Trees saplings, helper models and
        // smart-model fragments), so an old compatibility resource pack can be represented
        // entirely by config.
        for (ResourcePath<Model> targetPath : new ArrayList<>(originalModels.keySet())) {
            String targetId = targetPath.getFormatted();
            CompatRuleSet.ModelResourceMatch match =
                    CompatManager.rules().modelResource(targetId, "terrain");
            if (match == null) continue;

            if ("resource_alias".equals(match.model().type)) {
                String sourceId = match.resolveSourceModel(targetId);
                if (sourceId == null || sourceId.equals(targetId)) continue;

                ResourcePath<Model> sourcePath = new ResourcePath<>(sourceId);
                Model sourceModel = originalModels.get(sourcePath);
                if (sourceModel == null) {
                    warnMissing(
                            match.rule().id,
                            targetId,
                            sourceId,
                            "source model");
                    continue;
                }

                liveModels.put(targetPath, sourceModel);
                appliedModelTargets.add(targetPath);
                modelAliases++;
                continue;
            }

            if ("inline".equals(match.model().type)) {
                try {
                    Model inline = ResourcesGson.INSTANCE.fromJson(
                            match.inlineDefinition(), Model.class);
                    if (inline == null) {
                        throw new IllegalArgumentException("inline definition parsed to null");
                    }
                    liveModels.put(targetPath, inline);
                    appliedModelTargets.add(targetPath);
                    inlineToBake.put(targetPath, inline);
                    inlineModels++;
                } catch (RuntimeException error) {
                    Logger.global.logWarning(String.format(
                            "Could not apply inline model rule '%s' to %s: %s",
                            match.rule().id, targetId, error.getMessage()));
                }
            }
        }

        // BlueMap normally resolves parents and optimizes models during ResourcePack.bake().
        // Inline models arrive later, so perform those same model-local bake steps after all
        // replacements are installed. Doing this as a second pass lets one inline model
        // inherit from another inline model regardless of rule order.
        for (Map.Entry<ResourcePath<Model>, Model> entry : inlineToBake.entrySet()) {
            try {
                Model model = entry.getValue();
                model.applyParent(resourcePack);
                model.optimize(resourcePack);
                model.calculateProperties(resourcePack);
            } catch (RuntimeException error) {
                Logger.global.logWarning(String.format(
                        "Could not bake inline compatibility model %s: %s",
                        entry.getKey().getFormatted(), error.getMessage()));
            }
        }

        Logger.global.logInfo(String.format(
                "Config-driven model aliases applied: %s blockstate alias(es), "
                        + "%s model-resource alias(es), %s inline model override(s)",
                blockAliases,
                modelAliases,
                inlineModels));
    }

    private void restore() {
        for (String target : appliedBlockTargets) {
            var original = originalPaths.get(target);
            if (original == null) {
                livePaths.remove(target);
            } else {
                livePaths.put(target, original);
            }
        }
        appliedBlockTargets.clear();

        for (ResourcePath<Model> target : appliedModelTargets) {
            Model original = originalModels.get(target);
            if (original == null) {
                liveModels.remove(target);
            } else {
                liveModels.put(target, original);
            }
        }
        appliedModelTargets.clear();
    }

    private void warnMissing(
            String ruleId,
            String blockId,
            String sourceId,
            String sourceKind) {
        String warningKey = ruleId + "|" + blockId + "|" + sourceKind + "|" + sourceId;
        if (warnedMissingSources.add(warningKey)) {
            Logger.global.logWarning(String.format(
                    "Compatibility model alias '%s' matched %s but %s %s is not loaded",
                    ruleId,
                    blockId,
                    sourceKind,
                    sourceId));
        }
    }
}
