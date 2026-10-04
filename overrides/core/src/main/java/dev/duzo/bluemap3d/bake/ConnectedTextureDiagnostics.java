package dev.duzo.bluemap3d.bake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.Command;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelResource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * On-demand inventory of connected-texture assets present in the real server modpack.
 *
 * <p>This intentionally discovers candidates instead of maintaining another compatibility
 * allow-list. It detects Fusion metadata, conventional *_connected sheets, and small /
 * medium / large CT families, then resolves model inheritance and blockstate model usage so
 * a report can answer which installed blocks actually consume each base texture.
 */
public final class ConnectedTextureDiagnostics {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_ASSET_PATHS = 500_000;
    private static volatile AssetIndex ACTIVE_ASSETS;

    private ConnectedTextureDiagnostics() {
    }

    static void install(AssetIndex assets) {
        ACTIVE_ASSETS = assets;
    }

    public static int dump(CommandSourceStack source) {
        AssetIndex assets = ACTIVE_ASSETS;
        if (assets == null) {
            source.sendFailure(Component.literal(
                    "BlueMap3D asset index is not active. Run this after BlueMap has loaded."));
            return 0;
        }

        Path output = source.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("bluemap3d")
                .resolve("connected-textures.json")
                .toAbsolutePath()
                .normalize();

        try {
            DumpFile report = scan(assets);
            Files.createDirectories(output.getParent());
            Files.writeString(
                    output,
                    GSON.toJson(report) + System.lineSeparator(),
                    StandardCharsets.UTF_8);

            long used = report.candidates().stream()
                    .filter(candidate -> !candidate.blocks().isEmpty())
                    .count();
            source.sendSuccess(
                    () -> Component.literal(
                            "Wrote " + report.candidates().size()
                                    + " connected-texture candidate(s), "
                                    + used + " referenced by blocks, to " + output),
                    false);
            return Command.SINGLE_SUCCESS;
        } catch (IOException | RuntimeException error) {
            source.sendFailure(Component.literal(
                    "Could not write connected-texture diagnostics: " + error));
            return 0;
        }
    }

