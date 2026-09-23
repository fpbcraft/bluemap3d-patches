package dev.duzo.bluemaptrafficcraft;

import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

/** TrafficCraft PaintColor texture colors, kept dependency-free. */
final class TrafficCraftPalette {

    private static final int[] COLORS = {
            16383998, // white
            16351261, // orange
            13061821, // magenta
            3847130,  // light_blue
            16701501, // yellow
            8439583,  // lime
            15961002, // pink
            4673362,  // gray
            10329495, // light_gray
            1481884,  // cyan
            8991416,  // purple
            3949738,  // blue
            8606770,  // brown
            6192150,  // green
            11546150, // red
            1908001   // black
    };

    private TrafficCraftPalette() {
    }

    static int color(BlockNeighborhood block) {
        int index = -1;
        if (block.getBlockEntity() instanceof TrafficCraftColorBlockEntity entity) {
            index = entity.color();
        }
        if (index >= 0 && index < COLORS.length) {
            return COLORS[index];
        }
        return defaultColor(block.getBlockState().getFormatted());
    }

    static int defaultColor(String blockId) {
        return switch (blockId) {
            case "trafficcraft:guardrail" -> 0x828282;
            case "trafficcraft:traffic_cone" -> 0xD12725;
            case "trafficcraft:concrete_barrier" -> 0xABABAB;
            case "trafficcraft:reflector" -> 0xF9FFFE;
            default -> 0xFFFFFF;
        };
    }

    static boolean isPaintable(String id) {
        if (id == null || !id.startsWith("trafficcraft:")) {
            return false;
        }
        String path = id.substring("trafficcraft:".length());
        if (path.matches("(?:asphalt|concrete)_pattern_\\d+")
                || path.matches("(?:asphalt|concrete)_slope_pattern_\\d+")) {
            return true;
        }
        return switch (path) {
            case "concrete_barrier",
                 "street_sign",
                 "house_number_sign",
                 "traffic_light",
                 "guardrail",
                 "paint_bucket",
                 "traffic_cone",
                 "traffic_bollard",
                 "traffic_barrel",
                 "road_barrier_fence",
                 "reflector" -> true;
            default -> false;
        };
    }
}
