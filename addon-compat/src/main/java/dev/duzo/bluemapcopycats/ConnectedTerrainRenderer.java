package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.map.hires.block.ResourceModelRenderer;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.util.ArrayList;
import java.util.HashSet;
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
    private final Map<String, Variant> connectionVariants = new ConcurrentHashMap<>();

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

        int diagonals = 0;
        diagonals += renderDiagonal(block, tileModel, original, translated, isWall(id),
                "north_east", DiagonalDirectionMapping.cardinalFor("north_east"));
        diagonals += renderDiagonal(block, tileModel, original, translated, isWall(id),
                "south_east", DiagonalDirectionMapping.cardinalFor("south_east"));
        diagonals += renderDiagonal(block, tileModel, original, translated, isWall(id),
                "south_west", DiagonalDirectionMapping.cardinalFor("south_west"));
        diagonals += renderDiagonal(block, tileModel, original, translated, isWall(id),
                "north_west", DiagonalDirectionMapping.cardinalFor("north_west"));

        int end = tileModel.getStart();
        tileModel.initialize(start);
        if (end > start) {
            blockColor.set(1f, 1f, 1f, 1f, true);
        }

        if (TRACED.add(id)) {
            Logger.global.logDebug(String.format(
                    "CONNECTED STATIC block=%s state=%s normalVariants=%s diagonalArms=%s",
                    id, block.getBlockState(), variants[0], diagonals));
        }
    }

    private int renderDiagonal(
            BlockNeighborhood block,
            TileModelView tileModel,
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState original,
            BlockState translated,
            boolean wall,
            String diagonalProperty,
            String cardinalDirection) {
        if (!"true".equals(block.getBlockState().getProperties().get(diagonalProperty))) {
            return 0;
        }

        Variant variant = connectionVariant(
                block.getBlockState().getFormatted(),
                original,
                translated,
                wall,
                cardinalDirection);
        if (variant == null) return 0;

        Color color = new Color();
        TileModelView diagonal = tileModel.initialize();
        delegate.render(block, variant, diagonal, color);
        if (diagonal.getSize() == 0) return 0;

        // Diagonal Blocks first chooses the clockwise-adjacent cardinal segment
        // (E->NE, S->SE, W->SW, N->NW), then applies its -45 degree baked-quad
        // transform. BlueMap's model Y rotation convention is inverted at this stage,
        // so the equivalent mesh transform here remains +45 degrees.
        float diagonalScale = (float) Math.sqrt(2.0);
        boolean scaleX = "east".equals(cardinalDirection) || "west".equals(cardinalDirection);
        diagonal
                .translate(-0.5f, -0.5f, -0.5f)
                .scale(scaleX ? diagonalScale : 1f, 1f, scaleX ? 1f : diagonalScale)
                .rotate(45f, 0f, 1f, 0f)
                .translate(0.5f, 0.5f, 0.5f);
        return 1;
    }

    private Variant connectionVariant(
            String id,
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState original,
            BlockState translated,
            boolean wall,
            String cardinalDirection) {
        String key = id + "@" + cardinalDirection;
        Variant cached = connectionVariants.get(key);
        if (cached != null) return cached;

        Map<String, String> props = new LinkedHashMap<>(translated.getProperties());
        String disconnectedValue = wall ? "none" : "false";
        for (String direction : List.of("north", "east", "south", "west")) {
            props.put(direction, disconnectedValue);
        }
        if (wall) props.put("up", "false");
        for (String diagonal : List.of(
                "north_east", "south_east", "south_west", "north_west")) {
            if (props.containsKey(diagonal)) props.put(diagonal, "false");
        }

        BlockState disconnected = new BlockState(id, Map.copyOf(props));
        Set<ResourcePath<Model>> disconnectedModels = new HashSet<>();
        original.forEach(disconnected, 0, 0, 0,
                variant -> disconnectedModels.add(variant.getModel()));

        props.put(cardinalDirection, wall ? "low" : "true");
        BlockState cardinalOnly = new BlockState(id, Map.copyOf(props));
        List<Variant> candidates = new ArrayList<>();
        original.forEach(cardinalOnly, 0, 0, 0, candidates::add);

        ResourcePath<Model> missingPath = ResourcePack.MISSING_BLOCK_MODEL;
        Model missing = resourcePack.getModel(missingPath);
        for (Variant candidate : candidates) {
            ResourcePath<Model> modelPath = candidate.getModel();
            if (disconnectedModels.contains(modelPath)) continue;

            Model model = resourcePack.getModel(modelPath);
            if (model == null || model == missing) continue;

            connectionVariants.put(key, candidate);
            return candidate;
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
