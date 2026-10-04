package dev.duzo.bluemapfurniture;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.adapter.ResourcesGson;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.world.mca.MCAWorld;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Installs Immersive Furniture's BlueMap block-entity and renderer compatibility. */
public final class ImmersiveFurnitureAdapter {

    private static final List<Key> BLOCK_ENTITY_IDS = List.of(
            new Key("immersive_furniture", "furniture"),
            new Key("immersive_furniture", "furniture_offset"));

    private static final List<String> FURNITURE_BLOCKS = List.of(
            "immersive_furniture:furniture",
            "immersive_furniture:furniture_entity",
            "immersive_furniture:furniture_light");

    private static final String DISPATCH_JSON = """
            {
              "variants": {
                "": {
                  "renderer": "bluemap_immersive_furniture:furniture"
                }
              }
            }
            """;

    private ImmersiveFurnitureAdapter() {
    }

    public static void register() {
        registerBlockEntities();
        ImmersiveFurnitureRenderer.register();
    }

    public static void onBlueMapEnable(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            Logger.global.logWarning(String.format(
                    "Immersive Furniture compatibility requires BlueMap 5.7 common API implementation; got %s",
                    api.getClass().getName()));
            return;
        }

        ResourcePack resourcePack = impl.blueMapService().getResourcePack();
        if (resourcePack == null) {
            Logger.global.logWarning(
                    "Immersive Furniture compatibility could not access BlueMap resource pack");
            return;
        }

        List<Path> worldRoots = impl.blueMapService().getWorlds().values().stream()
                .filter(MCAWorld.class::isInstance)
                .map(MCAWorld.class::cast)
                .map(MCAWorld::getWorldFolder)
                .distinct()
                .toList();
        ImmersiveFurnitureRuntime.configureWorldRoots(worldRoots);

        int routed = routeFurniture(resourcePack);
        Logger.global.logInfo(String.format(
                "Immersive Furniture compatibility ready: %s furniture blockstate(s) routed",
                routed));
    }

    private static void registerBlockEntities() {
        for (Key key : BLOCK_ENTITY_IDS) {
            BlockEntityType existing = BlockEntityType.REGISTRY.get(key);
            if (existing != null) {
                if (!ImmersiveFurnitureBlockEntity.class.equals(existing.getBlockEntityClass())) {
                    Logger.global.logWarning(String.format(
                            "Block entity %s is already registered to %s; "
                                    + "Immersive Furniture geometry NBT may be unavailable",
                            key,
                            existing.getBlockEntityClass().getName()));
                }
                continue;
            }

            BlockEntityType.REGISTRY.register(new BlockEntityType.Impl(
                    key,
                    ImmersiveFurnitureBlockEntity.class));
        }
    }

    @SuppressWarnings("unchecked")
    private static int routeFurniture(ResourcePack resourcePack) {
        try {
            Field statesField = ResourcePack.class.getDeclaredField("blockStates");
            Field pathsField = ResourcePack.class.getDeclaredField("blockStatePaths");
            statesField.setAccessible(true);
            pathsField.setAccessible(true);

            Map<ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>,
                    de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState> states =
                    (Map<ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>,
                            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>)
                            statesField.get(resourcePack);

            Map<String, ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
                    paths =
                    (Map<String, ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>)
                            pathsField.get(resourcePack);

            var dispatch = ResourcesGson.INSTANCE.fromJson(
                    new StringReader(DISPATCH_JSON),
                    de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState.class);

            int routed = 0;
            for (String blockId : FURNITURE_BLOCKS) {
                ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>
                        path = paths.get(blockId);
                if (path == null) continue;
                if (states.get(path) == null) continue;
                states.put(path, dispatch);
                routed++;
            }
            return routed;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError(
                    "Failed to install Immersive Furniture blockstate routing",
                    error);
            return 0;
        }
    }
}
