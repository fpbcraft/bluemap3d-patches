package dev.duzo.bluemap3d.bake;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Applies neighbour-aware connected textures to ordinary resource-pack quads.
 *
 * <p>Supported natively:
 * <ul>
 *   <li>Fusion 1.21 built-in connecting layouts: full, simple, horizontal, vertical,
 *       compact, pieced and overlay.</li>
 *   <li>Fusion built-in connection predicates, including boolean composition,
 *       direction, same block/state, match block/state and in-front variants.</li>
 *   <li>Create's built-in CT sheet families used by casings, framed glass, windows,
 *       scaffolds, tanks, chassis, crafters, encased cogs, girders and copper roofs.</li>
 * </ul>
 *
 * <p>The output uses virtual texture ids backed by cropped tile images. This keeps the
 * existing atlas and mesh format unchanged while avoiding a generated resource pack.
 */
final class ConnectedTextureResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/ConnectedTextures");
    private static final String VIRTUAL_PREFIX = "bluemap3d:connected/";
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final AssetIndex assets;
    private final Function<BlockState, List<ModelQuad>> baseQuads;
    private final Map<String, Optional<FusionSpec>> fusionSpecs = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> rawTextures = new ConcurrentHashMap<>();
    private final Map<String, BufferedImage> virtualTextures = new ConcurrentHashMap<>();
    private final Map<TransformKey, List<ModelQuad>> transformed = new ConcurrentHashMap<>();

    ConnectedTextureResolver(
            AssetIndex assets,
            Function<BlockState, List<ModelQuad>> baseQuads) {
        this.assets = assets;
        this.baseQuads = baseQuads;
    }

    List<ModelQuad> resolve(List<ModelQuad> input, BlockRenderContext context) {
        if (input.isEmpty()) return input;
        List<ModelQuad> out = null;

        for (int index = 0; index < input.size(); index++) {
            ModelQuad quad = input.get(index);
            List<ModelQuad> replacement = resolveQuad(quad, context);
            boolean unchanged = replacement.size() == 1 && replacement.get(0) == quad;
            if (out == null && unchanged) continue;

            if (out == null) {
                out = new ArrayList<>(input.size() + 4);
                out.addAll(input.subList(0, index));
            }
            out.addAll(replacement);
        }

        return out == null ? input : List.copyOf(out);
    }

    /**
     * Lets state-only callers sample a Fusion texture safely. A Fusion sheet is not a
     * vanilla sprite, so putting the whole 8x6/4x4 sheet into the atlas is always wrong.
     */
    BufferedImage defaultTexture(String texture) {
        FusionSpec spec = fusionSpec(texture);
        if (spec == null) return null;
        return tileImage("fusion", texture, spec.grid(), 0, "full".equals(spec.layout()));
    }

    BufferedImage virtualTexture(String texture) {
        return virtualTextures.get(texture);
    }

    private List<ModelQuad> resolveQuad(ModelQuad quad, BlockRenderContext context) {
        Direction face = quad.shadeFace() != null ? quad.shadeFace() : quad.cullFace();
        if (face == null) return List.of(quad);

        AxisPair axes = axesFor(quad, face);
        if (axes == null) return List.of(quad);

        FusionSpec fusion = fusionSpec(quad.texture());
        if (fusion != null) {
            int mask = connectionMask(
                    context,
                    face,
                    axes,
                    (other, front, direction) ->
                            fusion.predicate().test(
                                    context.state(), other, front, face, direction));
            return transformFusion(quad, fusion, mask);
        }

        CreateConnectedTextures.Spec create = CreateConnectedTextures.find(
                quad.texture(), context.state());
        if (create == null) return List.of(quad);

        String sheet = create.sheetTexture(context.x(), context.y(), context.z());
        if (rawTexture(sheet) == null) return List.of(quad);

        int mask = connectionMask(
                context,
                face,
                axes,
                (other, front, direction) ->
                        createConnects(create, face, other, context, axes, direction));
        mask = constrainCreateCorners(mask);
        ConnectedTextureLayout.Grid grid = ConnectedTextureLayout.createGrid(create.type());
        int tile = ConnectedTextureLayout.createTile(create.type(), mask);
        String key = "create|" + create.cacheKey(context.x(), context.y(), context.z())
                + "|" + mask;
        return transformed.computeIfAbsent(
                new TransformKey(quad, key),
                ignored -> replaceTexture(
                        quad,
                        virtualId("create", sheet, grid, tile, false),
                        "create",
                        sheet,
                        grid,
                        tile,
                        false));
    }

    private static int constrainCreateCorners(int mask) {
        if ((mask & (ConnectedTextureLayout.TOP | ConnectedTextureLayout.RIGHT))
                != (ConnectedTextureLayout.TOP | ConnectedTextureLayout.RIGHT)) {
            mask &= ~ConnectedTextureLayout.TOP_RIGHT;
        }
        if ((mask & (ConnectedTextureLayout.RIGHT | ConnectedTextureLayout.BOTTOM))
                != (ConnectedTextureLayout.RIGHT | ConnectedTextureLayout.BOTTOM)) {
            mask &= ~ConnectedTextureLayout.BOTTOM_RIGHT;
        }
        if ((mask & (ConnectedTextureLayout.BOTTOM | ConnectedTextureLayout.LEFT))
                != (ConnectedTextureLayout.BOTTOM | ConnectedTextureLayout.LEFT)) {
            mask &= ~ConnectedTextureLayout.BOTTOM_LEFT;
        }
        if ((mask & (ConnectedTextureLayout.LEFT | ConnectedTextureLayout.TOP))
                != (ConnectedTextureLayout.LEFT | ConnectedTextureLayout.TOP)) {
            mask &= ~ConnectedTextureLayout.TOP_LEFT;
        }
        return mask;
    }

    private boolean createConnects(
            CreateConnectedTextures.Spec current,
            Direction face,
            BlockState other,
            BlockRenderContext context,
            AxisPair axes,
            String direction) {
        if (other == null || other.isAir()) return false;

        int[] offset = offsetFor(direction, axes);
        int ox = context.x() + offset[0];
        int oy = context.y() + offset[1];
        int oz = context.z() + offset[2];

        for (ModelQuad neighbourQuad : baseQuads.apply(other)) {
            Direction neighbourFace = neighbourQuad.shadeFace() != null
                    ? neighbourQuad.shadeFace()
                    : neighbourQuad.cullFace();
            if (neighbourFace != face) continue;
            CreateConnectedTextures.Spec candidate =
                    CreateConnectedTextures.find(neighbourQuad.texture(), other);
            if (candidate == null) continue;

            if (candidate.type().equals(current.type())) {
                if (current.positionVariant() && candidate.positionVariant()) return true;
                String a = current.sheetTexture(context.x(), context.y(), context.z());
                String b = candidate.sheetTexture(ox, oy, oz);
                if (a.equals(b)) return true;
            }
        }
        return false;
    }

    private List<ModelQuad> transformFusion(ModelQuad quad, FusionSpec spec, int mask) {
        String cacheKey = "fusion|" + quad.texture() + "|" + spec.layout() + "|" + mask;
        return transformed.computeIfAbsent(
                new TransformKey(quad, cacheKey),
                ignored -> {
                    if ("overlay".equals(spec.layout())) {
                        List<Integer> tiles = ConnectedTextureLayout.fusionOverlayTiles(mask);
                        if (tiles.isEmpty()) return List.of();
                        List<ModelQuad> result = new ArrayList<>(tiles.size());
                        for (int tile : tiles) {
                            String id = virtualId(
                                    "fusion", quad.texture(), spec.grid(), tile, false);
                            BufferedImage image = tileImage(
                                    "fusion", quad.texture(), spec.grid(), tile, false);
                            if (image != null) {
                                virtualTextures.putIfAbsent(id, image);
                                result.add(withTexture(quad, id));
                            }
                        }
                        return List.copyOf(result);
                    }

                    if ("pieced".equals(spec.layout())) {
                        int whole = ConnectedTextureLayout.fusionPiecedWholeTile(mask);
                        if (whole >= 0) {
                            return replaceTexture(
                                    quad,
                                    virtualId("fusion", quad.texture(), spec.grid(), whole, false),
                                    "fusion",
                                    quad.texture(),
                                    spec.grid(),
                                    whole,
                                    false);
                        }
                        return splitPieced(quad, spec, mask);
                    }

                    int tile = ConnectedTextureLayout.fusionTile(spec.layout(), mask);
                    boolean legacyFull = "full".equals(spec.layout());
                    return replaceTexture(
                            quad,
                            virtualId("fusion", quad.texture(), spec.grid(), tile, legacyFull),
                            "fusion",
                            quad.texture(),
                            spec.grid(),
                            tile,
                            legacyFull);
                });
    }

    private List<ModelQuad> splitPieced(ModelQuad quad, FusionSpec spec, int mask) {
        float[] p = quad.positions();
        float[] uv = quad.uvs();
        if (p.length != 12 || uv.length != 8) return List.of(quad);

        float midU = 0f;
        float midV = 0f;
        for (int i = 0; i < 4; i++) {
            midU += uv[i * 2];
            midV += uv[i * 2 + 1];
        }
        midU /= 4f;
        midV /= 4f;

        List<ModelQuad> out = new ArrayList<>(4);
        for (int corner = 0; corner < 4; corner++) {
            int next = (corner + 1) & 3;
            int prev = (corner + 3) & 3;
            boolean top = uv[corner * 2 + 1] < midV;
            boolean left = uv[corner * 2] < midU;
            int tile = ConnectedTextureLayout.fusionPiecedCornerTile(top, left, mask);
            String id = virtualId("fusion", quad.texture(), spec.grid(), tile, false);
            BufferedImage image = tileImage(
                    "fusion", quad.texture(), spec.grid(), tile, false);
            if (image == null) continue;
            virtualTextures.putIfAbsent(id, image);

            float[] positions = new float[12];
            float[] uvs = new float[8];

            copyPosition(p, corner, positions, 0);
            midpointPosition(p, corner, next, positions, 1);
            centerPosition(p, positions, 2);
            midpointPosition(p, corner, prev, positions, 3);

            copyUv(uv, corner, uvs, 0);
            midpointUv(uv, corner, next, uvs, 1);
            centerUv(uv, uvs, 2);
            midpointUv(uv, corner, prev, uvs, 3);

            out.add(new ModelQuad(
                    quad.cullFace(),
                    quad.shadeFace(),
                    positions,
                    uvs,
                    id,
                    quad.tint()));
        }
        return out.isEmpty() ? List.of(quad) : List.copyOf(out);
    }

    private List<ModelQuad> replaceTexture(
            ModelQuad quad,
            String id,
            String provider,
            String sheet,
            ConnectedTextureLayout.Grid grid,
            int tile,
            boolean legacyFull) {
        BufferedImage image = tileImage(provider, sheet, grid, tile, legacyFull);
        if (image == null) return List.of(quad);
        virtualTextures.putIfAbsent(id, image);
        return List.of(withTexture(quad, id));
    }

    private FusionSpec fusionSpec(String texture) {
        Optional<FusionSpec> cached = fusionSpecs.computeIfAbsent(
                texture,
                this::loadFusionSpec);
        return cached.orElse(null);
    }

    private Optional<FusionSpec> loadFusionSpec(String texture) {
        TextureId id = TextureId.parse(texture);
        if (id == null) return Optional.empty();

        byte[] bytes = assets.read(
                "assets/" + id.namespace() + "/textures/" + id.path() + ".png.mcmeta");
        if (bytes == null) return Optional.empty();

        try {
            JsonObject root = JsonParser.parseString(
                    new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement fusionElement = root.get("fusion");
            if (fusionElement == null || !fusionElement.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject fusion = fusionElement.getAsJsonObject();
            String type = fusion.has("type") ? stripNamespace(fusion.get("type").getAsString()) : "";
            if (!"connecting".equals(type)) return Optional.empty();

            String layout = fusion.has("layout")
                    ? fusion.get("layout").getAsString().toLowerCase(Locale.ROOT)
                    : "full";
            ConnectedTextureLayout.Grid grid = ConnectedTextureLayout.fusionGrid(layout);

            FusionConnectionPredicate predicate = FusionConnectionPredicate.sameState();
            JsonElement connections = fusion.get("connections");
            if (connections != null) {
                if (connections.isJsonArray()) {
                    predicate = FusionConnectionPredicate.or(connections.getAsJsonArray());
                } else if (connections.isJsonObject()) {
                    predicate = FusionConnectionPredicate.parse(connections.getAsJsonObject());
                }
            }

            return Optional.of(new FusionSpec(layout, grid, predicate));
        } catch (RuntimeException error) {
            if (WARNED.add("fusion:" + texture)) {
                LOGGER.warn(
                        "Ignoring invalid Fusion connected-texture metadata for {}: {}",
                        texture,
                        error.toString());
            }
            return Optional.empty();
        }
    }

    private int connectionMask(
            BlockRenderContext context,
            Direction face,
            AxisPair axes,
            ConnectionTest test) {
        String[] names = {
            "top", "top_right", "right", "bottom_right",
            "bottom", "bottom_left", "left", "top_left"
        };
        int[][] offsets = {
            axes.up(),
            add(axes.up(), axes.right()),
            axes.right(),
            add(negate(axes.up()), axes.right()),
            negate(axes.up()),
            add(negate(axes.up()), negate(axes.right())),
            negate(axes.right()),
            add(axes.up(), negate(axes.right()))
        };

        int mask = 0;
        for (int i = 0; i < offsets.length; i++) {
            int[] offset = offsets[i];
            BlockState other = context.stateAtOffset(offset[0], offset[1], offset[2]);
            BlockState front = context.stateAtOffset(
                    offset[0] + face.getStepX(),
                    offset[1] + face.getStepY(),
                    offset[2] + face.getStepZ());
            if (test.test(other, front, names[i])) mask |= 1 << i;
        }
        return mask;
    }

    private BufferedImage tileImage(
            String provider,
            String texture,
            ConnectedTextureLayout.Grid grid,
            int tile,
            boolean legacyFull) {
        String id = virtualId(provider, texture, grid, tile, legacyFull);
        BufferedImage cached = virtualTextures.get(id);
        if (cached != null) return cached;

        BufferedImage sheet = rawTexture(texture);
        if (sheet == null) return null;

        int effectiveWidth = sheet.getWidth();
        int effectiveHeight = sheet.getHeight();
        if (legacyFull && grid.width() == 8 && grid.height() == 6
                && effectiveWidth == effectiveHeight) {
            effectiveHeight = effectiveHeight * 6 / 8;
        }
        if (effectiveWidth % grid.width() != 0 || effectiveHeight % grid.height() != 0) {
            if (WARNED.add("grid:" + texture)) {
                LOGGER.warn(
                        "Connected texture {} is {}x{} but layout requires a {}x{} grid",
                        texture,
                        sheet.getWidth(),
                        sheet.getHeight(),
                        grid.width(),
                        grid.height());
            }
            return null;
        }

        int tileWidth = effectiveWidth / grid.width();
        int tileHeight = effectiveHeight / grid.height();
        int tx = tile % grid.width();
        int ty = tile / grid.width();
        if (tx < 0 || tx >= grid.width() || ty < 0 || ty >= grid.height()) return null;

        BufferedImage crop = new BufferedImage(
                tileWidth, tileHeight, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D graphics = crop.createGraphics();
        try {
            graphics.drawImage(
                    sheet,
                    0,
                    0,
                    tileWidth,
                    tileHeight,
                    tx * tileWidth,
                    ty * tileHeight,
                    (tx + 1) * tileWidth,
                    (ty + 1) * tileHeight,
                    null);
        } finally {
            graphics.dispose();
        }

        virtualTextures.putIfAbsent(id, crop);
        return crop;
    }

    private BufferedImage rawTexture(String texture) {
        if (rawTextures.containsKey(texture)) return rawTextures.get(texture);
        TextureId id = TextureId.parse(texture);
        if (id == null) return null;
        byte[] png = assets.read(
                "assets/" + id.namespace() + "/textures/" + id.path() + ".png");
        if (png == null) return null;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image != null) rawTextures.put(texture, image);
            return image;
        } catch (IOException error) {
            return null;
        }
    }

    private static AxisPair axesFor(ModelQuad quad, Direction face) {
        int[] right = uvAxis(quad, true, face);
        int[] down = uvAxis(quad, false, face);
        if (right == null || down == null) return fallbackAxes(face);
        return new AxisPair(negate(down), right);
    }

    private static int[] uvAxis(ModelQuad quad, boolean uAxis, Direction face) {
        float[] positions = quad.positions();
        float[] uv = quad.uvs();
        if (positions.length != 12 || uv.length != 8) return null;

        int target = uAxis ? 0 : 1;
        int other = uAxis ? 1 : 0;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            float primary = uv[j * 2 + target] - uv[i * 2 + target];
            float secondary = uv[j * 2 + other] - uv[i * 2 + other];
            if (Math.abs(primary) < 1e-4f || Math.abs(primary) < Math.abs(secondary)) {
                continue;
            }
            float sign = primary > 0 ? 1f : -1f;
            float dx = (positions[j * 3] - positions[i * 3]) * sign;
            float dy = (positions[j * 3 + 1] - positions[i * 3 + 1]) * sign;
            float dz = (positions[j * 3 + 2] - positions[i * 3 + 2]) * sign;
            int[] axis = dominantAxis(dx, dy, dz);
            if (axis != null && dotFace(axis, face) == 0) return axis;
        }
        return null;
    }

    private static int[] dominantAxis(float x, float y, float z) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (Math.max(ax, Math.max(ay, az)) < 1e-4f) return null;
        if (ax >= ay && ax >= az) return new int[]{x >= 0 ? 1 : -1, 0, 0};
        if (ay >= ax && ay >= az) return new int[]{0, y >= 0 ? 1 : -1, 0};
        return new int[]{0, 0, z >= 0 ? 1 : -1};
    }

    private static int dotFace(int[] axis, Direction face) {
        return axis[0] * face.getStepX()
                + axis[1] * face.getStepY()
                + axis[2] * face.getStepZ();
    }

    private static AxisPair fallbackAxes(Direction face) {
        return switch (face) {
            case UP -> new AxisPair(new int[]{0, 0, -1}, new int[]{1, 0, 0});
            case DOWN -> new AxisPair(new int[]{0, 0, 1}, new int[]{1, 0, 0});
            case NORTH -> new AxisPair(new int[]{0, 1, 0}, new int[]{-1, 0, 0});
            case SOUTH -> new AxisPair(new int[]{0, 1, 0}, new int[]{1, 0, 0});
            case WEST -> new AxisPair(new int[]{0, 1, 0}, new int[]{0, 0, 1});
            case EAST -> new AxisPair(new int[]{0, 1, 0}, new int[]{0, 0, -1});
        };
    }

    private static String virtualId(
            String provider,
            String texture,
            ConnectedTextureLayout.Grid grid,
            int tile,
            boolean legacyFull) {
        String safe = texture.replace(':', '/');
        return VIRTUAL_PREFIX + provider + "/" + safe + "/"
                + grid.width() + "x" + grid.height() + "/"
                + (legacyFull ? "legacy/" : "") + tile;
    }

    private static ModelQuad withTexture(ModelQuad quad, String texture) {
        return new ModelQuad(
                quad.cullFace(),
                quad.shadeFace(),
                quad.positions(),
                quad.uvs(),
                texture,
                quad.tint());
    }

    private static int[] offsetFor(String direction, AxisPair axes) {
        return switch (direction) {
            case "top" -> axes.up();
            case "top_right" -> add(axes.up(), axes.right());
            case "right" -> axes.right();
            case "bottom_right" -> add(negate(axes.up()), axes.right());
            case "bottom" -> negate(axes.up());
            case "bottom_left" -> add(negate(axes.up()), negate(axes.right()));
            case "left" -> negate(axes.right());
            case "top_left" -> add(axes.up(), negate(axes.right()));
            default -> new int[]{0, 0, 0};
        };
    }

    private static int[] add(int[] a, int[] b) {
        return new int[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static int[] negate(int[] value) {
        return new int[]{-value[0], -value[1], -value[2]};
    }

    private static void copyPosition(float[] source, int from, float[] target, int to) {
        System.arraycopy(source, from * 3, target, to * 3, 3);
    }

    private static void midpointPosition(
            float[] source, int a, int b, float[] target, int to) {
        for (int component = 0; component < 3; component++) {
            target[to * 3 + component] =
                    (source[a * 3 + component] + source[b * 3 + component]) * 0.5f;
        }
    }

    private static void centerPosition(float[] source, float[] target, int to) {
        for (int component = 0; component < 3; component++) {
            target[to * 3 + component] =
                    (source[component]
                            + source[3 + component]
                            + source[6 + component]
                            + source[9 + component]) * 0.25f;
        }
    }

    private static void copyUv(float[] source, int from, float[] target, int to) {
        target[to * 2] = source[from * 2];
        target[to * 2 + 1] = source[from * 2 + 1];
    }

    private static void midpointUv(float[] source, int a, int b, float[] target, int to) {
        target[to * 2] = (source[a * 2] + source[b * 2]) * 0.5f;
        target[to * 2 + 1] = (source[a * 2 + 1] + source[b * 2 + 1]) * 0.5f;
    }

    private static void centerUv(float[] source, float[] target, int to) {
        target[to * 2] = (source[0] + source[2] + source[4] + source[6]) * 0.25f;
        target[to * 2 + 1] = (source[1] + source[3] + source[5] + source[7]) * 0.25f;
    }

    private static String stripNamespace(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon < 0 ? lower : lower.substring(colon + 1);
    }

    private record FusionSpec(
            String layout,
            ConnectedTextureLayout.Grid grid,
            FusionConnectionPredicate predicate) {
    }

    private record AxisPair(int[] up, int[] right) {
    }

    private record TransformKey(ModelQuad quad, String context) {
    }

    private record TextureId(String namespace, String path) {
        static TextureId parse(String value) {
            if (value == null || value.startsWith(VIRTUAL_PREFIX)) return null;
            int colon = value.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : value.substring(0, colon);
            String path = colon < 0 ? value : value.substring(colon + 1);
            if (namespace.isBlank() || path.isBlank()) return null;
            return new TextureId(namespace, path);
        }
    }

    @FunctionalInterface
    private interface ConnectionTest {
        boolean test(BlockState other, BlockState front, String direction);
    }
}
