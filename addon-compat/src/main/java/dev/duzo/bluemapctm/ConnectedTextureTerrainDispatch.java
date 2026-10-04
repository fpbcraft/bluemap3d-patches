package dev.duzo.bluemapctm;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.adapter.ResourcesGson;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.VariantSet;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Element;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Face;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import dev.duzo.bluemapcopycats.ConnectedTerrainDispatch;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes only ordinary static blockstates that actually use Fusion/Create CT textures
 * through the neighbour-aware terrain renderer.
 */
public final class ConnectedTextureTerrainDispatch {

    private static final Map<String,
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>
            ORIGINALS = new ConcurrentHashMap<>();

    private static final String DISPATCH_JSON = """
            {
              "variants": {
                "": {
                  "renderer": "bluemap_ctm:connected"
                }
              }
            }
            """;

    private ConnectedTextureTerrainDispatch() {
    }

    public static de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState
    original(String id) {
        return ORIGINALS.get(id);
    }

    @SuppressWarnings("unchecked")
    public static void apply(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            Logger.global.logWarning(String.format(
                    "Connected texture compatibility requires BlueMap 5.7 common API implementation; got %s",
                    api.getClass().getName()));
            return;
        }

        ResourcePack resourcePack = impl.blueMapService().getResourcePack();
        if (resourcePack == null) {
            Logger.global.logWarning(
                    "Connected texture compatibility could not access BlueMap resource pack");
            return;
        }

        ConnectedTextureResourceExtension extension =
                resourcePack.getResourcePackExtension(ConnectedTextureResourceExtension.TYPE);
        if (extension == null) {
            Logger.global.logWarning(
                    "Connected texture resource extension was not installed before BlueMap loaded resources");
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

            Map<String,
                    ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
                    paths =
                    (Map<String,
                            ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>)
                            pathsField.get(resourcePack);

            var dispatch = ResourcesGson.INSTANCE.fromJson(
                    new StringReader(DISPATCH_JSON),
                    de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState.class);

            int routed = 0;
            int skippedCustom = 0;
            int candidates = 0;

            for (Map.Entry<String,
                    ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
                    entry : new ArrayList<>(paths.entrySet())) {
                String id = entry.getKey();

                // Fence/wall/diagonal and Copycats compatibility already owns these ids.
                if (ConnectedTerrainDispatch.original(id) != null) continue;

                var original = states.get(entry.getValue());
                if (original == null || ORIGINALS.containsKey(id)) continue;

                if (hasCustomRenderer(original)) {
                    skippedCustom++;
                    continue;
                }

                if (!usesConnectedTexture(resourcePack, extension, id, original)) continue;
                candidates++;

                ORIGINALS.put(id, original);
                states.put(entry.getValue(), dispatch);

                // ResourcePath memoizes resource resolution in BlueMap 5.7.
                entry.getValue().setResource(dispatch);
                routed++;
            }

            Logger.global.logInfo(String.format(
                    "Connected texture compatibility routed %s/%s static blockstate id(s); skippedCustom=%s FusionTextures=%s discoveredCreateStyle=%s",
                    routed,
                    candidates,
                    skippedCustom,
                    extension.fusionCount(),
                    extension.discoveredCreateStyleCount()));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError(
                    "Failed to install connected texture terrain dispatch",
                    error);
        }
    }

    private static boolean usesConnectedTexture(
            ResourcePack resourcePack,
            ConnectedTextureResourceExtension extension,
            String blockId,
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState state) {
        for (Variant variant : allVariants(state)) {
            Model model = variant.getModel().getResource(resourcePack::getModel);
            if (model == null || model.getElements() == null) continue;

            for (Element element : model.getElements()) {
                if (element == null) continue;
                for (Face face : element.getFaces().values()) {
                    if (face == null) continue;
                    ResourcePath<Texture> texture =
                            face.getTexture().getTexturePath(model.getTextures()::get);
                    if (texture == null) continue;
                    String id = texture.getFormatted();
                    if (extension.hasFusion(id)
                            || extension.createSpec(
                                            id,
                                            blockId,
                                            Map.of(),
                                            de.bluecolored.bluemap.core.util.Direction.NORTH)
                                    != null) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean hasCustomRenderer(
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState state) {
        for (Variant variant : allVariants(state)) {
            if (variant.getRenderer() != BlockRendererType.DEFAULT) return true;
        }
        return false;
    }

    private static List<Variant> allVariants(
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState state) {
        List<Variant> output = new ArrayList<>();

        if (state.getVariants() != null) {
            for (VariantSet set : state.getVariants().getVariants()) {
                add(output, set);
            }
            add(output, state.getVariants().getDefaultVariant());
        }

        if (state.getMultipart() != null) {
            for (VariantSet set : state.getMultipart().getParts()) {
                add(output, set);
            }
        }

        return output;
    }

    private static void add(List<Variant> output, VariantSet set) {
        if (set == null || set.getVariants() == null) return;
        output.addAll(List.of(set.getVariants()));
    }
}
