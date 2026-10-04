package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.map.hires.block.ResourceModelRenderer;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        if (isFence(id)) {
            variants[0] += renderFenceBaseAndCardinals(
                    block, tileModel, original, translated);
        } else {
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
        }

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

        List<Variant> variants = connectionVariants(
                original,
                translated,
                wall,
                cardinalDirection,
                block.getX(),
                block.getY(),
                block.getZ());
        if (variants.isEmpty()) return 0;

        int rendered = 0;
        for (Variant variant : variants) {
            Color color = new Color();
            TileModelView diagonal = tileModel.initialize();
            delegate.render(block, variant, diagonal, color);
            if (diagonal.getSize() == 0) continue;

            // Mirror Diagonal Blocks' QuadUtils.rotateQuad() exactly: scale the source
            // cardinal arm along its axis, then rotate it -45 degrees around block center.
            float diagonalScale = (float) Math.sqrt(2.0);
            boolean scaleX =
                    "east".equals(cardinalDirection) || "west".equals(cardinalDirection);
            diagonal
                    .translate(-0.5f, -0.5f, -0.5f)
                    .scale(scaleX ? diagonalScale : 1f, 1f, scaleX ? 1f : diagonalScale)
                    .rotate(DiagonalDirectionMapping.rotationDegrees(), 0f, 1f, 0f)
                    .translate(0.5f, 0.5f, 0.5f);
            rendered++;
        }
        return rendered;
    }

    private int renderFenceBaseAndCardinals(
            BlockNeighborhood block,
            TileModelView tileModel,
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState original,
            BlockState translated) {
        BlockState base = disconnectedState(translated, false);
        int rendered = 0;

        List<Variant> baseVariants = variantsFor(
                original, base, block.getX(), block.getY(), block.getZ());
        for (Variant variant : baseVariants) {
            Color color = new Color();
            delegate.render(block, variant, tileModel.initialize(), color);
            rendered++;
        }

        for (String cardinal : List.of("north", "east", "south", "west")) {
            if (!"true".equals(translated.getProperties().get(cardinal))) continue;
            for (Variant variant : connectionVariants(
                    original,
                    translated,
                    false,
                    cardinal,
                    block.getX(),
                    block.getY(),
                    block.getZ())) {
                Color color = new Color();
                delegate.render(block, variant, tileModel.initialize(), color);
                rendered++;
            }
        }

        return rendered;
    }

    private List<Variant> connectionVariants(
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState original,
            BlockState translated,
            boolean wall,
            String cardinalDirection,
            int x,
            int y,
            int z) {
        BlockState disconnected = disconnectedState(translated, wall);
        List<Variant> baseline = variantsFor(original, disconnected, x, y, z);

        Map<String, String> props = new LinkedHashMap<>(disconnected.getProperties());
        props.put(cardinalDirection, wall ? "low" : "true");
        BlockState cardinalOnly = new BlockState(disconnected.getFormatted(), Map.copyOf(props));
        List<Variant> connected = variantsFor(original, cardinalOnly, x, y, z);

        // Parsed multipart selectors keep stable Variant object identities. Subtracting
        // the disconnected state by identity gives exactly the selector(s) introduced by
        // enabling this cardinal arm, including multi-part custom fence/pane models.
        List<Variant> introduced = new ArrayList<>();
        for (Variant candidate : connected) {
            if (!containsIdentity(baseline, candidate)) introduced.add(candidate);
        }
        return introduced;
    }

    private static List<Variant> variantsFor(
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState original,
            BlockState state,
            int x,
            int y,
            int z) {
        List<Variant> variants = new ArrayList<>();
        original.forEach(state, x, y, z, variants::add);
        return variants;
    }

    private static boolean containsIdentity(List<Variant> variants, Variant candidate) {
        for (Variant variant : variants) {
            if (variant == candidate) return true;
        }
        return false;
    }

    private static BlockState disconnectedState(BlockState state, boolean wall) {
        Map<String, String> props = new LinkedHashMap<>(state.getProperties());
        String disconnected = wall ? "none" : "false";
        for (String direction : List.of("north", "east", "south", "west")) {
            if (props.containsKey(direction)) props.put(direction, disconnected);
        }
        for (String diagonal : List.of(
                "north_east", "south_east", "south_west", "north_west")) {
            if (props.containsKey(diagonal)) props.put(diagonal, "false");
        }
        return new BlockState(state.getFormatted(), Map.copyOf(props));
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

    static boolean isFence(String id) {
        int colon = id.indexOf(':');
        String namespace = colon >= 0 ? id.substring(0, colon) : "";
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return "diagonalfences".equals(namespace) || path.endsWith("_fence");
    }

    private static boolean isWall(String id) {
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.endsWith("_wall");
    }
}
