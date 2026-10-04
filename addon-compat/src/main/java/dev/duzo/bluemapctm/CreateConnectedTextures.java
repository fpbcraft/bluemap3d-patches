package dev.duzo.bluemapctm;

import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.world.BlockState;

import java.util.Locale;
import java.util.Map;

/**
 * Server-side catalog of Create's built-in connected-texture sprite shifts.
 *
 * <p>Create only registers CT behaviour on the client, so BlueMap cannot query the
 * registry directly. These entries mirror Create 1.21.1's stable resource paths and
 * AllCTTypes. Unknown Create textures simply fall back to BlueMap's normal renderer.
 */
final class CreateConnectedTextures {

    private static final String PREFIX = "create:block/";

    private CreateConnectedTextures() {
    }

    static Spec find(
            String texture,
            String blockId,
            Map<String, String> properties,
            Direction face) {
        if (texture == null || !texture.startsWith(PREFIX)) return null;
        String path = texture.substring(PREFIX.length());
        String blockPath = blockPath(blockId);

        if ("palettes/framed_glass".equals(path)) {
            if (blockPath.contains("horizontal_framed_glass")) {
                return spec("horizontal_kryppers", "palettes/horizontal_framed_glass");
            }
            if (blockPath.contains("vertical_framed_glass")) {
                return spec("vertical", "palettes/vertical_framed_glass");
            }
            return spec("omnidirectional", "palettes/framed_glass");
        }

        if ("palettes/industrial_iron_window".equals(path)) {
            return spec("rectangle", path);
        }
        if ("palettes/ornate_iron_window".equals(path)) {
            return spec("vertical", path);
        }
        if ("palettes/weathered_iron_window".equals(path)) {
            return new Spec(
                    "rectangle",
                    PREFIX + "palettes/weathered_iron_window_1_connected",
                    true);
        }

        if (path.startsWith("scaffold/")) {
            if (path.endsWith("_inside")) return spec("horizontal", path);
            return spec("horizontal", path);
        }

        if (path.endsWith("_casing") || path.endsWith("_casing_side")) {
            if ("creative_casing".equals(path)) return spec("rectangle", path);
            return spec("omnidirectional", path);
        }

        if ("linear_chassis_side".equals(path)
                || "secondary_linear_chassis_side".equals(path)
                || "linear_chassis_end".equals(path)
                || "linear_chassis_end_sticky".equals(path)) {
            return spec("omnidirectional", path);
        }

        if ("crafter_side".equals(path)) return spec("vertical", path);
        if (path.endsWith("encased_cogwheel_side")) return spec("vertical", path);
        if ("girder_pole_side".equals(path)) return spec("vertical", path);
        if ("tunnel/brass_tunnel_top".equals(path)) return spec("vertical", path);

        if ("fluid_tank".equals(path)
                || "fluid_tank_top".equals(path)
                || "fluid_tank_inner".equals(path)
                || "creative_fluid_tank".equals(path)) {
            return spec("rectangle", path);
        }

        if (path.startsWith("palettes/") && path.endsWith("_window")) {
            return spec("vertical", path);
        }

        if (path.contains("copper_roof_top")) {
            String connected = blockPath.contains("shingle")
                    ? path.replace("copper_roof_top", "copper_shingles_top")
                    : blockPath.contains("tile")
                            ? path.replace("copper_roof_top", "copper_tiles_top")
                            : null;
            return connected == null ? null : spec("roof", connected);
        }

        if (path.startsWith("vault/vault_") && path.endsWith("_small")) {
            String large = properties.getOrDefault("large", "false");
            String target = path.substring(0, path.length() - "_small".length())
                    + ("true".equals(large) ? "_large" : "_medium");
            return spec("rectangle", target);
        }

        if (path.startsWith("palettes/stone_types/")) {
            if (path.contains("/layered/")) {
                return spec("horizontal_kryppers", path);
            }
            if (path.contains("/pillar/")) {
                return spec("rectangle", path);
            }
            if (path.contains("/cap/")) {
                return spec("omnidirectional", path);
            }
        }

        return null;
    }

