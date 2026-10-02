package dev.duzo.bluemap3d.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-safe entity model source.
 *
 * <p>Resolution order for an entity attachment is:
 * <ol>
 *   <li>ResourcePackSource (registered before this source) for ordinary mod JSON models;</li>
 *   <li>exact vanilla ModelPart geometry generated at build time from Minecraft 1.21.1;</li>
 *   <li>Bedrock/GeckoLib/AzureLib-style geo JSON discovered in the owning mod's assets;</li>
 *   <li>a correctly sized textured box, so an unknown Java-only client renderer is visible
 *       rather than silently disappearing.</li>
 * </ol>
 */
public final class EntityModelSource implements BlockModelSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Entities");
    private static final String GENERATED = "assets/bluemap3d_entities/entity-models.json";
    private static final String FALLBACK_TEXTURE = "minecraft:block/light_gray_wool";

    private static final List<String> EXTRA_LAYERS = List.of(
            "fur", "wool", "undercoat", "outer", "outer_layer", "collar", "pattern", "eyes");

    private final AssetIndex assets;
    private final Map<String, RawMesh> vanilla = new HashMap<>();
    private final Map<String, List<ModelQuad>> modelCache = new HashMap<>();
    private final Map<String, BufferedImage> textureCache = new HashMap<>();

    public EntityModelSource(AssetIndex assets) {
        this.assets = assets;
        loadVanillaModels();
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return List.of();
    }

    @Override
    public List<ModelQuad> quadsForModel(ResourceLocation model, Map<String, String> metadata) {
        String cacheKey = model + "|" + metadata;
        return modelCache.computeIfAbsent(cacheKey, ignored -> resolve(model, metadata));
    }

    @Override
    public BufferedImage texture(String texture) {
        return textureCache.computeIfAbsent(texture, this::loadTexture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return false;
    }

    private List<ModelQuad> resolve(ResourceLocation model, Map<String, String> metadata) {
        EntityKey entity = entityKey(model);
        if (entity == null) return List.of();

        if ("minecraft".equals(entity.namespace())) {
            List<ModelQuad> exact = vanilla(entity, metadata);
            if (!exact.isEmpty()) return exact;
        }

        List<ModelQuad> geo = geo(entity, metadata);
        if (!geo.isEmpty()) return geo;

        return fallback(entity, metadata);
    }

    private List<ModelQuad> vanilla(EntityKey entity, Map<String, String> metadata) {
        List<ModelQuad> out = new ArrayList<>();
        appendVanillaLayer(out, entity, "main", true, metadata);
        for (String layer : EXTRA_LAYERS) {
            appendVanillaLayer(out, entity, layer, false, metadata);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private void appendVanillaLayer(
            List<ModelQuad> out,
            EntityKey entity,
            String layer,
            boolean required,
            Map<String, String> metadata) {
        if ("true".equals(metadata.get("__bm3d_hide_wool"))
                && ("wool".equals(layer)
                    || "fur".equals(layer)
                    || "undercoat".equals(layer))) {
            return;
        }

        RawMesh raw = findVanillaLayer(entity, layer, metadata);
        if (raw == null) return;

        String texture = findTexture(entity.namespace(), entity.path(), layer, metadata);
        if (texture == null) {
            if (!required) return;
            texture = FALLBACK_TEXTURE;
        }

        appendRaw(out, raw, texture, layerTint(metadata, layer));
    }

    private RawMesh findVanillaLayer(
            EntityKey entity, String layer, Map<String, String> metadata) {
        String exact = entity.namespace() + ":" + entity.path() + "#" + layer;
        RawMesh direct = vanilla.get(exact);
        if (direct != null && !hasVisualMetadata(metadata)) {
            return direct;
        }

        RawMesh best = direct;
        int bestScore = direct == null
                ? Integer.MIN_VALUE
                : EntityAssetMatch.score(entity.path(), exact, layer) + EntityAssetMatch.appearanceScore(metadata, exact);

        String suffix = "#" + layer;
        for (Map.Entry<String, RawMesh> entry : vanilla.entrySet()) {
            String key = entry.getKey();
            if (!key.endsWith(suffix)) continue;

            int score = EntityAssetMatch.score(entity.path(), key, layer)
                    + EntityAssetMatch.appearanceScore(metadata, key);
            if (score > bestScore) {
                best = entry.getValue();
                bestScore = score;
            }
        }
        return bestScore > 0 ? best : direct;
    }

    private void appendRaw(
            List<ModelQuad> out, RawMesh raw, String texture, int tint) {
        int vertices = raw.positions().length / 3;
        if (vertices == 0 || vertices % 4 != 0 || raw.uvs().length != vertices * 2) return;

        for (int vertex = 0; vertex < vertices; vertex += 4) {
            float[] positions = new float[12];
            float[] uvs = new float[8];
            System.arraycopy(raw.positions(), vertex * 3, positions, 0, 12);
            System.arraycopy(raw.uvs(), vertex * 2, uvs, 0, 8);
            out.add(new ModelQuad(null, null, positions, uvs, texture, tint));
        }
    }

    private static boolean hasVisualMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) return false;
        for (String key : metadata.keySet()) {
            if (key.startsWith("__bm3d_visual_")) return true;
        }
        return false;
    }

    private static int layerTint(Map<String, String> metadata, String layer) {
        if (!("wool".equals(layer)
                || "fur".equals(layer)
                || "undercoat".equals(layer))) {
            return 0xFFFFFF;
        }

        String encoded = metadata.get("__bm3d_tint");
        if (encoded == null) return 0xFFFFFF;
        try {
            return Integer.parseInt(encoded, 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return 0xFFFFFF;
        }
    }

    private void loadVanillaModels() {
        byte[] bytes = assets.read(GENERATED);
        if (bytes == null) {
            LOGGER.warn("Generated vanilla entity models are missing; mobs will use asset/fallback models");
            return;
        }

        try {
            JsonObject root = JsonParser.parseString(
                    new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject models = root.getAsJsonObject("models");
            if (models == null) return;

            for (Map.Entry<String, JsonElement> entry : models.entrySet()) {
                JsonObject value = entry.getValue().getAsJsonObject();
                float[] positions = floats(value.getAsJsonArray("positions"));
                float[] uvs = floats(value.getAsJsonArray("uvs"));
                if (positions.length % 12 == 0 && uvs.length * 3 == positions.length * 2) {
                    vanilla.put(entry.getKey(), new RawMesh(positions, uvs));
                }
            }
            LOGGER.info("Loaded {} generated vanilla entity model layer(s)", vanilla.size());
        } catch (RuntimeException e) {
            LOGGER.warn("Could not parse generated vanilla entity models: {}", e.toString());
        }
    }

    /**
     * Tries common data-driven entity geometry locations used by GeckoLib, AzureLib and
     * Bedrock-style model exporters. No mod id or entity id is registered in code.
     */
    private List<ModelQuad> geo(EntityKey entity, Map<String, String> metadata) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        // Fast conventional names plus a namespace scan for exporters that use names
        // such as <entity>_baby.geo.json or <entity>_model.geo.json.
        for (String relative : geoCandidates(entity.path())) {
            candidates.add("assets/" + entity.namespace() + "/" + relative);
        }

        String namespaceRoot = "assets/" + entity.namespace() + "/";
        for (String prefix : List.of(
                namespaceRoot + "geo",
                namespaceRoot + "geckolib/models",
                namespaceRoot + "models")) {
            for (String candidate : assets.findPaths(
                    prefix,
                    pathCandidate -> pathCandidate.toLowerCase(Locale.ROOT).endsWith(".geo.json"),
                    512)) {
                if (EntityAssetMatch.score(entity.path(), candidate, "main") > 0) {
                    candidates.add(candidate);
                }
            }
        }

        List<String> ranked = new ArrayList<>(candidates);
        ranked.removeIf(candidate ->
                EntityAssetMatch.score(entity.path(), candidate, "main") <= 0);
        ranked.sort(Comparator.comparingInt(
                (String candidate) -> EntityAssetMatch.score(entity.path(), candidate, "main")
                        + EntityAssetMatch.appearanceScore(metadata, candidate))
                .reversed());

        for (String assetPath : ranked) {
            byte[] bytes = assets.read(assetPath);
            if (bytes == null) continue;
            try {
                List<ModelQuad> parsed = parseGeo(entity, bytes, metadata);
                if (!parsed.isEmpty()) {
                    LOGGER.debug("Resolved {} from {}", entity.id(), assetPath);
                    return parsed;
                }
            } catch (RuntimeException e) {
                LOGGER.debug("Could not parse entity geo {} for {}: {}",
                        assetPath, entity.id(), e.toString());
            }
        }
        return List.of();
    }

    private static List<String> geoCandidates(String path) {
        String leaf = leaf(path);
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        for (String name : List.of(path, leaf)) {
            paths.add("geo/" + name + ".geo.json");
            paths.add("geo/entity/" + name + ".geo.json");
            paths.add("geo/entities/" + name + ".geo.json");
            paths.add("geckolib/models/entity/" + name + ".geo.json");
            paths.add("models/entity/" + name + ".geo.json");
        }
        return List.copyOf(paths);
    }

    private List<ModelQuad> parseGeo(EntityKey entity, byte[] bytes, Map<String, String> metadata) {
        JsonObject root = JsonParser.parseString(
                new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) return List.of();

        JsonObject geometry = selectGeometry(entity, geometries, metadata);
        if (geometry == null) return List.of();
        JsonObject description = geometry.has("description")
                ? geometry.getAsJsonObject("description")
                : new JsonObject();
        float textureWidth = number(description, "texture_width", 64F);
        float textureHeight = number(description, "texture_height", 64F);

        String texture = findTexture(entity.namespace(), entity.path(), "main", metadata);
        if (texture == null) texture = FALLBACK_TEXTURE;

        JsonArray bonesJson = geometry.getAsJsonArray("bones");
        if (bonesJson == null) return List.of();

        Map<String, Bone> bones = new HashMap<>();
        for (JsonElement element : bonesJson) {
            JsonObject json = element.getAsJsonObject();
            String name = string(json, "name", "");
            if (name.isBlank()) continue;
            bones.put(name, new Bone(
                    name,
                    string(json, "parent", null),
                    vector(json.get("pivot"), new float[]{0F, 0F, 0F}),
                    vector(json.get("rotation"), new float[]{0F, 0F, 0F}),
                    json.getAsJsonArray("cubes")));
        }

        List<ModelQuad> out = new ArrayList<>();
        Map<String, Matrix4f> transforms = new HashMap<>();
        for (Bone bone : bones.values()) {
            if (bone.cubes() == null) continue;
            Matrix4f boneTransform = boneTransform(bone, bones, transforms, new LinkedHashSet<>());

            for (JsonElement cubeElement : bone.cubes()) {
                appendGeoCube(
                        out,
                        cubeElement.getAsJsonObject(),
                        boneTransform,
                        texture,
                        textureWidth,
                        textureHeight);
            }
        }

        normalizeGeo(out);
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static JsonObject selectGeometry(EntityKey entity, JsonArray geometries, Map<String, String> metadata) {
        JsonObject best = null;
        int bestScore = Integer.MIN_VALUE;

        for (JsonElement element : geometries) {
            if (!element.isJsonObject()) continue;
            JsonObject geometry = element.getAsJsonObject();
            JsonObject description = geometry.has("description")
                    ? geometry.getAsJsonObject("description")
                    : null;
            String identifier = description == null
                    ? null
                    : string(description, "identifier", null);
            int score = identifier == null
                    ? 0
                    : EntityAssetMatch.score(entity.path(), identifier, "main")
                            + EntityAssetMatch.appearanceScore(metadata, identifier);
            if (best == null || score > bestScore) {
                best = geometry;
                bestScore = score;
            }
        }
        return best;
    }

    private static Matrix4f boneTransform(
            Bone bone,
            Map<String, Bone> bones,
            Map<String, Matrix4f> cache,
            Set<String> visiting) {
        Matrix4f cached = cache.get(bone.name());
        if (cached != null) return new Matrix4f(cached);
        if (!visiting.add(bone.name())) return new Matrix4f();

        Matrix4f matrix = new Matrix4f();
        if (bone.parent() != null) {
            Bone parent = bones.get(bone.parent());
            if (parent != null) {
                matrix.set(boneTransform(parent, bones, cache, visiting));
            }
        }

        rotateAround(matrix, bone.pivot(), bone.rotation());
        visiting.remove(bone.name());
        cache.put(bone.name(), new Matrix4f(matrix));
        return matrix;
    }

    private static void appendGeoCube(
            List<ModelQuad> out,
            JsonObject cube,
            Matrix4f boneTransform,
            String texture,
            float textureWidth,
            float textureHeight) {
        float[] origin = vector(cube.get("origin"), null);
        float[] size = vector(cube.get("size"), null);
        if (origin == null || size == null) return;

        float inflate = number(cube, "inflate", 0F);
        float minX = origin[0] - inflate;
        float minY = origin[1] - inflate;
        float minZ = origin[2] - inflate;
        float maxX = origin[0] + size[0] + inflate;
        float maxY = origin[1] + size[1] + inflate;
        float maxZ = origin[2] + size[2] + inflate;

        Matrix4f transform = new Matrix4f(boneTransform);
        if (cube.has("rotation")) {
            float[] pivot = vector(cube.get("pivot"), new float[]{
                    origin[0] + size[0] / 2F,
                    origin[1] + size[1] / 2F,
                    origin[2] + size[2] / 2F});
            rotateAround(transform, pivot, vector(cube.get("rotation"), new float[]{0F, 0F, 0F}));
        }

        float dx = Math.abs(size[0]);
        float dy = Math.abs(size[1]);
        float dz = Math.abs(size[2]);
        boolean mirror = cube.has("mirror") && cube.get("mirror").getAsBoolean();
        JsonElement uvDefinition = cube.get("uv");

        // Bedrock geometry supports two UV encodings:
        //  - [u,v]: the standard unfolded box layout
        //  - {north:{uv:[...],uv_size:[...]}, ...}: independently authored faces
        // GeckoLib/AzureLib exporters use both, so preserve either form instead of
        // reducing everything to the compact layout.
        if (uvDefinition != null && uvDefinition.isJsonObject()) {
            JsonObject faces = uvDefinition.getAsJsonObject();
            addGeoFaceIfPresent(out, transform, texture, faces, "down", Direction.DOWN,
                    minX,minY,minZ,maxX,maxY,maxZ, dx,dz, textureWidth,textureHeight, mirror);
            addGeoFaceIfPresent(out, transform, texture, faces, "up", Direction.UP,
                    minX,minY,minZ,maxX,maxY,maxZ, dx,dz, textureWidth,textureHeight, mirror);
            addGeoFaceIfPresent(out, transform, texture, faces, "west", Direction.WEST,
                    minX,minY,minZ,maxX,maxY,maxZ, dz,dy, textureWidth,textureHeight, mirror);
            addGeoFaceIfPresent(out, transform, texture, faces, "north", Direction.NORTH,
                    minX,minY,minZ,maxX,maxY,maxZ, dx,dy, textureWidth,textureHeight, mirror);
            addGeoFaceIfPresent(out, transform, texture, faces, "east", Direction.EAST,
                    minX,minY,minZ,maxX,maxY,maxZ, dz,dy, textureWidth,textureHeight, mirror);
            addGeoFaceIfPresent(out, transform, texture, faces, "south", Direction.SOUTH,
                    minX,minY,minZ,maxX,maxY,maxZ, dx,dy, textureWidth,textureHeight, mirror);
            return;
        }

        float u = 0F;
        float v = 0F;
        if (uvDefinition != null && uvDefinition.isJsonArray()) {
            JsonArray uv = uvDefinition.getAsJsonArray();
            if (uv.size() >= 2) {
                u = uv.get(0).getAsFloat();
                v = uv.get(1).getAsFloat();
            }
        }

        addGeoFace(out, transform, texture,
                corners(minX,minY,minZ,maxX,maxY,maxZ, Direction.DOWN),
                maybeMirrorUv(uvRect(u + dz + dx, v, u + dz + dx + dx, v + dz,
                        textureWidth, textureHeight), mirror));
        addGeoFace(out, transform, texture,
                corners(minX,minY,minZ,maxX,maxY,maxZ, Direction.UP),
                maybeMirrorUv(uvRect(u + dz, v, u + dz + dx, v + dz,
                        textureWidth, textureHeight), mirror));
        addGeoFace(out, transform, texture,
                corners(minX,minY,minZ,maxX,maxY,maxZ, Direction.WEST),
                maybeMirrorUv(uvRect(u, v + dz, u + dz, v + dz + dy,
                        textureWidth, textureHeight), mirror));
        addGeoFace(out, transform, texture,
                corners(minX,minY,minZ,maxX,maxY,maxZ, Direction.NORTH),
                maybeMirrorUv(uvRect(u + dz, v + dz, u + dz + dx, v + dz + dy,
                        textureWidth, textureHeight), mirror));
        addGeoFace(out, transform, texture,
                corners(minX,minY,minZ,maxX,maxY,maxZ, Direction.EAST),
                maybeMirrorUv(uvRect(u + dz + dx, v + dz, u + dz + dx + dz, v + dz + dy,
                        textureWidth, textureHeight), mirror));
        addGeoFace(out, transform, texture,
                corners(minX,minY,minZ,maxX,maxY,maxZ, Direction.SOUTH),
                maybeMirrorUv(uvRect(u + dz + dx + dz, v + dz,
                        u + dz + dx + dz + dx, v + dz + dy,
                        textureWidth, textureHeight), mirror));
    }

    private static void addGeoFaceIfPresent(
            List<ModelQuad> out,
            Matrix4f transform,
            String texture,
            JsonObject faces,
            String faceName,
            Direction direction,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            float defaultWidth, float defaultHeight,
            float textureWidth, float textureHeight,
            boolean mirror) {
        JsonElement element = faces.get(faceName);
        if (element == null || !element.isJsonObject()) return;

        JsonObject face = element.getAsJsonObject();
        float[] uv = vector2(face.get("uv"), null);
        if (uv == null) return;

        float[] uvSize = vector2(face.get("uv_size"),
                new float[]{defaultWidth, defaultHeight});
        float[] mapped = uvRect(
                uv[0], uv[1],
                uv[0] + uvSize[0], uv[1] + uvSize[1],
                textureWidth, textureHeight);

        addGeoFace(
                out,
                transform,
                texture,
                corners(minX,minY,minZ,maxX,maxY,maxZ,direction),
                maybeMirrorUv(mapped, mirror));
    }

    private static float[] maybeMirrorUv(float[] uv, boolean mirror) {
        if (!mirror) return uv;
        // Swap left/right corners while retaining the face's winding.
        return new float[]{
                uv[2], uv[3],
                uv[0], uv[1],
                uv[6], uv[7],
                uv[4], uv[5]};
    }

    private static void addGeoFace(
            List<ModelQuad> out,
            Matrix4f transform,
            String texture,
            float[] positions,
            float[] uvs) {
        Vector3f point = new Vector3f();
        for (int i = 0; i < 4; i++) {
            point.set(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]);
            transform.transformPosition(point);
            positions[i * 3] = point.x;
            positions[i * 3 + 1] = point.y;
            positions[i * 3 + 2] = point.z;
        }
        out.add(new ModelQuad(null, null, positions, uvs, texture, 0xFFFFFF));
    }

    private static void rotateAround(Matrix4f matrix, float[] pivot, float[] rotationDegrees) {
        if (rotationDegrees == null) return;
        float rx = (float) Math.toRadians(rotationDegrees[0]);
        float ry = (float) Math.toRadians(rotationDegrees[1]);
        float rz = (float) Math.toRadians(rotationDegrees[2]);
        if (Math.abs(rx) + Math.abs(ry) + Math.abs(rz) < 1.0e-7F) return;

        matrix.translate(pivot[0], pivot[1], pivot[2])
                .rotateXYZ(rx, ry, rz)
                .translate(-pivot[0], -pivot[1], -pivot[2]);
    }

    private static void normalizeGeo(List<ModelQuad> quads) {
        if (quads.isEmpty()) return;

        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (ModelQuad quad : quads) {
            float[] p = quad.positions();
            for (int i = 0; i < p.length; i += 3) {
                minX = Math.min(minX, p[i]);
                minY = Math.min(minY, p[i + 1]);
                minZ = Math.min(minZ, p[i + 2]);
                maxX = Math.max(maxX, p[i]);
                maxZ = Math.max(maxZ, p[i + 2]);
            }
        }

        float centerX = (minX + maxX) / 2F;
        float centerZ = (minZ + maxZ) / 2F;
        for (ModelQuad quad : quads) {
            float[] p = quad.positions();
            for (int i = 0; i < p.length; i += 3) {
                p[i] -= centerX;
                p[i + 1] -= minY;
                p[i + 2] -= centerZ;
            }
        }
    }

    private List<ModelQuad> fallback(EntityKey entity, Map<String, String> metadata) {
        float width = positive(metadata.get("__bm3d_width"), 0.8F);
        float height = positive(metadata.get("__bm3d_height"), 1.0F);
        float half = width * 8F;
        float top = height * 16F;

        String texture = findTexture(entity.namespace(), entity.path(), "main", metadata);
        if (texture == null) texture = FALLBACK_TEXTURE;

        float minX = -half, maxX = half;
        float minY = 0F, maxY = top;
        float minZ = -half, maxZ = half;
        float[] uv = new float[]{0,16,16,16,16,0,0,0};

        List<ModelQuad> out = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            out.add(new ModelQuad(
                    null, null,
                    corners(minX,minY,minZ,maxX,maxY,maxZ,direction),
                    uv.clone(),
                    texture,
                    0xFFFFFF));
        }
        return List.copyOf(out);
    }

    private String findTexture(
            String namespace,
            String path,
            String layer,
            Map<String, String> metadata) {
        String leaf = leaf(path);
        LinkedHashSet<String> relativeCandidates = new LinkedHashSet<>();

        if (!"main".equals(layer)) {
            for (String name : List.of(path, leaf)) {
                relativeCandidates.add("entity/" + name + "/" + name + "_" + layer);
                relativeCandidates.add("entity/" + name + "/" + layer);
                relativeCandidates.add("entity/" + name + "_" + layer);
            }
            if ("fur".equals(layer) || "wool".equals(layer) || "undercoat".equals(layer)) {
                relativeCandidates.add("entity/" + leaf + "/" + leaf + "_fur");
                relativeCandidates.add("entity/" + leaf + "/" + leaf + "_wool");
            }
        } else {
            for (String name : List.of(path, leaf)) {
                relativeCandidates.add("entity/" + name + "/" + name);
                relativeCandidates.add("entity/" + name);
            }

            int underscore = leaf.lastIndexOf('_');
            if (underscore > 0 && underscore < leaf.length() - 1) {
                String prefix = leaf.substring(0, underscore);
                String suffix = leaf.substring(underscore + 1);
                relativeCandidates.add("entity/" + suffix + "/" + leaf);
                relativeCandidates.add("entity/" + suffix + "/" + suffix + "_" + prefix);
                relativeCandidates.add("entity/" + prefix + "/" + leaf);
            }

            for (String variant : List.of(
                    "brown", "white", "gray", "red", "temperate", "creamy", "tabby")) {
                relativeCandidates.add("entity/" + leaf + "/" + leaf + "_" + variant);
                relativeCandidates.add("entity/" + leaf + "/" + variant);
            }
        }

        String root = "assets/" + namespace + "/textures/";
        LinkedHashSet<String> available = new LinkedHashSet<>();
        for (String relative : relativeCandidates) {
            String full = root + relative + ".png";
            if (assets.read(full) != null) available.add(full);
        }

        for (String discovered : assets.findPaths(
                "assets/" + namespace + "/textures",
                candidate -> candidate.toLowerCase(Locale.ROOT).endsWith(".png"),
                2048)) {
            if (EntityAssetMatch.score(path, discovered, layer) > 0) {
                available.add(discovered);
            }
        }

        String selected = null;
        int selectedScore = Integer.MIN_VALUE;
        for (String candidate : available) {
            int score = EntityAssetMatch.score(path, candidate, layer)
                    + EntityAssetMatch.appearanceScore(metadata, candidate);
            if (score > selectedScore) {
                selected = candidate;
                selectedScore = score;
            }
        }

        if (selected == null || !selected.startsWith(root) || !selected.endsWith(".png")) {
            return null;
        }

        String resourcePath = selected.substring(root.length(), selected.length() - 4);
        LOGGER.debug("Resolved texture for {}:{}#{} from {}", namespace, path, layer, selected);
        return namespace + ":" + resourcePath;
    }

    private BufferedImage loadTexture(String texture) {
        ResourceLocation id = ResourceLocation.tryParse(texture);
        if (id == null) return null;

        byte[] png = assets.read("assets/" + id.getNamespace() + "/textures/" + id.getPath() + ".png");
        if (png == null) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException e) {
            return null;
        }
    }

    private static EntityKey entityKey(ResourceLocation model) {
        String path = model.getPath();
        if (!path.startsWith("entity/") || !path.endsWith("/main")) return null;
        String entity = path.substring("entity/".length(), path.length() - "/main".length());
        if (entity.isBlank()) return null;
        return new EntityKey(model.getNamespace(), entity);
    }

    private static float[] corners(
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            Direction direction) {
        return switch (direction) {
            case DOWN -> new float[]{minX,minY,minZ, maxX,minY,minZ, maxX,minY,maxZ, minX,minY,maxZ};
            case UP -> new float[]{minX,maxY,maxZ, maxX,maxY,maxZ, maxX,maxY,minZ, minX,maxY,minZ};
            case NORTH -> new float[]{maxX,minY,minZ, minX,minY,minZ, minX,maxY,minZ, maxX,maxY,minZ};
            case SOUTH -> new float[]{minX,minY,maxZ, maxX,minY,maxZ, maxX,maxY,maxZ, minX,maxY,maxZ};
            case WEST -> new float[]{minX,minY,minZ, minX,minY,maxZ, minX,maxY,maxZ, minX,maxY,minZ};
            case EAST -> new float[]{maxX,minY,maxZ, maxX,minY,minZ, maxX,maxY,minZ, maxX,maxY,maxZ};
        };
    }

    private static float[] uvRect(
            float u1, float v1, float u2, float v2, float textureWidth, float textureHeight) {
        float x1 = u1 / Math.max(1F, textureWidth) * 16F;
        float y1 = v1 / Math.max(1F, textureHeight) * 16F;
        float x2 = u2 / Math.max(1F, textureWidth) * 16F;
        float y2 = v2 / Math.max(1F, textureHeight) * 16F;
        return new float[]{x1,y2, x2,y2, x2,y1, x1,y1};
    }

    private static float[] floats(JsonArray array) {
        if (array == null) return new float[0];
        float[] values = new float[array.size()];
        for (int i = 0; i < array.size(); i++) values[i] = array.get(i).getAsFloat();
        return values;
    }

    private static float[] vector2(JsonElement element, float[] fallback) {
        if (element == null || !element.isJsonArray()) {
            return fallback == null ? null : fallback.clone();
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() < 2) {
            return fallback == null ? null : fallback.clone();
        }
        return new float[]{array.get(0).getAsFloat(), array.get(1).getAsFloat()};
    }

    private static float[] vector(JsonElement element, float[] fallback) {
        if (element == null || !element.isJsonArray()) return fallback == null ? null : fallback.clone();
        JsonArray array = element.getAsJsonArray();
        if (array.size() < 3) return fallback == null ? null : fallback.clone();
        return new float[]{
                array.get(0).getAsFloat(),
                array.get(1).getAsFloat(),
                array.get(2).getAsFloat()};
    }

    private static float number(JsonObject object, String key, float fallback) {
        return object != null && object.has(key) ? object.get(key).getAsFloat() : fallback;
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object != null && object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static float positive(String value, float fallback) {
        if (value == null) return fallback;
        try {
            float parsed = Float.parseFloat(value);
            return Float.isFinite(parsed) && parsed > 0F ? parsed : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String leaf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private record RawMesh(float[] positions, float[] uvs) {
    }

    private record EntityKey(String namespace, String path) {
        String id() {
            return namespace + ":" + path;
        }
    }

    private record Bone(
            String name,
            String parent,
            float[] pivot,
            float[] rotation,
            JsonArray cubes) {
    }
}
