package dev.duzo.bluemap3d.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads real Minecraft block models and textures out of an {@link AssetIndex}.
 *
 * <p>This reimplements the parts of the client's model pipeline that matter for
 * geometry, because none of it exists on a server: blockstate variant selection, the
 * model parent chain, {@code elements} geometry, per-face uvs, element rotation and
 * variant rotation.
 *
 * <p>The pitfalls below are each the kind that produce a plausible-looking wrong result
 * rather than an error, so they are called out where they are handled:
 * <ul>
 *   <li><b>Automatic uvs.</b> A face with no {@code uv} does not get the whole texture;
 *       Minecraft derives the window from the element's own footprint. Skipping this
 *       stretches one sprite over every face and mangles any multi-cuboid model - a
 *       Create cogwheel becomes a smeared box. See {@link ResourcePackGeometry#autoUv}.</li>
 *   <li><b>Element rotation.</b> {@code {"angle":45,"axis":"y"}} has to be applied to
 *       the corners before anything else, or angled geometry collapses flat.</li>
 *   <li><b>Variant rotation and cull faces.</b> A variant's {@code x}/{@code y} rotates
 *       the whole model, and the cull face of each quad has to rotate with it, or a
 *       rotated stair culls against the wrong neighbour.</li>
 *   <li><b>Block entities.</b> Chests, shulkers, beds and signs have no model geometry
 *       at all. Falling back to their {@code particle} texture draws a chest as plain
 *       oak planks, so instead this returns nothing and lets {@link MapColorSource}
 *       take the block.</li>
 * </ul>
 *
 * <p>Not handled, deliberately: {@code uvlock} and random model weights (the first is
 * always taken, so a server and a client can disagree on which grass variant a block
 * shows). Connected textures are resolved after ordinary model baking using local
 * neighbour context.
 */
public final class ResourcePackSource implements BlockModelSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Models");

    private final AssetIndex assets;
    private final ResourcePackModelResolver models;
    private final ConnectedTextureResolver connectedTextures;
    private final Map<BlockState, List<ModelQuad>> quadCache = new HashMap<>();
    private final Map<String, List<ModelQuad>> attachmentCache = new HashMap<>();
    private final Map<String, BufferedImage> textureCache = new HashMap<>();

    public ResourcePackSource(AssetIndex assets) {
        this.assets = assets;
        this.models = new ResourcePackModelResolver(assets, LOGGER);
        this.connectedTextures = new ConnectedTextureResolver(assets, this::quadsFor);
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return quadCache.computeIfAbsent(state, this::buildQuads);
    }

    /**
     * Builds the ordinary resource-pack model while replacing client tint-index colors
     * with a known RGB value. This is intentionally uncached because the tint may come
     * from per-block block-entity NBT (TrafficCraft paint).
     */
    List<ModelQuad> quadsForWithTint(BlockState state, int tint) {
        return buildQuads(state, tint & 0xFFFFFF);
    }

    @Override
    public List<ModelQuad> quadsFor(BlockRenderContext context) {
        return connectedTextures.resolve(quadsFor(context.state()), context);
    }

    @Override
    public BufferedImage texture(String texture) {
        BufferedImage virtual = connectedTextures.virtualTexture(texture);
        if (virtual != null) {
            return virtual;
        }
        BufferedImage connectedDefault = connectedTextures.defaultTexture(texture);
        if (connectedDefault != null) {
            return connectedDefault;
        }
        return textureCache.computeIfAbsent(texture, id -> {
            ResourceLocation loc = models.parse(id);
            if (loc == null) {
                return null;
            }
            byte[] png = assets.read("assets/" + loc.getNamespace() + "/textures/" + loc.getPath() + ".png");
            if (png == null) {
                return null;
            }
            try {
                return ImageIO.read(new ByteArrayInputStream(png));
            } catch (IOException e) {
                return null;
            }
        });
    }

    @Override
    public boolean occludes(BlockState state) {
        return MapColorSource.isFullOpaqueCube(state);
    }

    // ---------------------------------------------------------------------------------
    // Blockstate -> model
    // ---------------------------------------------------------------------------------

    private List<ModelQuad> buildQuads(BlockState state) {
        return buildQuads(state, null);
    }

    private List<ModelQuad> buildQuads(BlockState state, Integer tintOverride) {
        ResourceLocation block = assetBlockId(state);
        JsonObject blockstate = models.json("assets/" + block.getNamespace() + "/blockstates/" + block.getPath() + ".json");
        if (blockstate == null) {
            return List.of();
        }

        List<ModelQuad> out = new ArrayList<>();
        try {
            if (blockstate.has("variants")) {
                JsonObject variants = blockstate.getAsJsonObject("variants");
                JsonElement chosen = models.selectVariant(variants, state);
                if (chosen != null) {
                    appendVariant(out, firstOf(chosen), state, tintOverride);
                }
            } else if (blockstate.has("multipart")) {
                JsonArray multipart = blockstate.getAsJsonArray("multipart");
                for (JsonElement partEl : multipart) {
                    JsonObject part = partEl.getAsJsonObject();
                    if (!part.has("when") || models.matches(part.getAsJsonObject("when"), state)) {
                        appendVariant(out, firstOf(part.get("apply")), state, tintOverride);
                    }
                }

                // Diagonal Blocks keeps the original fence/wall id and model resources,
                // but adds four boolean block-state properties client-side:
                // north_east, south_east, south_west and north_west. Its client model
                // loader duplicates a cardinal side selector and rotates it 45 degrees.
                // Dedicated-server model parsing never runs that loader, so reproduce
                // exactly that augmentation here for moving BlueMap3D objects.
                appendDiagonalMultipart(out, multipart, state, tintOverride);
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Could not model {}: {}", state, e.toString());
            return List.of();
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    /**
     * The sprite a block showers when it breaks, or {@code null} if it has none.
     *
     * <p>This is the one texture a geometry-less model still carries, which is what makes
     * {@link ShapeSource} possible: a block drawn entirely in code has no {@code elements}
     * to read, but it always declares a {@code particle}, because the client needs
     * something to break into.
     *
     * <p>Resolved through the same variant selection and parent chain as geometry, so a
     * blockstate that sends different variants at different models gets the right one.
     */
    String particleTexture(BlockState state) {
        ResourceLocation block = assetBlockId(state);
        JsonObject blockstate = models.json("assets/" + block.getNamespace() + "/blockstates/" + block.getPath() + ".json");
        if (blockstate == null) {
            return null;
        }
        try {
            JsonObject variant = null;
            if (blockstate.has("variants")) {
                variant = firstOf(models.selectVariant(blockstate.getAsJsonObject("variants"), state));
            } else if (blockstate.has("multipart")) {
                for (JsonElement partEl : blockstate.getAsJsonArray("multipart")) {
                    JsonObject part = partEl.getAsJsonObject();
                    if (!part.has("when") || models.matches(part.getAsJsonObject("when"), state)) {
                        variant = firstOf(part.get("apply"));
                        break;
                    }
                }
            }
            if (variant == null || !variant.has("model")) {
                return null;
            }
            JsonObject textures = models.resolveTexturesOnly(variant.get("model").getAsString(), Map.of());
            return textures == null ? null : models.resolveTextureRef(textures, "#particle");
        } catch (RuntimeException e) {
            LOGGER.debug("Could not find a particle texture for {}: {}", state, e.toString());
            return null;
        }
    }

    /**
     * Diagonal Blocks registers generated blocks under ids such as
     * {@code diagonalfences:natures_spirit/wisteria_fence}. The client deliberately
     * reuses the original block's model; there is no per-generated-block resource under
     * the diagonalfences/diagonalwindows namespace. Resolve the same original asset id
     * before looking up blockstates and particle textures.
     */
    private static ResourceLocation assetBlockId(BlockState state) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock());
        String namespace = id.getNamespace();
        if (!"diagonalfences".equals(namespace)
                && !"diagonalwalls".equals(namespace)
                && !"diagonalwindows".equals(namespace)) {
            return id;
        }

        String path = id.getPath();
        int slash = path.indexOf('/');
        if (slash <= 0 || slash == path.length() - 1) {
            return id;
        }

        ResourceLocation original = ResourceLocation.tryParse(
                path.substring(0, slash) + ":" + path.substring(slash + 1));
        return original == null ? id : original;
    }

    private void appendDiagonalMultipart(
            List<ModelQuad> out,
            JsonArray multipart,
            BlockState state,
            Integer tintOverride) {
        Map<String, String> properties = models.propertiesOf(state);

        for (JsonElement partEl : multipart) {
            JsonObject part = partEl.getAsJsonObject();
            if (!part.has("when") || !part.has("apply")) continue;

            String cardinal = cardinalPositiveCondition(part.getAsJsonObject("when"));
            if (cardinal == null) continue;

            // Matches Diagonal Blocks 21.1.1 EightWayDirection#rotateClockWise:
            // N -> NE -> E -> SE -> S -> SW -> W -> NW.
            String diagonal = switch (cardinal) {
                case "north" -> "north_east";
                case "east" -> "south_east";
                case "south" -> "south_west";
                case "west" -> "north_west";
                default -> null;
            };
            if (diagonal == null || !"true".equals(properties.get(diagonal))) continue;

            // The real client model does not merely rotate the cardinal arm. It first
            // stretches the arm's travel axis by sqrt(2), then rotates the resulting
            // geometry -45 degrees around the block centre. Without the stretch, diagonal
            // fences/windows stop short of the block corner.
            List<ModelQuad> segment = new ArrayList<>();
            appendVariant(segment, firstOf(part.get("apply")), state, tintOverride);
            for (ModelQuad quad : segment) {
                out.add(diagonalize(quad, cardinal));
            }
        }
    }

    private static ModelQuad diagonalize(ModelQuad quad, String cardinal) {
        float[] positions = quad.positions().clone();
        boolean scaleX = "east".equals(cardinal) || "west".equals(cardinal);
        float diagonalScale = (float) Math.sqrt(2.0);
        double radians = Math.toRadians(-45.0);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        for (int i = 0; i < positions.length; i += 3) {
            double x = positions[i] - 8.0;
            double z = positions[i + 2] - 8.0;
            if (scaleX) x *= diagonalScale;
            else z *= diagonalScale;

            double rx = x * cos - z * sin;
            double rz = x * sin + z * cos;
            positions[i] = (float) (rx + 8.0);
            positions[i + 2] = (float) (rz + 8.0);
        }

        // A diagonal face has no single cardinal neighbour that may safely cull it.
        return new ModelQuad(
                null,
                quad.shadeFace(),
                positions,
                quad.uvs().clone(),
                quad.texture(),
                quad.tint());
    }

    private static String cardinalPositiveCondition(JsonObject when) {
        // Fence and wall side selectors are simple property conditions. Avoid matching
        // compound/post selectors; only a direct cardinal true/low condition represents
        // an arm that Diagonal Blocks duplicates.
        for (String direction : new String[]{"north", "east", "south", "west"}) {
            if (!when.has(direction)) continue;
            JsonElement value = when.get(direction);
            if (!value.isJsonPrimitive()) continue;
            String text = value.getAsString();
            if ("true".equals(text) || "low".equals(text) || "low|tall".equals(text)) {
                return direction;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // Model -> quads
    // ---------------------------------------------------------------------------------

    /**
     * Quads for a model named directly, rather than reached through a block state.
     *
     * <p>This is the {@link dev.duzo.bluemap3d.api.ModelAttachment} path. Note the empty
     * result for models with no {@code elements}: vanilla item models such as
     * {@code minecraft:item/diamond_pickaxe} inherit {@code item/handheld} and carry only a
     * {@code layer0} texture, because the client builds their shape by extruding the sprite.
     * Reproducing that server-side is a different job entirely, so those resolve to nothing
     * and the caller is expected to attach something with real geometry instead.
     */
    @Override
    public List<ModelQuad> quadsForModel(ResourceLocation model, Map<String, String> textures) {
        String key = model + "|" + textures;
        List<ModelQuad> cached = attachmentCache.get(key);
        if (cached != null) {
            return cached;
        }
        List<ModelQuad> out = new ArrayList<>();
        try {
            appendModel(out, model.toString(), 0, 0, textures, null);
            if (out.isEmpty()) {
                // No elements anywhere in the chain. That is the normal shape of a vanilla
                // item model - handheld and generated carry a sprite and nothing else,
                // because the client builds the shape by extruding it. Do the same.
                out.addAll(extrudeItem(model, textures));
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Could not model attachment {}: {}", model, e.toString());
        }
        List<ModelQuad> result = out.isEmpty() ? List.of() : List.copyOf(out);
        attachmentCache.put(key, result);
        return result;
    }

    /**
     * Builds geometry for a geometry-less item model by extruding its sprite.
     *
     * <p>The sprite comes from {@code layer0} in the model's texture chain, which is where
     * both {@code item/generated} and {@code item/handheld} put it.
     */
    private List<ModelQuad> extrudeItem(ResourceLocation model, Map<String, String> overrides) {
        JsonObject resolved = models.resolveTexturesOnly(model.toString(), overrides);
        if (resolved == null) {
            return List.of();
        }
        String layer0 = models.resolveTextureRef(resolved, "#layer0");
        if (layer0 == null) {
            return List.of();
        }
        BufferedImage sprite = texture(layer0);
        if (sprite == null) {
            LOGGER.debug("Item model {} names sprite {} which is not available", model, layer0);
            return List.of();
        }
        List<ModelQuad> quads = ItemSpriteMesher.extrude(sprite, layer0);
        LOGGER.debug("Extruded {} into {} quads from {}", model, quads.size(), layer0);
        return quads;
    }

    private void appendVariant(List<ModelQuad> out, JsonObject variant, BlockState state) {
        appendVariant(out, variant, state, 0, null);
    }

    private void appendVariant(
            List<ModelQuad> out, JsonObject variant, BlockState state, Integer tintOverride) {
        appendVariant(out, variant, state, 0, tintOverride);
    }

    private void appendVariant(List<ModelQuad> out, JsonObject variant, BlockState state, int extraY) {
        appendVariant(out, variant, state, extraY, null);
    }

    private void appendVariant(
            List<ModelQuad> out,
            JsonObject variant,
            BlockState state,
            int extraY,
            Integer tintOverride) {
        if (variant == null || !variant.has("model")) {
            return;
        }
        int rotX = variant.has("x") ? variant.get("x").getAsInt() : 0;
        int rotY = (variant.has("y") ? variant.get("y").getAsInt() : 0) + extraY;
        appendModel(
                out, variant.get("model").getAsString(), rotX, rotY,
                Map.of(), state, tintOverride);
    }

    /**
     * Turns one model into quads, applying whole-model rotation and texture overrides.
     *
     * @param overrides texture variables that win over anything in the model's own chain
     * @param state     the block being modelled, for tint resolution, or {@code null}
     */
    private void appendModel(List<ModelQuad> out, String modelRef, int rotX, int rotY,
                             Map<String, String> overrides, BlockState state) {
        appendModel(out, modelRef, rotX, rotY, overrides, state, null);
    }

    private void appendModel(List<ModelQuad> out, String modelRef, int rotX, int rotY,
                             Map<String, String> overrides, BlockState state,
                             Integer tintOverride) {
        JsonObject objStub = models.findObjStub(modelRef);
        if (objStub != null) {
            List<ModelQuad> obj = models.objQuads(modelRef, objStub, overrides);
            if (rotX == 0 && rotY == 0) {
                out.addAll(obj);
            } else {
                boolean cardinalRotation = Math.floorMod(rotY, 90) == 0;
                for (ModelQuad quad : obj) {
                    float[] positions = quad.positions().clone();
                    ResourcePackGeometry.applyVariantRotation(positions, rotX, rotY);
                    Direction cull = cardinalRotation
                            ? ResourcePackGeometry.rotateDirection(quad.cullFace(), rotX, rotY)
                            : null;
                    Direction shade = cardinalRotation
                            ? ResourcePackGeometry.rotateDirection(quad.shadeFace(), rotX, rotY)
                            : quad.shadeFace();
                    out.add(new ModelQuad(
                            cull, shade, positions, quad.uvs().clone(),
                            quad.texture(), quad.tint()));
                }
            }
            return;
        }
        JsonObject model = models.resolveModel(modelRef, overrides);
        if (model == null) {
            return;
        }
        JsonArray elements = model.has("elements") ? model.getAsJsonArray("elements") : null;
        if (elements == null || elements.isEmpty()) {
            // No geometry: a block-entity block, or a pure parent stub. Returning
            // nothing hands the block to MapColorSource, which is much better than
            // drawing a chest as a cube of oak planks.
            return;
        }
        JsonObject textures = model.has("textures") ? model.getAsJsonObject("textures") : new JsonObject();

        for (JsonElement elementEl : elements) {
            JsonObject element = elementEl.getAsJsonObject();
            if (!element.has("from") || !element.has("to") || !element.has("faces")) {
                continue;
            }
            float[] from = vec3(element.getAsJsonArray("from"));
            float[] to = vec3(element.getAsJsonArray("to"));
            JsonObject elementRotation = element.has("rotation") ? element.getAsJsonObject("rotation") : null;

            JsonObject faces = element.getAsJsonObject("faces");
            for (Map.Entry<String, JsonElement> faceEntry : faces.entrySet()) {
                Direction face = directionOf(faceEntry.getKey());
                if (face == null) {
                    continue;
                }
                JsonObject faceDef = faceEntry.getValue().getAsJsonObject();

                String texture = models.resolveTextureRef(textures,
                        faceDef.has("texture") ? faceDef.get("texture").getAsString() : null);
                if (texture == null) {
                    continue;
                }

                // Automatic uv derivation. Without this every face samples the whole
                // sheet and multi-cuboid models come out smeared.
                float[] uv = faceDef.has("uv")
                        ? vec4(faceDef.getAsJsonArray("uv"))
                        : ResourcePackGeometry.autoUv(from, to, face);
                int uvRotation = faceDef.has("rotation") ? faceDef.get("rotation").getAsInt() : 0;

                Direction cull = faceDef.has("cullface")
                        ? directionOf(faceDef.get("cullface").getAsString())
                        : null;

                float[] corners = ResourcePackGeometry.faceCorners(from, to, face);
                if (elementRotation != null) {
                    ResourcePackGeometry.applyElementRotation(corners, elementRotation);
                }
                // The variant's own x/y rotation, about the block centre.
                if (rotX != 0 || rotY != 0) {
                    ResourcePackGeometry.applyVariantRotation(corners, rotX, rotY);

                    // Cull faces are cardinal. A 45-degree diagonal arm has no single
                    // neighbouring block that can safely cull it, so keep every face.
                    if (Math.floorMod(rotY, 90) == 0) {
                        cull = ResourcePackGeometry.rotateDirection(cull, rotX, rotY);
                        face = ResourcePackGeometry.rotateDirection(face, rotX, rotY);
                    } else {
                        cull = null;
                    }
                }

                // A tintindex means the client multiplies by a biome or state colour we
                // cannot compute without the level. The block's map colour is the right
                // hue for the cases that matter visually - grass, leaves, water - so it
                // stands in. Untinted faces are left alone.
                int tint = 0xFFFFFF;
                // state is null for attachments, which name a model directly and so have no
                // block to take a colour from. A compatibility source may provide the exact
                // client-side tint (for example TrafficCraft paint from block-entity NBT).
                if (state != null && faceDef.has("tintindex")
                        && faceDef.get("tintindex").getAsInt() >= 0) {
                    if (tintOverride != null) {
                        tint = tintOverride;
                    } else {
                        int approximate = MapColorSource.mapColorOf(state);
                        if (approximate >= 0) {
                            tint = approximate;
                        }
                    }
                }

                out.add(new ModelQuad(cull, face, corners, ResourcePackGeometry.uvCorners(uv, uvRotation), texture, tint));
            }
        }
    }

    // ---------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------

    private static JsonObject firstOf(JsonElement element) {
        if (element == null) {
            return null;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            // Weighted variants: always take the first, so the same block always looks
            // the same. A client would pick randomly per position; matching that would
            // need the client's position hash and is not worth it.
            return array.isEmpty() ? null : array.get(0).getAsJsonObject();
        }
        return element.getAsJsonObject();
    }

    private static Direction directionOf(String name) {
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "down" -> Direction.DOWN;
            case "up" -> Direction.UP;
            case "north" -> Direction.NORTH;
            case "south" -> Direction.SOUTH;
            case "west" -> Direction.WEST;
            case "east" -> Direction.EAST;
            default -> null;
        };
    }

    private static float[] vec3(JsonArray array) {
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat(), array.get(2).getAsFloat()};
    }

    private static float[] vec4(JsonArray array) {
        return new float[]{
                array.get(0).getAsFloat(), array.get(1).getAsFloat(),
                array.get(2).getAsFloat(), array.get(3).getAsFloat()};
    }
}