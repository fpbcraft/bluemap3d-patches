package dev.duzo.bluemapfurniture;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.BmMap;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.adapter.ResourcesGson;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.common.serverinterface.ServerWorld;
import de.bluecolored.bluemap.core.world.mca.MCAWorld;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType;

import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

        Set<Path> worldRoots = new LinkedHashSet<>();

        // BlueMap's configured MCA source is useful for offline/custom maps, but on a
        // live NeoForge server the authoritative save root is the ServerWorld path.
        // A map can be configured against a dimension/source path that does not contain
        // level saved-data such as data/immersive_furniture.dat.
        impl.blueMapService().getWorlds().values().stream()
                .filter(MCAWorld.class::isInstance)
                .map(MCAWorld.class::cast)
                .map(MCAWorld::getWorldFolder)
                .forEach(worldRoots::add);

        if (impl.plugin() != null) {
            impl.plugin().getServerInterface().getLoadedServerWorlds().stream()
                    .map(ServerWorld::getWorldFolder)
                    .forEach(worldRoots::add);
        }

        ImmersiveFurnitureRuntime.configureWorldRoots(worldRoots);

        // Install blockstate dispatch before any texture-gallery work. BlueMap's
        // ResourcePath values are memoized, and the furniture placeholder must never
        // be allowed to become the active cached resource while compatibility setup is
        // doing unrelated texture work.
        RouteResult initialRouting = routeFurniture(resourcePack);

        List<ImmersiveFurnitureData.Definition> persisted =
                ImmersiveFurnitureRuntime.persistedDefinitions();
        int bakedTextures = 0;
        for (BmMap map : impl.blueMapService().getMaps().values()) {
            bakedTextures += ImmersiveFurnitureRenderer.preloadTextures(
                    map.getTextureGallery(), persisted);
            try (OutputStream out = map.getStorage().textures().write()) {
                map.getTextureGallery().writeTexturesFile(out);
            } catch (IOException error) {
                Logger.global.logError(
                        "Failed to persist Immersive Furniture textures for map '"
                                + map.getId() + "'",
                        error);
            }
        }

        // Re-assert and verify routing after texture setup as a guard against any
        // ResourcePath/cache lifecycle changes during BlueMap startup.
        RouteResult finalRouting = routeFurniture(resourcePack);
        Logger.global.logInfo(String.format(
                "Immersive Furniture compatibility ready: routed=%s/%s verified=%s/%s bakedTextures=%s",
                initialRouting.routed(),
                FURNITURE_BLOCKS.size(),
                finalRouting.verified(),
                FURNITURE_BLOCKS.size(),
                bakedTextures));
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
    private static RouteResult routeFurniture(ResourcePack resourcePack) {
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
            int verified = 0;
            for (String blockId : FURNITURE_BLOCKS) {
                ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>
                        path = paths.get(blockId);
                if (path == null) continue;
                if (states.get(path) == null) continue;
                states.put(path, dispatch);

                // ResourcePath memoizes the first resolved resource. By the time BlueMap's
                // API onEnable listeners run, the original Immersive Furniture blockstate
                // may already have been resolved and cached on this path. Updating only the
                // backing map would then leave rendering pinned to the stock oak-log
                // placeholder forever.
                path.setResource(dispatch);

                routed++;
                if (path.getResource() == dispatch && states.get(path) == dispatch) {
                    verified++;
                } else {
                    Logger.global.logWarning(
                            "Immersive Furniture routing verification failed for " + blockId);
                }
            }
            return new RouteResult(routed, verified);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError(
                    "Failed to install Immersive Furniture blockstate routing",
                    error);
            return new RouteResult(0, 0);
        }
    }

    private record RouteResult(int routed, int verified) {
    }
}