    static DumpFile scan(AssetIndex assets) throws IOException {
        List<String> paths = assets.pathsUnder("assets", MAX_ASSET_PATHS);
        boolean truncated = paths.size() >= MAX_ASSET_PATHS;
        Set<String> pathSet = Set.copyOf(paths);

        Map<String, ModelInfo> models = loadModels(assets, paths);
        Map<String, Set<String>> modelBlocks = loadBlockstateModelUsage(assets, paths);

        Map<String, Set<String>> effectiveTextureCache = new HashMap<>();
        Map<String, Set<String>> textureModels = new HashMap<>();
        for (String model : models.keySet()) {
            for (String texture : effectiveTextures(
                    model, models, effectiveTextureCache, new HashSet<>())) {
                textureModels.computeIfAbsent(texture, ignored -> new LinkedHashSet<>())
                        .add(model);
            }
        }

        Map<String, CandidateBuilder> candidates = new LinkedHashMap<>();

        for (String path : paths) {
            if (path.endsWith(".png.mcmeta")) {
                FusionMetadata fusion = fusionMetadata(assets, path);
                if (fusion != null) {
                    String texture = textureIdFromAssetPath(
                            path.substring(0, path.length() - ".mcmeta".length()));
                    if (texture != null) {
                        candidate(candidates, texture)
                                .provider("fusion")
                                .reason("fusion:connecting metadata")
                                .layout(fusion.layout())
                                .metadataPath(path)
                                .target(texture);
                    }
                }
            }

            if (!path.endsWith(".png")) continue;
            String texture = textureIdFromAssetPath(path);
            if (texture == null) continue;

            if (texture.endsWith("_connected")) {
                String base = texture.substring(0, texture.length() - "_connected".length());
                CandidateBuilder builder = candidate(candidates, base)
                        .provider(providerHint(base))
                        .reason("*_connected texture pair")
                        .target(texture);
                addImageInfo(assets, path, builder);
            }

            if (texture.endsWith("_small")) {
                String stem = texture.substring(0, texture.length() - "_small".length());
                String medium = stem + "_medium";
                String large = stem + "_large";
                boolean hasMedium = pathSet.contains(assetPathForTexture(medium));
                boolean hasLarge = pathSet.contains(assetPathForTexture(large));
                if (hasMedium || hasLarge) {
                    CandidateBuilder builder = candidate(candidates, texture)
                            .provider(providerHint(texture))
                            .reason("_small/_medium/_large texture family");
                    if (hasMedium) builder.target(medium);
                    if (hasLarge) builder.target(large);
                    addImageInfo(assets, path, builder);
                }
            }
        }

        List<Candidate> output = new ArrayList<>();
        for (CandidateBuilder builder : candidates.values()) {
            Set<String> usedModels = textureModels.getOrDefault(builder.baseTexture, Set.of());
            LinkedHashSet<String> blocks = new LinkedHashSet<>();
            for (String model : usedModels) {
                blocks.addAll(modelBlocks.getOrDefault(model, Set.of()));
            }

            builder.models.addAll(usedModels);
            builder.blocks.addAll(blocks);
            output.add(builder.build());
        }
        output.sort(Comparator
                .comparing((Candidate c) -> c.blocks().isEmpty())
                .thenComparing(Candidate::baseTexture));

        Map<String, Integer> byProvider = new java.util.TreeMap<>();
        Map<String, Integer> referencedByNamespace = new java.util.TreeMap<>();
        for (Candidate candidate : output) {
            byProvider.merge(candidate.provider(), 1, Integer::sum);
            for (String block : candidate.blocks()) {
                int colon = block.indexOf(':');
                String namespace = colon < 0 ? "minecraft" : block.substring(0, colon);
                referencedByNamespace.merge(namespace, 1, Integer::sum);
            }
        }

        return new DumpFile(
                1,
                paths.size(),
                truncated,
                models.size(),
                modelBlocks.values().stream().mapToInt(Set::size).sum(),
                output.size(),
                byProvider,
                referencedByNamespace,
                List.copyOf(output));
    }

