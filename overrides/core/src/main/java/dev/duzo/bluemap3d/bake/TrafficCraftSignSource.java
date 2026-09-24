package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reconstructs TrafficCraft's client-only traffic-sign BER for moving BlueMap3D volumes.
 *
 * <p>The normal block model supplies the pole/backing. The selected sign artwork lives
 * in block-entity NBT as SignTexture and is rendered by TrafficCraft as a second quad.
 * Custom images are persisted under world/data/trafficcraft_signs/&lt;id&gt;.nbt.
 */
public final class TrafficCraftSignSource implements BlockModelSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/TrafficCraftSigns");
    private static final String SIGN_BLOCK = "trafficcraft:traffic_sign";
    private static final String CUSTOM_PREFIX = "bluemap3d:trafficcraft_sign/";
    private static final String BUILTIN_BG_PREFIX = "bluemap3d:trafficcraft_sign_builtin_bg/";

    private final ResourcePackSource models;
    private final Map<String, BufferedImage> dynamicTextures = new ConcurrentHashMap<>();
    private final Set<String> failedTextures = ConcurrentHashMap.newKeySet();
    private final Set<String> traced = ConcurrentHashMap.newKeySet();

    public TrafficCraftSignSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return quadsFor(state, null);
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state, CompoundTag metadata) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!SIGN_BLOCK.equals(id)) {
            return List.of();
        }

        List<ModelQuad> base = models.quadsForWithTint(state, 0xFFFFFF);
        String signTexture = findString(metadata, "SignTexture", 0);
        if (signTexture == null || signTexture.isBlank() || "empty".equals(signTexture)) {
            return base;
        }

        String shape = property(state, "shape", "square");
        String facing = property(state, "facing", "north");

        BuiltIn builtIn = BuiltIn.decode(signTexture);
        String frontTexture;
        if (builtIn != null) {
            String resolvedShape = builtIn.shapeName() != null ? builtIn.shapeName() : shape;
            frontTexture = "trafficcraft:block/sign/" + resolvedShape + "/" + resolvedShape + builtIn.id();
        } else {
            frontTexture = CUSTOM_PREFIX + signTexture;
        }

        List<ModelQuad> out = new ArrayList<>(base.size() + 2);
        out.addAll(base);

        float frontZ = "misc".equals(shape) ? 7f : 6.5f;
        out.add(plane(frontZ - 0.032f, facing, Direction.NORTH, frontTexture));

        if ("misc".equals(shape)) {
            String backTexture = builtIn == null
                    ? CUSTOM_PREFIX + signTexture + "_bg"
                    : BUILTIN_BG_PREFIX + builtIn.id();
            out.add(plane(9f + 0.032f, facing, Direction.SOUTH, backTexture));
        }

        if (traced.add(signTexture)) {
            LOGGER.info(
                    "TRAFFICCRAFT-SIGN moving texture={} shape={} facing={} baseQuads={} totalQuads={}",
                    signTexture, shape, facing, base.size(), out.size());
        }
        return List.copyOf(out);
    }

    @Override
    public BufferedImage texture(String texture) {
        if (texture == null) return null;

        if (texture.startsWith(CUSTOM_PREFIX)) {
            return dynamicTexture(texture);
        }
        if (texture.startsWith(BUILTIN_BG_PREFIX)) {
            return builtInBackground(texture.substring(BUILTIN_BG_PREFIX.length()));
        }
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return models.occludes(state);
    }

    private BufferedImage dynamicTexture(String texture) {
        BufferedImage cached = dynamicTextures.get(texture);
        if (cached != null || failedTextures.contains(texture)) return cached;

        String key = texture.substring(CUSTOM_PREFIX.length());
        boolean background = key.endsWith("_bg");
        String id = background ? key.substring(0, key.length() - 3) : key;

        BufferedImage front = loadCustom(id);
        if (front == null) {
            failedTextures.add(texture);
            return null;
        }

        if (!background) {
            dynamicTextures.put(texture, front);
            return front;
        }

        BufferedImage blank = models.texture("trafficcraft:block/sign/blank");
        BufferedImage bg = blank == null ? null : miscBackground(front, blank);
        if (bg == null) {
            failedTextures.add(texture);
            return null;
        }
        dynamicTextures.put(texture, bg);
        return bg;
    }

    private BufferedImage builtInBackground(String idText) {
        String cacheKey = BUILTIN_BG_PREFIX + idText;
        BufferedImage cached = dynamicTextures.get(cacheKey);
        if (cached != null || failedTextures.contains(cacheKey)) return cached;

        try {
            int id = Integer.parseInt(idText);
            BufferedImage front = models.texture("trafficcraft:block/sign/misc/misc" + id);
            BufferedImage blank = models.texture("trafficcraft:block/sign/blank");
            if (front == null || blank == null) {
                failedTextures.add(cacheKey);
                return null;
            }
            BufferedImage bg = miscBackground(front, blank);
            dynamicTextures.put(cacheKey, bg);
            return bg;
        } catch (RuntimeException error) {
            failedTextures.add(cacheKey);
            return null;
        }
    }

    private BufferedImage loadCustom(String id) {
        Path directory = signDirectory();
        if (directory == null) return null;

        Path file = directory.resolve(id + ".nbt");
        if (!Files.isRegularFile(file)) {
            LOGGER.debug("TrafficCraft sign texture file not found: {}", file);
            return null;
        }

        try {
            CompoundTag nbt = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            byte[] png = nbt.getByteArray("Data");
            if (png.length == 0) return null;
            return ImageIO.read(new ByteArrayInputStream(png));
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Could not load TrafficCraft sign texture {}: {}", file, error.toString());
            return null;
        }
    }

    private static Path signDirectory() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            return server.getWorldPath(new LevelResource("data/trafficcraft_signs"));
        }

        // Startup-order fallback. BlueMap3D normally bakes after the server exists, but
        // checking the conventional world folders also keeps offline/test environments useful.
        Path cwd = Path.of("").toAbsolutePath().normalize();
        for (String candidate : List.of("world", ".")) {
            Path path = cwd.resolve(candidate).normalize().resolve("data").resolve("trafficcraft_signs");
            if (Files.isDirectory(path)) return path;
        }
        return null;
    }

    private static ModelQuad plane(
            float z,
            String facing,
            Direction face,
            String texture) {
        float[] from = {0f, 0f, z};
        float[] to = {16f, 16f, z};
        float[] positions = ResourcePackSource.faceCorners(from, to, face);
        rotateY(positions, rotation(facing));
        return new ModelQuad(
                null,
                null,
                positions,
                ResourcePackSource.uvCorners(new float[]{0f, 0f, 16f, 16f}, 0),
                texture,
                0xFFFFFF);
    }

    private static int rotation(String facing) {
        return switch (facing) {
            case "east" -> 90;
            case "south" -> 180;
            case "west" -> 270;
            default -> 0;
        };
    }

    private static void rotateY(float[] positions, int degrees) {
        if (degrees == 0) return;
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        for (int i = 0; i < positions.length; i += 3) {
            double x = positions[i] - 8.0;
            double z = positions[i + 2] - 8.0;
            positions[i] = (float) (x * cos - z * sin + 8.0);
            positions[i + 2] = (float) (x * sin + z * cos + 8.0);
        }
    }

    private static BufferedImage miscBackground(BufferedImage original, BufferedImage blank) {
        int width = Math.min(blank.getWidth(), original.getWidth());
        int height = Math.min(blank.getHeight(), original.getHeight());

        BufferedImage out = new BufferedImage(blank.getWidth(), blank.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = out.createGraphics();
        graphics.drawImage(blank, 0, 0, null);
        graphics.dispose();

        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                if (((original.getRGB(x, y) >>> 24) & 0xFF) != 0) continue;
                out.setRGB(width - 1 - x, y, 0);
            }
        }
        return out;
    }

    private static String findString(CompoundTag tag, String key, int depth) {
        if (tag == null || depth > 8) return null;
        if (tag.contains(key)) {
            String value = tag.getString(key);
            if (value != null && !value.isBlank()) return value;
        }
        for (String childKey : tag.getAllKeys()) {
            if (tag.get(childKey) instanceof CompoundTag child) {
                String found = findString(child, key, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String property(BlockState state, String wanted, String fallback) {
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            if (!wanted.equals(entry.getKey().getName())) continue;
            return nameOf(entry.getKey(), entry.getValue());
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String nameOf(Property<?> property, Comparable<?> value) {
        return ((Property<T>) property).getName((T) value);
    }

    private record BuiltIn(int shape, int id) {
        static BuiltIn decode(String code) {
            if (code == null || !code.startsWith("builtIn_")) return null;
            try {
                String[] parts = code.split("@", 2)[0].split("_");
                if (parts.length < 3) return null;
                return new BuiltIn(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            } catch (RuntimeException error) {
                return null;
            }
        }

        String shapeName() {
            return switch (shape) {
                case 0 -> "circle";
                case 1 -> "square";
                case 2 -> "diamond";
                case 3 -> "triangle";
                case 4 -> "triangle_down";
                case 5 -> "rectangle";
                case 6 -> "rectangle_small";
                case 7 -> "rectangle_horizontal";
                case 8 -> "small_upper";
                case 9 -> "small_lower";
                case 10 -> "octagon";
                case 11 -> "misc";
                default -> null;
            };
        }
    }
}
