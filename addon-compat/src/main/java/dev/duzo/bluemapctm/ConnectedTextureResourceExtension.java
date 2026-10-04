package dev.duzo.bluemapctm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePackExtension;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePackExtensionType;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.Key;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * BlueMap 5.7 resource-pack extension that discovers Fusion metadata and pre-bakes
 * Fusion/Create connected-texture sheets into ordinary BlueMap texture resources.
 *
 * <p>All virtual textures are created before BlueMap builds its texture gallery. No
 * generated resource pack or runtime gallery mutation is required.
 */
public final class ConnectedTextureResourceExtension implements ResourcePackExtension {

    private static final Key TYPE_KEY = new Key("bluemap_compat", "connected_textures");
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public static final ResourcePackExtensionType<ConnectedTextureResourceExtension> TYPE =
            new ResourcePackExtensionType<>() {
                @Override
                public Key getKey() {
                    return TYPE_KEY;
                }

                @Override
                public ConnectedTextureResourceExtension create() {
                    return new ConnectedTextureResourceExtension();
                }
            };

    private final Map<String, FusionSpec> fusion = new ConcurrentHashMap<>();

    private ConnectedTextureResourceExtension() {
    }

    public static void register() {
        if (ResourcePackExtensionType.REGISTRY.get(TYPE_KEY) == null) {
            ResourcePackExtensionType.REGISTRY.register(TYPE);
        }
    }

    @Override
    public void loadResources(Path root) throws IOException {
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) return;

