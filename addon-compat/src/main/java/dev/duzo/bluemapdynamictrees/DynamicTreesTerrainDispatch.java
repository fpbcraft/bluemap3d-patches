package dev.duzo.bluemapdynamictrees;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.adapter.ResourcesGson;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.TextureVariable;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.world.BlockState;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds Dynamic Trees custom-loader block models and routes them through the native
 * BlueMap renderer in this addon.
 *
 * <p>No Dynamic Trees classes are referenced here. BlueMap renders from its own chunk
 * snapshot and resource-pack representation, so the stable signature is the resource
 * itself: a geometry-less model with a {@code bark} texture, plus {@code rings} for a
 * branch model. This also makes addon families (BOP, Quark, Nature's Spirit, etc.) work
 * without a hardcoded namespace list.
 */
public final class DynamicTreesTerrainDispatch {

    public enum Kind {
        BRANCH,
        SURFACE_ROOT
    }

    public record Info(
            Kind kind,
            String family,
            ResourcePath<Texture> bark,
            ResourcePath<Texture> rings) {
    }

    private static final Map<String, Info> INFO = new ConcurrentHashMap<>();

    private static final String DISPATCH_JSON = """
            {
              "variants": {
                "": {
                  "renderer": "bluemap_dynamic_trees:terrain",
                  "model": "bluemap_dynamic_trees:block/placeholder"
                }
              }
            }
            """;

    private DynamicTreesTerrainDispatch() {
    }

    public static Info info(String blockId) {
        return INFO.get(blockId);
    }

    public static boolean sameFamily(String a, String b) {
        Info left = INFO.get(a);
        Info right = INFO.get(b);
        return left != null
                && right != null
                && left.kind() == Kind.BRANCH
                && right.kind() == Kind.BRANCH
                && left.family().equals(right.family());
    }

    @SuppressWarnings("unchecked")
    public static void apply(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            Logger.global.logWarning(String.format(
                    "Dynamic Trees terrain compatibility requires BlueMap 5.7 common API implementation; got %s",
                    api.getClass().getName()));
            return;
        }

        ResourcePack resourcePack = impl.blueMapService().getResourcePack();
        if (resourcePack == null) {
            Logger.global.logWarning("Dynamic Trees compatibility could not access BlueMap resource pack");
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

            INFO.clear();
            int branches = 0;
            int roots = 0;

            for (Map.Entry<String, ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
                    entry : new ArrayList<>(paths.entrySet())) {
                String id = entry.getKey();
                var original = states.get(entry.getValue());
                if (original == null) continue;

                Info info = inspect(resourcePack, id, original);
                if (info == null) continue;

                INFO.put(id, info);
                states.put(entry.getValue(), dispatch);
                if (info.kind() == Kind.BRANCH) branches++;
                else roots++;
            }

            Logger.global.logInfo(String.format(
                    "Dynamic Trees native terrain renderer routed %s branch block(s) and %s surface-root block(s)",
                    branches,
                    roots));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError("Failed to install Dynamic Trees native terrain renderer", error);
        }
    }

    private static Info inspect(
            ResourcePack resourcePack,
            String blockId,
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState stateResource) {
        String path = path(blockId);
        boolean branchName = path.endsWith("_branch") || path.endsWith("_roots");
        boolean rootName = path.endsWith("_root");
        if (!branchName && !rootName) return null;

        List<Variant> variants = new ArrayList<>(2);
        stateResource.forEach(new BlockState(blockId), 0, 0, 0, variants::add);
        if (variants.isEmpty()) return null;

        Model model = variants.getFirst().getModel().getResource(resourcePack::getModel);
        if (model == null || model.getElements() != null) return null;

        ResourcePath<Texture> bark = texture(model, "bark");
        if (bark == null) return null;

        ResourcePath<Texture> rings = texture(model, "rings");
        if (branchName && rings == null) return null;

        Kind kind = branchName ? Kind.BRANCH : Kind.SURFACE_ROOT;
        return new Info(kind, family(blockId), bark, rings);
    }

    private static ResourcePath<Texture> texture(Model model, String key) {
        TextureVariable variable = model.getTextures().get(key);
        if (variable == null) return null;
        return variable.getTexturePath(model.getTextures()::get);
    }

    private static String family(String id) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);

        if (path.startsWith("stripped_")) {
            path = path.substring("stripped_".length());
        }
        if (path.endsWith("_branch")) {
            path = path.substring(0, path.length() - "_branch".length());
        } else if (path.endsWith("_roots")) {
            path = path.substring(0, path.length() - "_roots".length());
        } else if (path.endsWith("_root")) {
            path = path.substring(0, path.length() - "_root".length());
        }

        return namespace + ":" + path;
    }

    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }
}