    private static Map<String, ModelInfo> loadModels(
            AssetIndex assets, List<String> paths) {
        Map<String, ModelInfo> out = new HashMap<>();
        for (String path : paths) {
            if (!path.contains("/models/") || !path.endsWith(".json")) continue;
            String modelId = modelIdFromAssetPath(path);
            if (modelId == null) continue;

            byte[] raw = assets.read(path);
            if (raw == null) continue;
            try {
                JsonObject json = JsonParser.parseString(
                        new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
                String namespace = namespaceOf(modelId);
                String parent = null;
                if (json.has("parent") && json.get("parent").isJsonPrimitive()) {
                    parent = normalizeModelId(json.get("parent").getAsString(), namespace);
                }

                LinkedHashSet<String> textures = new LinkedHashSet<>();
                JsonElement textureElement = json.get("textures");
                if (textureElement != null && textureElement.isJsonObject()) {
                    for (JsonElement value : textureElement.getAsJsonObject().asMap().values()) {
                        if (!value.isJsonPrimitive()) continue;
                        String text = value.getAsString();
                        if (text.startsWith("#")) continue;
                        String normalized = normalizeTextureId(text, namespace);
                        if (normalized != null) textures.add(normalized);
                    }
                }
                out.put(modelId, new ModelInfo(parent, Set.copyOf(textures)));
            } catch (RuntimeException ignored) {
                // Invalid/non-vanilla JSON is still useful to BlueMap through other adapters,
                // but cannot participate in this structural inventory.
            }
        }
        return out;
    }

    private static Map<String, Set<String>> loadBlockstateModelUsage(
            AssetIndex assets, List<String> paths) {
        Map<String, Set<String>> out = new HashMap<>();
        for (String path : paths) {
            if (!path.contains("/blockstates/") || !path.endsWith(".json")) continue;
            String blockId = blockIdFromAssetPath(path);
            if (blockId == null) continue;

            byte[] raw = assets.read(path);
            if (raw == null) continue;
            try {
                JsonElement json = JsonParser.parseString(
                        new String(raw, StandardCharsets.UTF_8));
                LinkedHashSet<String> models = new LinkedHashSet<>();
                collectModelReferences(json, namespaceOf(blockId), models);
                for (String model : models) {
                    out.computeIfAbsent(model, ignored -> new LinkedHashSet<>()).add(blockId);
                }
            } catch (RuntimeException ignored) {
                // Keep scanning other blockstates.
            }
        }
        return out;
    }

    private static void collectModelReferences(
            JsonElement element, String namespace, Set<String> output) {
        if (element == null || element.isJsonNull()) return;
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectModelReferences(child, namespace, output);
            }
            return;
        }
        if (!element.isJsonObject()) return;

        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            if ("model".equals(entry.getKey())
                    && entry.getValue().isJsonPrimitive()
                    && entry.getValue().getAsJsonPrimitive().isString()) {
                String model = normalizeModelId(entry.getValue().getAsString(), namespace);
                if (model != null) output.add(model);
            } else {
                collectModelReferences(entry.getValue(), namespace, output);
            }
        }
    }

    private static Set<String> effectiveTextures(
            String model,
            Map<String, ModelInfo> models,
            Map<String, Set<String>> cache,
            Set<String> visiting) {
        Set<String> cached = cache.get(model);
        if (cached != null) return cached;
        if (!visiting.add(model)) return Set.of();

        ModelInfo info = models.get(model);
        if (info == null) {
            visiting.remove(model);
            return Set.of();
        }

        LinkedHashSet<String> textures = new LinkedHashSet<>();
        if (info.parent() != null) {
            textures.addAll(effectiveTextures(
                    info.parent(), models, cache, visiting));
        }
        textures.addAll(info.textures());
        visiting.remove(model);

        Set<String> result = Set.copyOf(textures);
        cache.put(model, result);
        return result;
    }

    private static FusionMetadata fusionMetadata(AssetIndex assets, String path) {
        byte[] raw = assets.read(path);
        if (raw == null) return null;
        try {
            JsonObject root = JsonParser.parseString(
                    new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement fusionElement = root.get("fusion");
            if (fusionElement == null || !fusionElement.isJsonObject()) return null;
            JsonObject fusion = fusionElement.getAsJsonObject();
            String type = fusion.has("type") ? fusion.get("type").getAsString() : "";
            if (!"connecting".equals(stripNamespace(type))) return null;
            String layout = fusion.has("layout")
                    ? fusion.get("layout").getAsString()
                    : "full";
            return new FusionMetadata(layout);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static CandidateBuilder candidate(
            Map<String, CandidateBuilder> candidates, String baseTexture) {
        return candidates.computeIfAbsent(
                baseTexture, CandidateBuilder::new);
    }

    private static void addImageInfo(
            AssetIndex assets, String path, CandidateBuilder builder) {
        if (builder.width != null) return;
        byte[] raw = assets.read(path);
        if (raw == null) return;
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw));
            if (image == null) return;
            builder.width = image.getWidth();
            builder.height = image.getHeight();
            int width = image.getWidth();
            int height = image.getHeight();
            for (int grid : List.of(2, 4, 8)) {
                if (width % grid == 0
                        && height % grid == 0
                        && width / grid == height / grid) {
                    builder.gridCandidates.add(grid + "x" + grid);
                }
            }
        } catch (IOException ignored) {
            // Dimensions are supplemental diagnostics only.
        }
    }

    private static String providerHint(String texture) {
        String namespace = namespaceOf(texture);
        return switch (namespace) {
            case "create" -> "create";
            case "createdeco" -> "create_deco";
            default -> "create_style_candidate";
        };
    }

    private static String textureIdFromAssetPath(String path) {
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith("assets/")) return null;
        String rest = normalized.substring("assets/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String namespace = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);
        if (!tail.startsWith("textures/") || !tail.endsWith(".png")) return null;
        String texturePath = tail.substring(
                "textures/".length(), tail.length() - ".png".length());
        return namespace + ":" + texturePath;
    }

    private static String modelIdFromAssetPath(String path) {
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith("assets/")) return null;
        String rest = normalized.substring("assets/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String namespace = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);
        if (!tail.startsWith("models/") || !tail.endsWith(".json")) return null;
        return namespace + ":" + tail.substring(
                "models/".length(), tail.length() - ".json".length());
    }

    private static String blockIdFromAssetPath(String path) {
        String normalized = path.replace('\\', '/');
        if (!normalized.startsWith("assets/")) return null;
        String rest = normalized.substring("assets/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0) return null;
        String namespace = rest.substring(0, slash);
        String tail = rest.substring(slash + 1);
        if (!tail.startsWith("blockstates/") || !tail.endsWith(".json")) return null;
        return namespace + ":" + tail.substring(
                "blockstates/".length(), tail.length() - ".json".length());
    }

    private static String assetPathForTexture(String texture) {
        int colon = texture.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : texture.substring(0, colon);
        String path = colon < 0 ? texture : texture.substring(colon + 1);
        return "assets/" + namespace + "/textures/" + path + ".png";
    }

    private static String normalizeTextureId(String value, String namespace) {
        if (value == null || value.isBlank() || value.startsWith("#")) return null;
        int colon = value.indexOf(':');
        return colon < 0 ? namespace + ":" + value : value;
    }

    private static String normalizeModelId(String value, String namespace) {
        if (value == null || value.isBlank()) return null;
        int colon = value.indexOf(':');
        return colon < 0 ? namespace + ":" + value : value;
    }

    private static String namespaceOf(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? "minecraft" : id.substring(0, colon);
    }

    private static String stripNamespace(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon < 0 ? lower : lower.substring(colon + 1);
    }

    private record FusionMetadata(String layout) {
    }

    private record ModelInfo(String parent, Set<String> textures) {
    }

    public record Candidate(
            String baseTexture,
            String provider,
            List<String> detectionReasons,
            String layout,
            List<String> targetTextures,
            List<String> metadataPaths,
            Integer width,
            Integer height,
            List<String> gridCandidates,
            List<String> models,
            List<String> blocks) {
    }

    public record DumpFile(
            int format,
            int scannedAssetPaths,
            boolean pathLimitReached,
            int parsedModels,
            int blockModelReferences,
            int candidateCount,
            Map<String, Integer> candidatesByProvider,
            Map<String, Integer> referencedBlocksByNamespace,
            List<Candidate> candidates) {
    }

    private static final class CandidateBuilder {
        private final String baseTexture;
        private String provider = "unknown";
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        private String layout;
        private final LinkedHashSet<String> targets = new LinkedHashSet<>();
        private final LinkedHashSet<String> metadataPaths = new LinkedHashSet<>();
        private Integer width;
        private Integer height;
        private final LinkedHashSet<String> gridCandidates = new LinkedHashSet<>();
        private final LinkedHashSet<String> models = new LinkedHashSet<>();
        private final LinkedHashSet<String> blocks = new LinkedHashSet<>();

        private CandidateBuilder(String baseTexture) {
            this.baseTexture = baseTexture;
        }

        private CandidateBuilder provider(String value) {
            if ("unknown".equals(provider)
                    || "fusion".equals(value)
                    || "create_deco".equals(value)
                    || "create".equals(value)) {
                provider = value;
            }
            return this;
        }

        private CandidateBuilder reason(String value) {
            reasons.add(value);
            return this;
        }

        private CandidateBuilder layout(String value) {
            if (layout == null) layout = value;
            return this;
        }

        private CandidateBuilder target(String value) {
            targets.add(value);
            return this;
        }

        private CandidateBuilder metadataPath(String value) {
            metadataPaths.add(value);
            return this;
        }

        private Candidate build() {
            List<String> sortedModels = models.stream().sorted().toList();
            List<String> sortedBlocks = blocks.stream().sorted().toList();
            return new Candidate(
                    baseTexture,
                    provider,
                    List.copyOf(reasons),
                    layout,
                    targets.stream().sorted().toList(),
                    metadataPaths.stream().sorted().toList(),
                    width,
                    height,
                    List.copyOf(gridCandidates),
                    sortedModels,
                    sortedBlocks);
        }
    }
}
