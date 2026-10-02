package dev.duzo.bluemap3d.bake;

import java.util.Locale;
import java.util.Map;

/**
 * Pure entity-asset matching policy.
 *
 * <p>Kept independent from Minecraft classes so its collision/variant rules can be
 * exercised by ordinary unit tests without bootstrapping a game runtime.
 */
final class EntityAssetMatch {

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

        // A layer/variant name may refine a match, but it cannot create an entity match.
        if (score == 0) return 0;

        String normalizedLayer = compactName(layer);
        if (!"main".equals(layer) && !normalizedLayer.isEmpty()) {
            if (stem.contains(normalizedLayer)) score += 220;
            else if (whole.contains(normalizedLayer)) score += 100;
            else score -= 100;
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
