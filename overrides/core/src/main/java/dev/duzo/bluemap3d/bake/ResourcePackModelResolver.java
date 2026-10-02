package dev.duzo.bluemap3d.bake;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Resolves resource-pack model parent chains, texture indirection and OBJ wrappers.
 *
 * <p>Geometry generation stays in {@link ResourcePackSource}; this class owns only
 * resource lookup and model-chain semantics.
 */
final class ResourcePackModelResolver {

    private static final Gson GSON = new Gson();
    private static final int MAX_PARENT_DEPTH = 16;

    private final AssetIndex assets;
    private final Logger logger;
    private final Map<String, JsonObject> jsonCache = new HashMap<>();

    ResourcePackModelResolver(AssetIndex assets, Logger logger) {
        this.assets = assets;
        this.logger = logger;
    }

    JsonObject json(String path) {
        return jsonCache.computeIfAbsent(path, p -> {
            byte[] bytes = assets.read(p);
            if (bytes == null) {
                return null;
            }
            try {
                return GSON.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);
            } catch (RuntimeException e) {
                logger.debug("Malformed json at {}: {}", p, e.toString());
                return null;
            }
        });
    }

    ResourceLocation parse(String id) {
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

    JsonElement selectVariant(JsonObject variants, BlockState state) {
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

    boolean matches(JsonObject when, BlockState state) {
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

    Map<String, String> propertiesOf(BlockState state) {
        Map<String, String> out = new TreeMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            out.put(entry.getKey().getName(), nameOf(entry.getKey(), entry.getValue()));
        }
        return out;
    }

    private static boolean propertyMatches(String expected, String actual) {
        if (actual == null) return false;
        if (expected.equals(actual)) return true;
        if ("true".equals(actual) && "low".equals(expected)) return true;
        if ("false".equals(actual) && "none".equals(expected)) return true;
        return false;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String nameOf(
            Property<?> property, Comparable<?> value) {
        return ((Property<T>) property).getName((T) value);
    }

    JsonObject resolveTexturesOnly(String modelRef, Map<String, String> overrides) {
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
                for (Map.Entry<String, JsonElement> entry
                        : model.getAsJsonObject("textures").entrySet()) {
                    if (!textures.has(entry.getKey())) {
                        textures.add(entry.getKey(), entry.getValue());
                    }
                }
            }
            ref = model.has("parent") ? model.get("parent").getAsString() : null;
        }
        return textures;
    }

    JsonObject findObjStub(String modelRef) {
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

    List<ModelQuad> objQuads(
            String modelRef, JsonObject stub, Map<String, String> overrides) {
        if (!stub.has("model")) {
            return List.of();
        }
        ResourceLocation objLoc = parse(stub.get("model").getAsString());
        if (objLoc == null) {
            return List.of();
        }
        String objPath = "assets/" + objLoc.getNamespace() + "/" + objLoc.getPath();
        byte[] objBytes = assets.read(objPath);
        if (objBytes == null) {
            logger.debug("Obj model {} names mesh {} which is not available", modelRef, objPath);
            return List.of();
        }
        String objText = new String(objBytes, StandardCharsets.UTF_8);

        String mtlText = null;
        if (stub.has("mtl_override")) {
            ResourceLocation mtlLoc = parse(stub.get("mtl_override").getAsString());
            if (mtlLoc != null) {
                String mtlPath = "assets/" + mtlLoc.getNamespace() + "/" + mtlLoc.getPath();
                byte[] mtlBytes = assets.read(mtlPath);
                if (mtlBytes != null) {
                    mtlText = new String(mtlBytes, StandardCharsets.UTF_8);
                } else {
                    logger.debug(
                            "Obj model {} overrides material with {} which is not available",
                            modelRef,
                            mtlPath);
                }
            }
        }

        if (mtlText == null) {
            String mtlName = findMtllib(objText);
            if (mtlName != null) {
                int slash = objPath.lastIndexOf('/');
                String mtlPath =
                        (slash >= 0 ? objPath.substring(0, slash + 1) : "") + mtlName;
                byte[] mtlBytes = assets.read(mtlPath);
                if (mtlBytes != null) {
                    mtlText = new String(mtlBytes, StandardCharsets.UTF_8);
                } else {
                    logger.debug(
                            "Obj model {} names material {} which is not available",
                            modelRef,
                            mtlPath);
                }
            }
        }

        JsonObject textures = resolveTexturesOnly(modelRef, overrides);
        Map<String, String> resolvedTextures = new HashMap<>();
        for (String key : textures.keySet()) {
            String resolved = resolveTextureRef(textures, "#" + key);
            if (resolved != null) {
                resolvedTextures.put(key, resolved);
            }
        }

        boolean flipV = stub.has("flip_v") && stub.get("flip_v").getAsBoolean();
        return ObjModelReader.read(objText, mtlText, resolvedTextures, flipV);
    }

    JsonObject resolveModel(String modelRef, Map<String, String> overrides) {
        JsonObject merged = new JsonObject();
        JsonObject mergedTextures = new JsonObject();
        JsonArray elements = null;

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
                for (Map.Entry<String, JsonElement> entry
                        : model.getAsJsonObject("textures").entrySet()) {
                    if (!mergedTextures.has(entry.getKey())) {
                        mergedTextures.add(entry.getKey(), entry.getValue());
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

    String resolveTextureRef(JsonObject textures, String ref) {
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

    private static String findMtllib(String objText) {
        for (String rawLine : objText.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("mtllib ")) {
                return line.substring("mtllib ".length()).trim();
            }
        }
        return null;
    }
}