        try (Stream<Path> stream = Files.walk(assets)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".png.mcmeta"))
                    .forEach(this::loadFusionMetadata);
        }
    }

    @Override
    public Iterable<Texture> loadTextures(Path root) throws IOException {
        Path assets = root.resolve("assets");
        if (!Files.isDirectory(assets)) return List.of();

        List<Texture> textures = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(assets)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".png"))
                    .forEach(path -> {
                        String textureId = textureId(assets, path);
                        if (textureId == null) return;

                        FusionSpec fusionSpec = fusion.get(textureId);
                        String createType = CreateConnectedTextures.typeForSheet(textureId);
                        if (fusionSpec == null && createType == null) return;

                        try {
                            BufferedImage image = ImageIO.read(path.toFile());
                            if (image == null) return;
                            if (fusionSpec != null) {
                                textures.addAll(bakeFusion(textureId, image, fusionSpec));
                            }
                            if (createType != null) {
                                textures.addAll(bakeCreate(textureId, image, createType));
                            }
                        } catch (IOException | RuntimeException error) {
                            warnOnce(
                                    "texture:" + textureId,
                                    "Could not bake connected texture " + textureId + ": " + error);
                        }
                    });
        }
        return textures;
    }

    FusionSpec fusionSpec(String texture) {
        return fusion.get(texture);
    }

    boolean hasFusion(String texture) {
        return fusion.containsKey(texture);
    }

    int fusionCount() {
        return fusion.size();
    }

    ResourcePath<Texture> fusionMaterial(String texture, FusionSpec spec, int mask) {
        if ("pieced".equals(spec.layout()) || "overlay".equals(spec.layout())) {
            return virtualPath("fusion/" + safe(texture) + "/" + spec.layout() + "/mask/" + (mask & 0xFF));
        }
        int tile = ConnectedTextureLayout.fusionTile(spec.layout(), mask);
        return virtualPath(
                "fusion/" + safe(texture) + "/" + spec.layout() + "/tile/" + tile);
    }

    ResourcePath<Texture> createMaterial(String sheet, String type, int tile) {
        return virtualPath(
                "create/" + safe(sheet) + "/" + type.toLowerCase(Locale.ROOT) + "/tile/" + tile);
    }

    private void loadFusionMetadata(Path metadataPath) {
        try (Reader reader = Files.newBufferedReader(metadataPath)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonElement fusionElement = root.get("fusion");
            if (fusionElement == null || !fusionElement.isJsonObject()) return;

            JsonObject data = fusionElement.getAsJsonObject();
            String type = data.has("type") ? stripNamespace(data.get("type").getAsString()) : "";
            if (!"connecting".equals(type)) return;

            String textureId = textureIdFromMetadata(metadataPath);
            if (textureId == null) return;

            String layout = data.has("layout")
                    ? data.get("layout").getAsString().toLowerCase(Locale.ROOT)
                    : "full";
            ConnectedTextureLayout.Grid grid = ConnectedTextureLayout.fusionGrid(layout);

            FusionConnectionPredicate predicate = FusionConnectionPredicate.sameState();
            JsonElement connections = data.get("connections");
            if (connections != null) {
                if (connections.isJsonArray()) {
                    predicate = FusionConnectionPredicate.or(connections.getAsJsonArray());
                } else if (connections.isJsonObject()) {
                    predicate = FusionConnectionPredicate.parse(connections.getAsJsonObject());
                }
            }

            int frameWidth = -1;
            int frameHeight = -1;
            JsonElement animationElement = root.get("animation");
            if (animationElement != null && animationElement.isJsonObject()) {
                JsonObject animation = animationElement.getAsJsonObject();
                if (animation.has("width")) frameWidth = animation.get("width").getAsInt();
                if (animation.has("height")) frameHeight = animation.get("height").getAsInt();
            }

            boolean customSubTexture = false;
            JsonElement subTexture = data.get("sub_texture");
            if (subTexture != null && subTexture.isJsonObject()) {
                JsonObject sub = subTexture.getAsJsonObject();
                String subType = sub.has("type") ? stripNamespace(sub.get("type").getAsString()) : "base";
                customSubTexture = !("base".equals(subType) || "vanilla".equals(subType));
            }

            fusion.put(
                    textureId,
                    new FusionSpec(
                            layout,
                            grid,
                            predicate,
                            frameWidth,
                            frameHeight,
                            customSubTexture));

            if (customSubTexture) {
                warnOnce(
                        "sub-texture:" + textureId,
                        "Fusion texture " + textureId
                                + " uses a nested sub_texture type; BlueMap preserves the pixels "
                                + "and connection layout but cannot execute that client-only texture processor");
            }
        } catch (IOException | RuntimeException error) {
            warnOnce(
                    "metadata:" + metadataPath,
                    "Could not parse Fusion texture metadata " + metadataPath + ": " + error);
        }
    }

    private List<Texture> bakeFusion(
            String source,
            BufferedImage image,
            FusionSpec spec) throws IOException {
        Frame frame = frame(image, spec.grid(), spec.frameWidth(), spec.frameHeight());
        if (frame == null) {
            warnOnce(
                    "fusion-grid:" + source,
                    "Fusion connected texture " + source + " has incompatible dimensions "
                            + image.getWidth() + "x" + image.getHeight()
                            + " for " + spec.grid().width() + "x" + spec.grid().height());
            return List.of();
        }

        List<BufferedImage> tiles = cropTiles(image, spec.grid(), frame);
        List<Texture> output = new ArrayList<>();

        if ("pieced".equals(spec.layout())) {
            for (int mask = 0; mask < 256; mask++) {
                BufferedImage composite = composePieced(tiles, mask);
                output.add(texture(
                        virtualPath("fusion/" + safe(source) + "/pieced/mask/" + mask),
                        composite));
            }
            return output;
        }

        if ("overlay".equals(spec.layout())) {
            for (int mask = 0; mask < 256; mask++) {
                BufferedImage composite = composeOverlay(tiles, mask);
                output.add(texture(
                        virtualPath("fusion/" + safe(source) + "/overlay/mask/" + mask),
                        composite));
            }
            return output;
        }

        for (int tile = 0; tile < tiles.size(); tile++) {
            output.add(texture(
                    virtualPath(
                            "fusion/" + safe(source) + "/" + spec.layout() + "/tile/" + tile),
                    tiles.get(tile)));
        }
        return output;
    }

    private List<Texture> bakeCreate(
            String source,
            BufferedImage image,
            String type) throws IOException {
        ConnectedTextureLayout.Grid grid = ConnectedTextureLayout.createGrid(type);
        Frame frame = frame(image, grid, -1, -1);
        if (frame == null) {
            warnOnce(
                    "create-grid:" + source,
                    "Create connected texture " + source + " has incompatible dimensions "
                            + image.getWidth() + "x" + image.getHeight()
                            + " for " + grid.width() + "x" + grid.height());
            return List.of();
        }

        List<BufferedImage> tiles = cropTiles(image, grid, frame);
        List<Texture> output = new ArrayList<>(tiles.size());
        for (int tile = 0; tile < tiles.size(); tile++) {
            output.add(texture(
                    virtualPath(
                            "create/" + safe(source) + "/" + type.toLowerCase(Locale.ROOT)
                                    + "/tile/" + tile),
                    tiles.get(tile)));
        }
        return output;
    }

    private static Frame frame(
            BufferedImage image,
            ConnectedTextureLayout.Grid grid,
            int explicitWidth,
            int explicitHeight) {
        int width = explicitWidth > 0 ? explicitWidth : image.getWidth();
        int height = explicitHeight > 0 ? explicitHeight : image.getHeight();

        if (explicitWidth <= 0 && explicitHeight <= 0) {
            if (image.getWidth() % grid.width() == 0) {
                int tile = image.getWidth() / grid.width();
                int expectedHeight = tile * grid.height();
                if (expectedHeight > 0 && expectedHeight <= image.getHeight()
                        && image.getHeight() % expectedHeight == 0) {
                    height = expectedHeight;
                }
            }
            if (height == image.getHeight() && image.getHeight() % grid.height() == 0) {
                int tile = image.getHeight() / grid.height();
                int expectedWidth = tile * grid.width();
                if (expectedWidth > 0 && expectedWidth <= image.getWidth()
                        && image.getWidth() % expectedWidth == 0) {
                    width = expectedWidth;
                }
            }
        } else if (explicitWidth <= 0 && explicitHeight > 0) {
            int tile = height / grid.height();
            width = tile * grid.width();
        } else if (explicitHeight <= 0 && explicitWidth > 0) {
            int tile = width / grid.width();
            height = tile * grid.height();
        }

        if (width <= 0 || height <= 0
                || width > image.getWidth() || height > image.getHeight()
                || width % grid.width() != 0 || height % grid.height() != 0) {
            return null;
        }
        return new Frame(width, height);
    }

    private static List<BufferedImage> cropTiles(
            BufferedImage image,
            ConnectedTextureLayout.Grid grid,
            Frame frame) {
        int tileWidth = frame.width() / grid.width();
        int tileHeight = frame.height() / grid.height();
        List<BufferedImage> output = new ArrayList<>(grid.width() * grid.height());

        for (int y = 0; y < grid.height(); y++) {
            for (int x = 0; x < grid.width(); x++) {
                output.add(copyRegion(
                        image,
                        x * tileWidth,
                        y * tileHeight,
                        tileWidth,
                        tileHeight));
            }
        }
        return output;
    }

    private static BufferedImage composePieced(List<BufferedImage> tiles, int mask) {
        BufferedImage sample = tiles.getFirst();
        BufferedImage output =
                new BufferedImage(sample.getWidth(), sample.getHeight(), BufferedImage.TYPE_INT_ARGB);

        int whole = ConnectedTextureLayout.fusionPiecedWholeTile(mask);
        if (whole >= 0) {
            drawFull(output, tiles.get(whole));
            return output;
        }

        int halfW = sample.getWidth() / 2;
        int halfH = sample.getHeight() / 2;
        copyQuadrant(
                tiles.get(ConnectedTextureLayout.fusionPiecedCornerTile(true, true, mask)),
                output,
                0, 0, halfW, halfH);
        copyQuadrant(
                tiles.get(ConnectedTextureLayout.fusionPiecedCornerTile(true, false, mask)),
                output,
                halfW, 0, sample.getWidth() - halfW, halfH);
        copyQuadrant(
                tiles.get(ConnectedTextureLayout.fusionPiecedCornerTile(false, false, mask)),
                output,
                halfW, halfH, sample.getWidth() - halfW, sample.getHeight() - halfH);
        copyQuadrant(
                tiles.get(ConnectedTextureLayout.fusionPiecedCornerTile(false, true, mask)),
                output,
                0, halfH, halfW, sample.getHeight() - halfH);
        return output;
    }

    private static BufferedImage composeOverlay(List<BufferedImage> tiles, int mask) {
        BufferedImage sample = tiles.getFirst();
        BufferedImage output =
                new BufferedImage(sample.getWidth(), sample.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            for (int tile : ConnectedTextureLayout.fusionOverlayTiles(mask)) {
                if (tile >= 0 && tile < tiles.size()) {
                    graphics.drawImage(tiles.get(tile), 0, 0, null);
                }
            }
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static void copyQuadrant(
            BufferedImage source,
            BufferedImage target,
            int x,
            int y,
            int width,
            int height) {
        int sourceX = x == 0 ? 0 : source.getWidth() - width;
        int sourceY = y == 0 ? 0 : source.getHeight() - height;
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(
                    source,
                    x,
                    y,
                    x + width,
                    y + height,
                    sourceX,
                    sourceY,
                    sourceX + width,
                    sourceY + height,
                    null);
        } finally {
            graphics.dispose();
        }
    }

    private static void drawFull(BufferedImage target, BufferedImage source) {
        Graphics2D graphics = target.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
    }

    private static BufferedImage copyRegion(
            BufferedImage source,
            int x,
            int y,
            int width,
            int height) {
        BufferedImage output =
                new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            graphics.drawImage(
                    source,
                    0,
                    0,
                    width,
                    height,
                    x,
                    y,
                    x + width,
                    y + height,
                    null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static Texture texture(ResourcePath<Texture> path, BufferedImage image)
            throws IOException {
        Texture texture = Texture.from(path, image);
        path.setResource(texture);
        return texture;
    }

    private static ResourcePath<Texture> virtualPath(String path) {
        return new ResourcePath<>("bluemap_ctm", path);
    }

    private static String textureId(Path assetsRoot, Path file) {
        Path relative = assetsRoot.relativize(file);
        if (relative.getNameCount() < 3) return null;
        String namespace = relative.getName(0).toString();
        if (!"textures".equals(relative.getName(1).toString())) return null;
        String path = relative.subpath(2, relative.getNameCount()).toString().replace('\\', '/');
        if (!path.endsWith(".png")) return null;
        return namespace + ":" + path.substring(0, path.length() - 4);
    }

    private static String textureIdFromMetadata(Path metadata) {
        String normalized = metadata.toString().replace('\\', '/');
        int assets = normalized.lastIndexOf("/assets/");
        if (assets < 0) {
            if (normalized.startsWith("assets/")) assets = -1;
            else return null;
        }
        String relative = assets < 0
                ? normalized.substring("assets/".length())
                : normalized.substring(assets + "/assets/".length());
        int slash = relative.indexOf('/');
        if (slash < 0) return null;
        String namespace = relative.substring(0, slash);
        String marker = "/textures/";
        int textures = relative.indexOf(marker);
        if (textures < 0 || !relative.endsWith(".png.mcmeta")) return null;
        String path = relative.substring(textures + marker.length());
        return namespace + ":" + path.substring(0, path.length() - ".png.mcmeta".length());
    }

    private static String safe(String texture) {
        return texture.toLowerCase(Locale.ROOT)
                .replace(':', '/')
                .replaceAll("[^a-z0-9_./-]", "_");
    }

    private static String stripNamespace(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon < 0 ? lower : lower.substring(colon + 1);
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) Logger.global.logWarning(message);
    }

    record FusionSpec(
            String layout,
            ConnectedTextureLayout.Grid grid,
            FusionConnectionPredicate predicate,
            int frameWidth,
            int frameHeight,
            boolean customSubTexture) {
    }

    private record Frame(int width, int height) {
    }
}
