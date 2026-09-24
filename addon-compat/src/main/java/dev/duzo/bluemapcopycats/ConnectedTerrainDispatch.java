package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.adapter.ResourcesGson;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes normal fence/wall blockstates through the compatibility renderer after BlueMap
 * has loaded all resource packs. The original parsed blockstate is retained so the
 * renderer can still use the block's real post/side models and textures.
 */
public final class ConnectedTerrainDispatch {

    private static final Map<String,
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>
            ORIGINALS = new ConcurrentHashMap<>();

    private static final String DISPATCH_JSON = """
            {
              "variants": {
                "": {
                  "renderer": "bluemap_copycats:connected",
                  "model": "bluemap_copycats:block/placeholder"
                }
              }
            }
            """;

    private ConnectedTerrainDispatch() {
    }

    public static de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState
    original(String id) {
        return ORIGINALS.get(id);
    }

    @SuppressWarnings("unchecked")
    public static void apply(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            Logger.global.logWarning(String.format(
                    "Connected fence/wall compatibility requires BlueMap 5.7 common API implementation; got %s",
                    api.getClass().getName()));
            return;
        }

        ResourcePack resourcePack = impl.blueMapService().getResourcePack();
        if (resourcePack == null) {
            Logger.global.logWarning("Connected fence/wall compatibility could not access BlueMap resource pack");
            return;
        }

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

            int patched = 0;
            for (Map.Entry<String, ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
                    entry : new ArrayList<>(paths.entrySet())) {
                String id = entry.getKey();
                if (!isConnectedBlock(id)) continue;

                var original = states.get(entry.getValue());
                if (original == null) continue;

                ORIGINALS.put(id, original);
                states.put(entry.getValue(), dispatch);
                patched++;
            }

            Logger.global.logInfo(String.format(
                    "Connected fence/wall compatibility routed %s loaded blockstate(s)",
                    patched));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError("Failed to install connected fence/wall compatibility", error);
        }
    }

    private static boolean isConnectedBlock(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return false;

        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);

        // Copycats has its own per-material renderer in this addon; do not steal it.
        if ("copycats".equals(namespace) || "create_connected".equals(namespace)) {
            return false;
        }

        return path.endsWith("_fence") || path.endsWith("_wall");
    }
}
