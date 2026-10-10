package dev.duzo.bluemap3d.bake;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;

/**
 * Data-driven approximation of Create's built-in sprite shifts for dedicated-server baking.
 *
 * <p>Create deliberately leaves client sprite objects uninitialised on a dedicated server.
 * The stable part of its contract is the resource naming scheme and CT type, so this class
 * mirrors those declarations without loading client classes.
 */
final class CreateConnectedTextures {

    private CreateConnectedTextures() {
    }

    static Spec find(String texture, BlockState state) {
        if (texture == null) return null;

        Spec createDeco = createDeco(texture, state);
        if (createDeco != null) return createDeco;

        Spec railways = railways(texture);
        if (railways != null) return railways;

        if (!texture.startsWith("create:block/")) return null;
        String path = texture.substring("create:block/".length());
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();

        if ("palettes/framed_glass".equals(path)) {
            if (block.contains("horizontal_framed_glass")) {
                return spec("horizontal_kryppers", "palettes/horizontal_framed_glass");
            }
            if (block.contains("vertical_framed_glass")) {
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
            // The client rotates through four visual variants. Pick a deterministic variant
            // from position in the resolver while retaining the same connection semantics.
            return new Spec("rectangle", "create:block/palettes/weathered_iron_window_1_connected", true);
        }

        if (path.startsWith("scaffold/")) return spec("horizontal", path);
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

        if ("crafter_side".equals(path)) {
            return spec("vertical", path);
        }
        if (path.endsWith("encased_cogwheel_side")) {
            return spec("vertical", path);
        }
        if ("girder_pole_side".equals(path)) {
            return spec("vertical", path);
        }
        if ("tunnel/brass_tunnel_top".equals(path)) {
            return spec("vertical", path);
        }

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
            String connected = block.contains("shingle")
                    ? path.replace("copper_roof_top", "copper_shingles_top")
                    : block.contains("tile")
                            ? path.replace("copper_roof_top", "copper_tiles_top")
                            : null;
            return connected == null ? null : spec("roof", connected);
        }

        if (path.startsWith("vault/vault_") && path.endsWith("_small")) {
            String target = path.substring(0, path.length() - "_small".length())
                    + ("true".equals(property(state, "large")) ? "_large" : "_medium");
            return new Spec("rectangle", "create:block/" + target, false);
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

    private static Spec railways(String texture) {
        String prefix = "railways:block/palettes/";
        if (!texture.startsWith(prefix)) return null;

        String name = texture.substring(texture.lastIndexOf('/') + 1);
        String type = switch (name) {
            case "slashed", "riveted", "vent",
                    "wrapped_slashed", "copper_wrapped_slashed", "iron_wrapped_slashed" ->
                    "omnidirectional";
            case "riveted_pillar_side", "tank_side",
                    "wrapped_tank_side", "copper_wrapped_tank_side", "iron_wrapped_tank_side" ->
                    "vertical_pinkmachine";
            case "boiler_side", "wrapped_boiler_side",
                    "copper_wrapped_boiler_side", "iron_wrapped_boiler_side" ->
                    "horizontal_kryppers";
            default -> name.endsWith("_window") ? "vertical" : null;
        };
        return type == null ? null : new Spec(type, texture + "_connected", false);
    }

    private static Spec createDeco(String texture, BlockState state) {
        String prefix = "createdeco:block/palettes/";
        if (!texture.startsWith(prefix)) return null;
        String path = texture.substring(prefix.length());

        if (path.startsWith("sheet_metal/") && path.endsWith("_sheet_metal")) {
            return new Spec("vertical", texture + "_connected", false);
        }
        if (path.startsWith("catwalks/") && path.endsWith("_catwalk")) {
            return new Spec("omnidirectional", texture + "_connected", false);
        }
        if (path.startsWith("windows/") && path.endsWith("_window")) {
            return new Spec("vertical", texture + "_connected", false);
        }
        if (path.startsWith("shipping_containers/") && path.endsWith("_small")) {
            String target = texture.substring(0, texture.length() - "_small".length())
                    + ("true".equals(property(state, "large")) ? "_large" : "_medium");
            return new Spec("rectangle", target, false);
        }
        return null;
    }

    private static Spec spec(String type, String path) {
        return new Spec(type, "create:block/" + path + "_connected", false);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String property(BlockState state, String name) {
        for (net.minecraft.world.level.block.state.properties.Property property
                : state.getProperties()) {
            if (!property.getName().equals(name)) continue;
            Comparable value = state.getValue(property);
            return property.getName(value);
        }
        return "";
    }

    record Spec(String type, String sheetTexture, boolean positionVariant) {
        String cacheKey(int x, int y, int z) {
            if (!positionVariant) return type + "|" + sheetTexture;
            int variant = Math.floorMod(mix(x, y, z), 4) + 1;
            String selected = sheetTexture.replace("_1_connected", "_" + variant + "_connected");
            return type + "|" + selected;
        }

        String sheetTexture(int x, int y, int z) {
            if (!positionVariant) return sheetTexture;
            int variant = Math.floorMod(mix(x, y, z), 4) + 1;
            return sheetTexture.replace("_1_connected", "_" + variant + "_connected");
        }

        private static int mix(int x, int y, int z) {
            int h = x * 73428767 ^ y * 912931 ^ z * 4382893;
            h ^= h >>> 13;
            h *= 1274126177;
            return h ^ (h >>> 16);
        }
    }
}
