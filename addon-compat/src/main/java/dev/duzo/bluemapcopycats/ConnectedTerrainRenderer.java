package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.map.hires.block.ResourceModelRenderer;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.adapter.ResourcesGson;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replays ordinary fence/wall resource models against Diagonal Blocks' expanded state.
 *
 * <p>Diagonal Blocks replaces wall cardinal WallSide values with booleans and adds four
 * diagonal booleans to fences and walls. Its client model loader translates booleans
 * back to low/none and duplicates a cardinal side model at 45 degrees. BlueMap never
 * runs that client loader, so do the same here while keeping each block's original
 * resource model and texture.
 */
public final class ConnectedTerrainRenderer implements BlockRenderer {

    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();

    private final ResourcePack resourcePack;
    private final ResourceModelRenderer delegate;
    private final Map<String, ResourcePath<Model>> sideModels = new ConcurrentHashMap<>();
    private final Map<String, Variant> diagonalVariants = new ConcurrentHashMap<>();

    public ConnectedTerrainRenderer(
            ResourcePack resourcePack,
            TextureGallery textureGallery,
            RenderSettings renderSettings) {
        this.resourcePack = resourcePack;
        this.delegate = new ResourceModelRenderer(resourcePack, textureGallery, renderSettings);
    }

    @Override
    public void render(
            BlockNeighborhood block,
            Variant ignoredVariant,
            TileModelView tileModel,
            Color blockColor) {
        String id = block.getBlockState().getFormatted();
        var original = ConnectedTerrainDispatch.original(id);
        if (original == null) return;

        int start = tileModel.getStart();
        int[] variants = {0};

        BlockState translated = translatedState(block.getBlockState(), isWall(id));
        original.forEach(
                translated,
                block.getX(),
                block.getY(),
                block.getZ(),
                variant -> {
                    Color color = new Color();
                    delegate.render(block, variant, tileModel.initialize(), color);
                    variants[0]++;
                });

        ResourcePath<Model> sideModel = sideModel(id, original, translated, isWall(id));
        int diagonals = 0;
        if (sideModel != null) {
            diagonals += renderDiagonal(block, tileModel, sideModel, "north_east", 45f);
            diagonals += renderDiagonal(block, tileModel, sideModel, "south_east", 135f);
            diagonals += renderDiagonal(block, tileModel, sideModel, "south_west", 225f);
            diagonals += renderDiagonal(block, tileModel, sideModel, "north_west", 315f);
        }

        int end = tileModel.getStart();
        tileModel.initialize(start);
        if (end > start) {
            blockColor.set(1f, 1f, 1f, 1f, true);
        }

        if (TRACED.add(id)) {
            Logger.global.logDebug(String.format(
                    "CONNECTED STATIC block=%s state=%s normalVariants=%s diagonalArms=%s sideModel=%s",
                    id, block.getBlockState(), variants[0], diagonals,
                    sideModel == null ? "<missing>" : sideModel.getFormatted()));
        }
    }

    private int renderDiagonal(
            BlockNeighborhood block,
            TileModelView tileModel,
            ResourcePath<Model> model,
            String property,
            float y) {
        if (!"true".equals(block.getBlockState().getProperties().get(property))) {
            return 0;
        }

        String key = model.getFormatted() + "@" + y;
        Variant variant = diagonalVariants.computeIfAbsent(key, ignored -> {
            String json = "{\"model\":\"" + model.getFormatted() + "\",\"y\":" + y + "}";
            return ResourcesGson.INSTANCE.fromJson(new StringReader(json), Variant.class);
        });

        Color color = new Color();
        delegate.render(block, variant, tileModel.initialize(), color);
        return 1;
    }

    private ResourcePath<Model> sideModel(
            String id,
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState original,
            BlockState translated,
            boolean wall) {
        ResourcePath<Model> cached = sideModels.get(id);
        if (cached != null) return cached;

        Map<String, String> props = new LinkedHashMap<>(translated.getProperties());
        props.put("north", wall ? "low" : "true");
        props.put("east", wall ? "none" : "false");
        props.put("south", wall ? "none" : "false");
        props.put("west", wall ? "none" : "false");
        if (wall) props.put("up", "false");
        for (String diagonal : List.of(
                "north_east", "south_east", "south_west", "north_west")) {
            if (props.containsKey(diagonal)) props.put(diagonal, "false");
        }

        BlockState northOnly = new BlockState(id, Map.copyOf(props));
        List<Variant> candidates = new ArrayList<>();
        original.forEach(northOnly, 0, 0, 0, candidates::add);

        ResourcePath<Model> missingPath = ResourcePack.MISSING_BLOCK_MODEL;
        Model missing = resourcePack.getModel(missingPath);

        for (Variant candidate : candidates) {
            ResourcePath<Model> path = candidate.getModel();
            Model model = resourcePack.getModel(path);
            if (model == null || model == missing) continue;

            String formatted = path.getFormatted();
            if (formatted.contains("side") || formatted.contains("fence")) {
                sideModels.put(id, path);
                return path;
            }
        }

        // Datagen convention used by vanilla and most mods. This fallback also handles
        // blocks whose multipart conditions are too unusual for the north-only probe.
        int colon = id.indexOf(':');
        if (colon >= 0) {
            String namespace = id.substring(0, colon);
            String path = id.substring(colon + 1);
            ResourcePath<Model> conventional =
                    new ResourcePath<>(namespace + ":block/" + path + "_side");
            Model model = resourcePack.getModel(conventional);
            if (model != null && model != missing) {
                sideModels.put(id, conventional);
                return conventional;
            }
        }

        return null;
    }

    private static BlockState translatedState(BlockState state, boolean wall) {
        if (!wall) return state;

        Map<String, String> props = new LinkedHashMap<>(state.getProperties());
        for (String direction : List.of("north", "east", "south", "west")) {
            String value = props.get(direction);
            if ("true".equals(value)) {
                props.put(direction, "low");
            } else if ("false".equals(value)) {
                props.put(direction, "none");
            }
        }
        return new BlockState(state.getFormatted(), Map.copyOf(props));
    }

    private static boolean isWall(String id) {
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.endsWith("_wall");
    }
}
