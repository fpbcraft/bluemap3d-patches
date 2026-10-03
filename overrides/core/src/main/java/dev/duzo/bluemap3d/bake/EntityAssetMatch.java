package dev.duzo.bluemap3d.bake;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Pure entity-asset matching policy.
 *
 * <p>Kept independent from Minecraft classes so its collision/variant rules can be
 * exercised by ordinary unit tests without bootstrapping a game runtime.
 */
final class EntityAssetMatch {

    private static final Set<String> GENERIC_ASSET_TOKENS = Set.of(
            "assets", "textures", "texture", "entity", "entities", "geo", "geckolib",
            "models", "model", "main", "default", "base", "normal", "replaced", "mesh", "st");

    private static final Set<String> MAIN_LAYER_PENALTIES = Set.of(
            "glow", "glowmask", "emissive", "overlay", "armor", "eyes");

    private EntityAssetMatch() {
    }

    static int score(String entityPath, String assetPath, String layer) {
        String entity = compactName(leaf(entityPath));
        if (entity.isEmpty()) return 0;

        String stem = compactName(assetStem(assetPath));
        String whole = compactName(assetPath);
        int score = 0;

        if (stem.equals(entity)) score = 1000;
        else if (stem.startsWith(entity) || stem.endsWith(entity)) score = 850;
        else if (stem.contains(entity)) score = 700;
        else if (whole.contains(entity)) score = 450;

        // Many mods deliberately omit the entity family from model filenames:
        // badlands_creeper -> geo/badlands.geo.json
        // black_bear       -> geo/entity/bear.geo.json
        // elokosa_howler   -> geo/elokosa.geo.json
        // Treat shared semantic path tokens as a weaker match than a literal id.
        if (score == 0) {
            score = semanticTokenScore(entityPath, assetPath);
        }

        // A layer/variant name may refine a match, but it cannot create an entity match.
        if (score == 0) return 0;

        String normalizedLayer = compactName(layer);
        if (!"main".equals(layer) && !normalizedLayer.isEmpty()) {
            if (stem.contains(normalizedLayer)) score += 220;
            else if (whole.contains(normalizedLayer)) score += 100;
            else score -= 100;
        } else if ("main".equals(layer)) {
            Set<String> assetTokens = new HashSet<>(assetSemanticTokens(assetPath));
            for (String token : MAIN_LAYER_PENALTIES) {
                if (assetTokens.contains(token)) {
                    score -= 180;
                    break;
                }
            }
        }

        String lower = assetPath.toLowerCase(Locale.ROOT);
        if (lower.contains("/entity/") || lower.contains("/entities/")
                || lower.contains("/geo/") || lower.contains("/geckolib/")) {
            score += 80;
        }

        return Math.max(score, 0);
    }

    static int appearanceScore(Map<String, String> metadata, String candidate) {
        if (metadata == null || metadata.isEmpty() || candidate == null) return 0;
        String normalizedCandidate = compactName(candidate);
        int score = 0;

        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            if (!entry.getKey().startsWith("__bm3d_visual_")) continue;
            String token = compactName(entry.getValue());
            if (token.length() < 2) continue;
            if (normalizedCandidate.contains(token)) score += 400;
        }
        return score;
    }

    private static int semanticTokenScore(String entityPath, String assetPath) {
        List<String> entityTokens = tokens(leaf(entityPath));
        List<String> assetTokens = assetSemanticTokens(assetPath);
        if (entityTokens.isEmpty() || assetTokens.isEmpty()) return 0;

        Set<String> entitySet = new HashSet<>(entityTokens);
        List<String> meaningfulAssetTokens = assetTokens.stream()
                .filter(token -> !GENERIC_ASSET_TOKENS.contains(token))
                .filter(token -> token.length() >= 3)
                .toList();
        if (meaningfulAssetTokens.isEmpty()) return 0;

        int shared = 0;
        int sharedChars = 0;
        for (String token : meaningfulAssetTokens) {
            if (entitySet.contains(token)) {
                shared++;
                sharedChars += token.length();
            }
        }
        if (shared == 0) return 0;

        boolean assetDescribesEntity = meaningfulAssetTokens.stream()
                .allMatch(entitySet::contains);

        // A model whose meaningful name is entirely contained in the registry id is
        // strong evidence (bear -> black_bear, badlands -> badlands_creeper).
        if (assetDescribesEntity) {
            return 560 + shared * 60 + Math.min(120, sharedChars * 4);
        }

        // Directory names also carry useful semantics for textures
        // (textures/entity/ocean/brown_1.png for ocean_creeper). Keep this deliberately
        // below full subset matches so a concrete model/texture always wins.
        return 320 + shared * 45 + Math.min(80, sharedChars * 3);
    }

    private static List<String> assetSemanticTokens(String assetPath) {
        if (assetPath == null || assetPath.isBlank()) return List.of();

        String normalized = assetPath.replace('\\', '/').toLowerCase(Locale.ROOT);
        int start = -1;
        for (String marker : List.of("/entity/", "/entities/", "/geo/", "/models/")) {
            int markerIndex = normalized.lastIndexOf(marker);
            if (markerIndex >= 0) {
                start = Math.max(start, markerIndex + marker.length());
            }
        }

        String semantic = start >= 0 ? normalized.substring(start) : leaf(normalized);
        for (String suffix : new String[]{".geo.json", ".json", ".png"}) {
            if (semantic.endsWith(suffix)) {
                semantic = semantic.substring(0, semantic.length() - suffix.length());
                break;
            }
        }
        return tokens(semantic);
    }

    private static List<String> tokens(String value) {
        if (value == null || value.isBlank()) return List.of();

        List<String> out = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = Character.toLowerCase(value.charAt(i));
            if (Character.isLetterOrDigit(ch)) {
                token.append(ch);
            } else if (!token.isEmpty()) {
                out.add(token.toString());
                token.setLength(0);
            }
        }
        if (!token.isEmpty()) out.add(token.toString());
        return List.copyOf(out);
    }

    private static String assetStem(String path) {
        String name = leaf(path);
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : new String[]{".geo.json", ".json", ".png"}) {
            if (lower.endsWith(suffix)) {
                return name.substring(0, name.length() - suffix.length());
            }
        }
        return name;
    }

    private static String compactName(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = Character.toLowerCase(value.charAt(i));
            if (Character.isLetterOrDigit(ch)) out.append(ch);
        }
        return out.toString();
    }

    private static String leaf(String path) {
        if (path == null) return "";
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
