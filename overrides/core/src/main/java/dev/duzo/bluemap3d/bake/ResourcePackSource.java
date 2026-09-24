package dev.duzo.bluemap3d.bake;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

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
 *       Create cogwheel becomes a smeared box. See {@link #autoUv}.</li>
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
 * <p>Not handled, deliberately: {@code uvlock}, random model weights (the first is
 * always taken, so a server and a client can disagree on which grass variant a block
 * shows), and connected-texture mods.
 */
public final class ResourcePackSource implements BlockModelSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Models");
    private static final Gson GSON = new Gson();
    private static final int MAX_PARENT_DEPTH = 16;

    private final AssetIndex assets;
    private final Map<BlockState, List<ModelQuad>> quadCache = new HashMap<>();
    private final Map<String, List<ModelQuad>> attachmentCache = new HashMap<>();
    private final Map<String, BufferedImage> textureCache = new HashMap<>();
    private final Map<String, JsonObject> jsonCache = new HashMap<>();

    public ResourcePackSource(AssetIndex assets) {
        this.assets = assets;
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
    public BufferedImage texture(String texture) {
        return textureCache.computeIfAbsent(texture, id -> {
            ResourceLocation loc = parse(id);
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
        JsonObject blockstate = json("assets/" + block.getNamespace() + "/blockstates/" + block.getPath() + ".json");
        if (blockstate == null) {
            return List.of();
        }

        List<ModelQuad> out = new ArrayList<>();
        try {
            if (blockstate.has("variants")) {
                JsonObject variants = blockstate.getAsJsonObject("variants");
                JsonElement chosen = selectVariant(variants, state);
                if (chosen != null) {
                    appendVariant(out, firstOf(chosen), state, tintOverride);
                }
            } else if (blockstate.has("multipart")) {
                JsonArray multipart = blockstate.getAsJsonArray("multipart");
                for (JsonElement partEl : multipart) {
                    JsonObject part = partEl.getAsJsonObject();
                    if (!part.has("when") || matches(part.getAsJsonObject("when"), state)) {
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
        JsonObject blockstate = json("assets/" + block.getNamespace() + "/blockstates/" + block.getPath() + ".json");
        if (blockstate == null) {
            return null;
        }
        try {
            JsonObject variant = null;
            if (blockstate.has("variants")) {
                variant = firstOf(selectVariant(blockstate.getAsJsonObject("variants"), state));
            } else if (blockstate.has("multipart")) {
                for (JsonElement partEl : blockstate.getAsJsonArray("multipart")) {
                    JsonObject part = partEl.getAsJsonObject();
                    if (!part.has("when") || matches(part.getAsJsonObject("when"), state)) {
                        variant = firstOf(part.get("apply"));
                        break;
                    }
                }
            }
            if (variant == null || !variant.has("model")) {
                return null;
            }
            JsonObject textures = resolveTexturesOnly(variant.get("model").getAsString(), Map.of());
            return textures == null ? null : resolveTextureRef(textures, "#particle");
        } catch (RuntimeException e) {
            LOGGER.debug("Could not find a particle texture for {}: {}", state, e.toString());
            return null;
        }
    }

    /**
     * Picks the variant whose key matches the state's properties.
     *
     * <p>Keys are comma-separated {@code name=value} pairs; a blockstate file only lists
     * the properties that actually change the model, so a key matches when every pair it
     * names agrees with the state. {@code ""} is the catch-all single-variant key.
     */
    private static JsonElement selectVariant(JsonObject variants, BlockState state) {
        Map<String, String> props = propertiesOf(state);

        JsonElement fallback = null;
        for (Map.Entry<String, JsonElement> entry : variants.entrySet()) {
            String key = entry.getKey();
            if (key.isEmpty()) {
                fallback = entry.getValue();
                continue;
            }
            boolean ok = true;
            for (String pair : key.split(",")) {
                int eq = pair.indexOf('=');
                if (eq < 0) {
                    continue;
                }
                String name = pair.substring(0, eq).trim();
                String want = pair.substring(eq + 1).trim();
                if (!propertyMatches(want, props.get(name))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return entry.getValue();
            }
        }
        return fallback;
    }

    /** Evaluates a multipart {@code when} clause, including {@code OR} and {@code AND}. */
    private static boolean matches(JsonObject when, BlockState state) {
        if (when.has("OR")) {
            for (JsonElement alt : when.getAsJsonArray("OR")) {
                if (matches(alt.getAsJsonObject(), state)) {
                    return true;
                }
            }
            return false;
        }
        if (when.has("AND")) {
            for (JsonElement all : when.getAsJsonArray("AND")) {
                if (!matches(all.getAsJsonObject(), state)) {
                    return false;
                }
            }
            return true;
        }

        Map<String, String> props = propertiesOf(state);
        for (Map.Entry<String, JsonElement> entry : when.entrySet()) {
            String actual = props.get(entry.getKey());
            // A condition value may list alternatives: "north|east".
            String[] allowed = entry.getValue().getAsString().split("\\|");
            boolean any = false;
            for (String option : allowed) {
                if (propertyMatches(option, actual)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    /**
     * Diagonal Walls replaces vanilla WallSide values with booleans to keep the state
     * count manageable. The resource-pack models still test for low/none, exactly as
     * Diagonal Blocks' own client-side WallMultiPartTranslator converts them.
     */
    private static boolean propertyMatches(String expected, String actual) {
        if (actual == null) return false;
        if (expected.equals(actual)) return true;
        if ("true".equals(actual) && "low".equals(expected)) return true;
        if ("false".equals(actual) && "none".equals(expected)) return true;
        return false;
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
        Map<String, String> properties = propertiesOf(state);

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

    /** The state's properties as the strings a blockstate file would use. */
    private static Map<String, String> propertiesOf(BlockState state) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            out.put(entry.getKey().getName(), nameOf(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String nameOf(Property<?> property, Comparable<?> value) {
        return ((Property<T>) property).getName((T) value);
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
        JsonObject resolved = resolveTexturesOnly(model.toString(), overrides);
        if (resolved == null) {
            return List.of();
        }
        String layer0 = resolveTextureRef(resolved, "#layer0");
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

    /**
     * The merged texture map of a model chain, for models that have no elements at all.
     *
     * <p>{@link #resolveModel} gives up and returns null when it finds no geometry, which is
     * exactly the case an item model hits.
     */
    private JsonObject resolveTexturesOnly(String modelRef, Map<String, String> overrides) {
        JsonObject textures = new JsonObject();
        overrides.forEach(textures::addProperty);

        String ref = modelRef;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && ref != null; depth++) {
            ResourceLocation loc = parse(ref);
            if (loc == null) {
                return textures;
            }
            String path = loc.getPath();
            if (!path.contains("/")) {
                path = "item/" + path;
            }
            JsonObject model = json("assets/" + loc.getNamespace() + "/models/" + path + ".json");
            if (model == null) {
                return textures;
            }
            if (model.has("textures")) {
                for (Map.Entry<String, JsonElement> e : model.getAsJsonObject("textures").entrySet()) {
                    if (!textures.has(e.getKey())) {
                        textures.add(e.getKey(), e.getValue());
                    }
                }
            }
            ref = model.has("parent") ? model.get("parent").getAsString() : null;
        }
        return textures;
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
        JsonObject objStub = findObjStub(modelRef);
        if (objStub != null) {
            List<ModelQuad> obj = new ArrayList<>();
            appendObjModel(obj, modelRef, objStub, overrides);
            if (rotX == 0 && rotY == 0) {
                out.addAll(obj);
            } else {
                boolean cardinalRotation = Math.floorMod(rotY, 90) == 0;
                for (ModelQuad quad : obj) {
                    float[] positions = quad.positions().clone();
                    applyVariantRotation(positions, rotX, rotY);
                    Direction cull = cardinalRotation
                            ? rotateDirection(quad.cullFace(), rotX, rotY)
                            : null;
                    Direction shade = cardinalRotation
                            ? rotateDirection(quad.shadeFace(), rotX, rotY)
                            : quad.shadeFace();
                    out.add(new ModelQuad(
                            cull, shade, positions, quad.uvs().clone(),
                            quad.texture(), quad.tint()));
                }
            }
            return;
        }
        JsonObject model = resolveModel(modelRef, overrides);
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

                String texture = resolveTextureRef(textures,
                        faceDef.has("texture") ? faceDef.get("texture").getAsString() : null);
                if (texture == null) {
                    continue;
                }

                // Automatic uv derivation. Without this every face samples the whole
                // sheet and multi-cuboid models come out smeared.
                float[] uv = faceDef.has("uv")
                        ? vec4(faceDef.getAsJsonArray("uv"))
                        : autoUv(from, to, face);
                int uvRotation = faceDef.has("rotation") ? faceDef.get("rotation").getAsInt() : 0;

                Direction cull = faceDef.has("cullface")
                        ? directionOf(faceDef.get("cullface").getAsString())
                        : null;

                float[] corners = faceCorners(from, to, face);
                if (elementRotation != null) {
                    applyElementRotation(corners, elementRotation);
                }
                // The variant's own x/y rotation, about the block centre.
                if (rotX != 0 || rotY != 0) {
                    applyVariantRotation(corners, rotX, rotY);

                    // Cull faces are cardinal. A 45-degree diagonal arm has no single
                    // neighbouring block that can safely cull it, so keep every face.
                    if (Math.floorMod(rotY, 90) == 0) {
                        cull = rotateDirection(cull, rotX, rotY);
                        face = rotateDirection(face, rotX, rotY);
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

                out.add(new ModelQuad(cull, face, corners, uvCorners(uv, uvRotation), texture, tint));
            }
        }
    }

    /**
     * The resource-pack path of a model's json file. A bare name with no namespaced
     * folder defaults to {@code block/}, since that is what every plain block-model
     * reference in a blockstate variant means; a ref that already names {@code block/}
     * or {@code item/} is left alone.
     */
    private static String modelJsonPath(ResourceLocation loc) {
        String path = loc.getPath();
        if (!path.startsWith("block/") && !path.startsWith("item/") && !path.contains("/")) {
            path = "block/" + path;
        }
        return "assets/" + loc.getNamespace() + "/models/" + path + ".json";
    }

    private static boolean isObjLoader(JsonObject model) {
        if (!model.has("loader")) {
            return false;
        }
        String loader = model.get("loader").getAsString();
        return "neoforge:obj".equals(loader)
                || "forge:obj".equals(loader)
                || "porting_lib:obj".equals(loader);
    }

    /**
     * The nearest model in {@code modelRef}'s parent chain that wraps an OBJ mesh, or
     * {@code null} if the chain reaches ordinary {@code elements} geometry first (or has
     * neither).
     *
     * <p>The chain has to be walked, not just the leaf inspected. A mod that reskins
     * another mod's OBJ track, machine or decoration ships a leaf model that is nothing
     * but {@code parent} plus a {@code textures} block - Steam 'n' Rails' 150-odd track
     * materials are all exactly that on top of Create's {@code create:block/track/diag},
     * {@code tie} and {@code segment_*}. Checking only the leaf finds no {@code loader}
     * there, falls through to the elements path, finds no {@code elements} anywhere in
     * the chain either, and hands the block to {@link MapColorSource} - so every one of
     * those blocks and attachments draws as a flat grey lump instead of track.
     *
     * <p>Stops at the first {@code elements} it meets so that whichever kind of geometry
     * is <em>nearer</em> the leaf wins, which is the same precedence a child overriding
     * its parent's geometry has everywhere else. Textures are deliberately not read here:
     * {@link #appendObjModel} resolves those from the original leaf, so the child's
     * overrides still beat the parent's defaults.
     */
    private JsonObject findObjStub(String modelRef) {
        String ref = modelRef;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && ref != null; depth++) {
            ResourceLocation loc = parse(ref);
            if (loc == null) {
                return null;
            }
            JsonObject model = json(modelJsonPath(loc));
            if (model == null) {
                return null;
            }
            if (isObjLoader(model) && model.has("model")) {
                return model;
            }
            if (model.has("elements")) {
                return null;
            }
            ref = model.has("parent") ? model.get("parent").getAsString() : null;
        }
        return null;
    }

    /**
     * A model wrapping an OBJ mesh instead of {@code elements} - some mods ship those for
     * parts that do not decompose into cuboids. Reads the mesh and its material file
     * through the same {@link AssetIndex} as everything else, resolves the wrapper's
     * texture map exactly like an element model would, and hands both to
     * {@link ObjModelReader}, which knows nothing about resource packs or blocks.
     */
    private void appendObjModel(List<ModelQuad> out, String modelRef, JsonObject stub, Map<String, String> overrides) {
        if (!stub.has("model")) {
            return;
        }
        ResourceLocation objLoc = parse(stub.get("model").getAsString());
        if (objLoc == null) {
            return;
        }
        String objPath = "assets/" + objLoc.getNamespace() + "/" + objLoc.getPath();
        byte[] objBytes = assets.read(objPath);
        if (objBytes == null) {
            LOGGER.debug("Obj model {} names mesh {} which is not available", modelRef, objPath);
            return;
        }
        String objText = new String(objBytes, StandardCharsets.UTF_8);

        String mtlText = null;

        // NeoForge supports mtl_override on the wrapper JSON. Prefer it when present,
        // then fall back to the OBJ's local mtllib declaration.
        if (stub.has("mtl_override")) {
            ResourceLocation mtlLoc = parse(stub.get("mtl_override").getAsString());
            if (mtlLoc != null) {
                String mtlPath = "assets/" + mtlLoc.getNamespace() + "/" + mtlLoc.getPath();
                byte[] mtlBytes = assets.read(mtlPath);
                if (mtlBytes != null) {
                    mtlText = new String(mtlBytes, StandardCharsets.UTF_8);
                } else {
                    LOGGER.debug("Obj model {} overrides material with {} which is not available",
                            modelRef, mtlPath);
                }
            }
        }

        if (mtlText == null) {
            String mtlName = findMtllib(objText);
            if (mtlName != null) {
                int slash = objPath.lastIndexOf('/');
                String mtlPath = (slash >= 0 ? objPath.substring(0, slash + 1) : "") + mtlName;
                byte[] mtlBytes = assets.read(mtlPath);
                if (mtlBytes != null) {
                    mtlText = new String(mtlBytes, StandardCharsets.UTF_8);
                } else {
                    LOGGER.debug("Obj model {} names material {} which is not available",
                            modelRef, mtlPath);
                }
            }
        }

        // The stub itself carries no elements, but resolveTexturesOnly already walks a
        // chain like this for item models - it is exactly what an obj wrapper's "parent"
        // is for, and its materials only ever point at a #ref into this same map.
        JsonObject textures = resolveTexturesOnly(modelRef, overrides);
        Map<String, String> resolvedTextures = new HashMap<>();
        for (String key : textures.keySet()) {
            String resolved = resolveTextureRef(textures, "#" + key);
            if (resolved != null) {
                resolvedTextures.put(key, resolved);
            }
        }

        boolean flipV = stub.has("flip_v") && stub.get("flip_v").getAsBoolean();
        out.addAll(ObjModelReader.read(objText, mtlText, resolvedTextures, flipV));
    }

    private static String findMtllib(String objText) {
        for (String rawLine : objText.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("mtllib ")) {
                return line.substring("mtllib ".length()).trim();
            }
        }
        return null;
    }

    /**
     * The model's merged form: {@code textures} accumulated down the parent chain with
     * the child winning, and the first {@code elements} found.
     */
    private JsonObject resolveModel(String modelRef, Map<String, String> overrides) {
        JsonObject merged = new JsonObject();
        JsonObject mergedTextures = new JsonObject();
        JsonArray elements = null;

        // Seeded first, and the merge below only fills gaps, so these beat the whole chain.
        overrides.forEach(mergedTextures::addProperty);

        String ref = modelRef;
        for (int depth = 0; depth < MAX_PARENT_DEPTH && ref != null; depth++) {
            ResourceLocation loc = parse(ref);
            if (loc == null) {
                break;
            }
            JsonObject model = json(modelJsonPath(loc));
            if (model == null) {
                break;
            }

            if (model.has("textures")) {
                for (Map.Entry<String, JsonElement> e : model.getAsJsonObject("textures").entrySet()) {
                    // Child wins: only fill gaps as we walk up.
                    if (!mergedTextures.has(e.getKey())) {
                        mergedTextures.add(e.getKey(), e.getValue());
                    }
                }
            }
            if (elements == null && model.has("elements")) {
                elements = model.getAsJsonArray("elements");
            }
            ref = model.has("parent") ? model.get("parent").getAsString() : null;
        }

        if (elements == null) {
            return null;
        }
        merged.add("textures", mergedTextures);
        merged.add("elements", elements);
        return merged;
    }

    /** Follows {@code #ref} indirection in a model's texture map. */
    private static String resolveTextureRef(JsonObject textures, String ref) {
        String current = ref;
        for (int depth = 0; depth < 8; depth++) {
            if (current == null) {
                return null;
            }
            if (!current.startsWith("#")) {
                return current;
            }
            JsonElement next = textures.get(current.substring(1));
            if (next == null) {
                return null;
            }
            current = next.getAsString();
        }
        return null;
    }

    // ---------------------------------------------------------------------------------
    // Geometry helpers. Ported from the reference renderer in the pack's quest tools,
    // which had these semantics already debugged against real Create models.
    // ---------------------------------------------------------------------------------

    /**
     * Minecraft's default uv when a face declares none: the texture is sampled from the
     * element's own footprint on that axis pair, with v measured from the top.
     */
    static float[] autoUv(float[] f, float[] t, Direction face) {
        float x0 = f[0], y0 = f[1], z0 = f[2];
        float x1 = t[0], y1 = t[1], z1 = t[2];
        return switch (face) {
            case DOWN -> new float[]{x0, z0, x1, z1};
            case UP -> new float[]{x0, 16 - z1, x1, 16 - z0};
            case NORTH -> new float[]{16 - x1, 16 - y1, 16 - x0, 16 - y0};
            case SOUTH -> new float[]{x0, 16 - y1, x1, 16 - y0};
            case WEST -> new float[]{z0, 16 - y1, z1, 16 - y0};
            case EAST -> new float[]{16 - z1, 16 - y1, 16 - z0, 16 - y0};
        };
    }

    /**
     * Four corners of a face of the box {@code from..to}, in the order
     * {@code (u0,v0) (u1,v0) (u1,v1) (u0,v1)} so they line up with the uv window.
     */
    static float[] faceCorners(float[] f, float[] t, Direction face) {
        float x0 = f[0], y0 = f[1], z0 = f[2];
        float x1 = t[0], y1 = t[1], z1 = t[2];
        return switch (face) {
            case UP -> new float[]{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1};
            case DOWN -> new float[]{x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0};
            case NORTH -> new float[]{x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0};
            case SOUTH -> new float[]{x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1};
            case WEST -> new float[]{x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0};
            case EAST -> new float[]{x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1};
        };
    }

    /** Expands a {@code [u0,v0,u1,v1]} window to four corners, with face rotation. */
    static float[] uvCorners(float[] uv, int rotation) {
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};

        int steps = ((rotation % 360) + 360) % 360 / 90;
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            float[] c = corners[(i + steps) % 4];
            out[i * 2] = c[0];
            out[i * 2 + 1] = c[1];
        }
        return out;
    }

    /** An element's own rotation: {@code {origin, axis, angle}}. */
    private static void applyElementRotation(float[] corners, JsonObject rotation) {
        float angle = rotation.has("angle") ? rotation.get("angle").getAsFloat() : 0f;
        if (Math.abs(angle) < 1e-6f) {
            return;
        }
        float[] origin = rotation.has("origin")
                ? vec3(rotation.getAsJsonArray("origin"))
                : new float[]{8, 8, 8};
        String axis = rotation.has("axis") ? rotation.get("axis").getAsString() : "y";

        double rad = Math.toRadians(angle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);

        for (int i = 0; i < 4; i++) {
            float x = corners[i * 3] - origin[0];
            float y = corners[i * 3 + 1] - origin[1];
            float z = corners[i * 3 + 2] - origin[2];
            float nx = x, ny = y, nz = z;
            switch (axis) {
                case "x" -> {
                    ny = y * cos - z * sin;
                    nz = y * sin + z * cos;
                }
                case "z" -> {
                    nx = x * cos - y * sin;
                    ny = x * sin + y * cos;
                }
                default -> {
                    nx = x * cos + z * sin;
                    nz = -x * sin + z * cos;
                }
            }
            corners[i * 3] = nx + origin[0];
            corners[i * 3 + 1] = ny + origin[1];
            corners[i * 3 + 2] = nz + origin[2];
        }
    }

    /**
     * A variant's whole-model rotation, in 90 degree steps about the block centre.
     *
     * <p>Both steps turn the <em>negative</em> way, which is what a blockstate's {@code x}
     * and {@code y} mean: vanilla builds them as {@code rotationXYZ(-x, -y, 0)}. Written
     * the positive way round every rotated model comes out mirrored through the axis - a
     * {@code facing=south} stair points north - and, worse, disagrees with
     * {@link #rotateDirection}, which is correct. A face then carries a cull direction
     * belonging to the face on the opposite side, so it is tested against the wrong
     * neighbour: a barrel on the ground loses its lid, because the quad that ended up on
     * top is asking whether the block <em>below</em> hides it.
     *
     * <p>Nothing caught this for a long time because nothing exercised it. A turtle has
     * its facing baked out of the model and streamed as a quaternion instead, and a ship
     * made of planks looks the same whichever way its blocks are turned.
     */
    private static void applyVariantRotation(float[] corners, int rotX, int rotY) {
        int stepsX = normaliseSteps(rotX);
        double radiansY = Math.toRadians(rotY);
        double cosY = Math.cos(radiansY);
        double sinY = Math.sin(radiansY);

        for (int i = 0; i < 4; i++) {
            float x = corners[i * 3] - 8f;
            float y = corners[i * 3 + 1] - 8f;
            float z = corners[i * 3 + 2] - 8f;

            for (int step = 0; step < stepsX; step++) {
                // up -> north, matching rotateAroundX.
                float ny = z, nz = -y;
                y = ny;
                z = nz;
            }

            double nx = x * cosY - z * sinY;
            double nz = x * sinY + z * cosY;
            corners[i * 3] = (float) nx + 8f;
            corners[i * 3 + 1] = y + 8f;
            corners[i * 3 + 2] = (float) nz + 8f;
        }
    }

    /**
     * Rotates a cull face with its model. Missing this makes a rotated stair or door
     * cull against the wrong neighbour, which shows up as a hole only from one side.
     */
    private static Direction rotateDirection(Direction direction, int rotX, int rotY) {
        if (direction == null) {
            return null;
        }
        Direction out = direction;
        for (int s = 0; s < normaliseSteps(rotX); s++) {
            out = rotateAroundX(out);
        }
        for (int s = 0; s < normaliseSteps(rotY); s++) {
            out = out.getClockWise(Direction.Axis.Y);
        }
        return out;
    }

    private static Direction rotateAroundX(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.DOWN;
            case DOWN -> Direction.SOUTH;
            case SOUTH -> Direction.UP;
            case UP -> Direction.NORTH;
            default -> direction;
        };
    }

    private static int normaliseSteps(int degrees) {
        return ((degrees % 360) + 360) % 360 / 90;
    }

    // ---------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------

    private JsonObject json(String path) {
        return jsonCache.computeIfAbsent(path, p -> {
            byte[] bytes = assets.read(p);
            if (bytes == null) {
                return null;
            }
            try {
                return GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            } catch (RuntimeException e) {
                LOGGER.debug("Malformed json at {}: {}", p, e.toString());
                return null;
            }
        });
    }

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

    private static ResourceLocation parse(String id) {
        String value = id;
        if (value.startsWith("#")) {
            return null;
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        return ResourceLocation.tryParse(value.contains(":") ? value : "minecraft:" + value);
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