    static String typeForSheet(String texture) {
        if (texture == null || !texture.startsWith(PREFIX) || !texture.endsWith("_connected")) {
            return null;
        }
        String path = texture.substring(PREFIX.length());

        if ("palettes/framed_glass_connected".equals(path)) return "omnidirectional";
        if ("palettes/horizontal_framed_glass_connected".equals(path)) {
            return "horizontal_kryppers";
        }
        if ("palettes/vertical_framed_glass_connected".equals(path)) return "vertical";
        if ("palettes/industrial_iron_window_connected".equals(path)) return "rectangle";
        if ("palettes/ornate_iron_window_connected".equals(path)) return "vertical";
        if (path.matches("palettes/weathered_iron_window_[1-4]_connected")) {
            return "rectangle";
        }

        if (path.startsWith("scaffold/")) return "horizontal";

        if (path.equals("creative_casing_connected")) return "rectangle";
        if (path.endsWith("_casing_connected") || path.endsWith("_casing_side_connected")) {
            return "omnidirectional";
        }

        if (path.equals("linear_chassis_side_connected")
                || path.equals("secondary_linear_chassis_side_connected")
                || path.equals("linear_chassis_end_connected")
                || path.equals("linear_chassis_end_sticky_connected")) {
            return "omnidirectional";
        }

        if (path.equals("crafter_side_connected")) return "vertical";
        if (path.endsWith("encased_cogwheel_side_connected")) return "vertical";
        if (path.equals("girder_pole_side_connected")) return "vertical";
        if (path.equals("tunnel/brass_tunnel_top_connected")) return "vertical";

        if (path.equals("fluid_tank_connected")
                || path.equals("fluid_tank_top_connected")
                || path.equals("fluid_tank_inner_connected")
                || path.equals("creative_fluid_tank_connected")) {
            return "rectangle";
        }

        if (path.startsWith("palettes/") && path.endsWith("_window_connected")) {
            return "vertical";
        }

        if (path.contains("copper_shingles_top_connected")
                || path.contains("copper_tiles_top_connected")) {
            return "roof";
        }

        if (path.startsWith("vault/vault_")
                && (path.endsWith("_medium_connected") || path.endsWith("_large_connected"))) {
            return "rectangle";
        }

        if (path.startsWith("palettes/stone_types/")) {
            if (path.contains("/layered/")) return "horizontal_kryppers";
            if (path.contains("/pillar/")) return "rectangle";
            if (path.contains("/cap/")) return "omnidirectional";
        }

        return null;
    }

    static boolean isSourceTexture(String texture, String blockId) {
        return find(texture, blockId, Map.of(), Direction.NORTH) != null
                || (texture != null
                        && texture.startsWith(PREFIX + "vault/vault_")
                        && texture.endsWith("_small"));
    }

    private static Spec spec(String type, String path) {
        return new Spec(type, PREFIX + path + "_connected", false);
    }

    private static String blockPath(String blockId) {
        if (blockId == null) return "";
        int colon = blockId.indexOf(':');
        return colon < 0 ? blockId : blockId.substring(colon + 1);
    }

    record Spec(String type, String sheetTexture, boolean positionVariant) {

        String sheetTexture(int x, int y, int z) {
            if (!positionVariant) return sheetTexture;
            int variant = Math.floorMod(mix(x, y, z), 4) + 1;
            return sheetTexture.replace("_1_connected", "_" + variant + "_connected");
        }

        String semanticKey() {
            return type.toLowerCase(Locale.ROOT) + "|"
                    + (positionVariant
                            ? sheetTexture.replace("_1_connected", "_#_connected")
                            : sheetTexture);
        }

        private static int mix(int x, int y, int z) {
            int h = x * 73428767 ^ y * 912931 ^ z * 4382893;
            h ^= h >>> 13;
            h *= 1274126177;
            return h ^ (h >>> 16);
        }
    }
}
