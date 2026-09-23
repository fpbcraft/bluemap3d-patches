package dev.duzo.bluemap3d.bake;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reproduces TrafficCraft's client BlockColor provider for moving BlueMap3D meshes.
 *
 * <p>TrafficCraft uses tintindex=0 on paintable model faces and stores PaintColor#index
 * in block-entity NBT under "color". A dedicated server has no client color provider, so
 * generic resource-model rendering otherwise falls back to the block map color.
 */
public final class TrafficCraftTintSource implements BlockModelSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/TrafficCraft");
    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();

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

    private final ResourcePackSource models;

    public TrafficCraftTintSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return quadsFor(state, null);
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state, CompoundTag metadata) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!isPaintable(id)) {
            return List.of();
        }

        Integer index = findColor(metadata, 0);
        int tint = index != null && index >= 0 && index < COLORS.length
                ? COLORS[index]
                : defaultColor(id);

        List<ModelQuad> quads = models.quadsForWithTint(state, tint);
        if (!quads.isEmpty() && TRACED.add(id)) {
            LOGGER.info(
                    "TRAFFICCRAFT-TINT block={} colorIndex={} tint=#{}, quads={}",
                    id,
                    index == null ? "<none>" : index,
                    String.format("%06X", tint),
                    quads.size());
        }
        return quads;
    }

    @Override
    public BufferedImage texture(String texture) {
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return models.occludes(state);
    }

    private static Integer findColor(CompoundTag tag, int depth) {
        if (tag == null || depth > 8) return null;
        if (tag.contains("color", Tag.TAG_INT)) {
            return tag.getInt("color");
        }
        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                Integer found = findColor(compound, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int defaultColor(String id) {
        return switch (id) {
            case "trafficcraft:guardrail" -> 0x828282;
            case "trafficcraft:traffic_cone" -> 0xD12725;
            case "trafficcraft:concrete_barrier" -> 0xABABAB;
            case "trafficcraft:reflector" -> 0xF9FFFE;
            default -> 0xFFFFFF;
        };
    }

    private static boolean isPaintable(String id) {
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
