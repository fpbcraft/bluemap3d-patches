package dev.duzo.bluemaptrafficcraft;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.BmMap;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.adapter.ResourcesGson;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.world.mca.MCAWorld;
import de.bluecolored.bluenbt.BlueNBT;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Loads TrafficCraft's server-side dynamic sign images into each BlueMap 5.7 texture
 * gallery before rendering starts, and routes traffic_sign through the overlay renderer.
 */
final class TrafficCraftSignSupport {

    private static final Map<String,
            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>
            ORIGINALS = new ConcurrentHashMap<>();

    private static final String SIGN_BLOCK = "trafficcraft:traffic_sign";

    private static final String DISPATCH_JSON = """
            {
              "variants": {
                "": {
                  "renderer": "bluemap_trafficcraft:traffic_sign",
                  "model": "trafficcraft:block/sign/square"
                }
              }
            }
            """;

    private TrafficCraftSignSupport() {
    }

    static void install(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            Logger.global.logWarning(String.format(
                    "TrafficCraft sign compatibility requires BlueMap 5.7 common API implementation; got %s",
                    api.getClass().getName()));
            return;
        }

        ResourcePack resourcePack = impl.blueMapService().getResourcePack();
        if (resourcePack == null) {
            Logger.global.logWarning("TrafficCraft sign compatibility could not access BlueMap resource pack");
            return;
        }

        int textures = registerCustomTextures(impl, resourcePack);
        boolean routed = routeTrafficSign(resourcePack);

        Logger.global.logInfo(String.format(
                "TrafficCraft sign compatibility ready: %s custom texture(s), rendererRouted=%s",
                textures, routed));
    }

    static de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState
    original(String id) {
        return ORIGINALS.get(id);
    }

    static ResourcePath<Texture> frontTexture(String signTexture, String shape) {
        if (signTexture == null || signTexture.isBlank() || "empty".equals(signTexture)) {
            return null;
        }

        BuiltIn builtIn = BuiltIn.decode(signTexture);
        if (builtIn != null) {
            String resolvedShape = builtIn.shapeName() != null ? builtIn.shapeName() : shape;
            if (resolvedShape == null || resolvedShape.isBlank()) return null;
            return new ResourcePath<>(
                    "trafficcraft",
                    "block/sign/" + resolvedShape + "/" + resolvedShape + builtIn.id());
        }

        return new ResourcePath<>("bluemap_trafficcraft", "sign/" + signTexture);
    }

    static ResourcePath<Texture> backTexture(String signTexture) {
        if (signTexture == null) return null;
        BuiltIn builtIn = BuiltIn.decode(signTexture);
        if (builtIn != null) {
            return builtIn.shape() == 11
                    ? new ResourcePath<>("bluemap_trafficcraft", "sign_builtin_bg/" + builtIn.id())
                    : null;
        }
        return new ResourcePath<>("bluemap_trafficcraft", "sign/" + signTexture + "_bg");
    }

    private static int registerCustomTextures(BlueMapAPIImpl impl, ResourcePack resourcePack) {
        Map<Path, Map<String, LoadedSign>> byWorld = new HashMap<>();
        int[] loaded = {0};

        for (BmMap map : impl.blueMapService().getMaps().values()) {
            if (!(map.getWorld() instanceof MCAWorld world)) {
                continue;
            }

            Path root = world.getWorldFolder().toAbsolutePath().normalize();
            Map<String, LoadedSign> signs = byWorld.computeIfAbsent(
                    root,
                    ignored -> loadSigns(root.resolve("data").resolve("trafficcraft_signs"), resourcePack));

            for (Map.Entry<String, LoadedSign> entry : signs.entrySet()) {
                register(map, new ResourcePath<>("bluemap_trafficcraft", "sign/" + entry.getKey()),
                        entry.getValue().front());
                if (entry.getValue().back() != null) {
                    register(map,
                            new ResourcePath<>("bluemap_trafficcraft", "sign/" + entry.getKey() + "_bg"),
                            entry.getValue().back());
                }
            }

            registerBuiltInMiscBackgrounds(map, resourcePack);

            try (OutputStream out = map.getStorage().textures().write()) {
                map.getTextureGallery().writeTexturesFile(out);
            } catch (IOException error) {
                Logger.global.logError(
                        "Failed to persist TrafficCraft sign textures for map '" + map.getId() + "'",
                        error);
            }

            loaded[0] = Math.max(loaded[0], signs.size());
        }

        return loaded[0];
    }

    private static void registerBuiltInMiscBackgrounds(BmMap map, ResourcePack resourcePack) {
        BufferedImage blank = readTexture(
                resourcePack.getTexture(new ResourcePath<>("trafficcraft", "block/sign/blank")));
        if (blank == null) return;

        String prefix = "trafficcraft:block/sign/misc/misc";
        for (ResourcePath<Texture> sourcePath : resourcePack.getTextures().keySet()) {
            String formatted = sourcePath.getFormatted();
            if (!formatted.startsWith(prefix)) continue;

            String id = formatted.substring(prefix.length());
            if (id.isBlank() || !id.chars().allMatch(Character::isDigit)) continue;

            BufferedImage front = readTexture(resourcePack.getTexture(sourcePath));
            if (front == null) continue;

            register(
                    map,
                    new ResourcePath<>("bluemap_trafficcraft", "sign_builtin_bg/" + id),
                    miscBackground(front, blank));
        }
    }

    private static void register(BmMap map, ResourcePath<Texture> path, BufferedImage image) {
        try {
            Texture texture = Texture.from(path, image);
            path.setResource(texture);
            map.getTextureGallery().put(path);
        } catch (IOException error) {
            Logger.global.logError("Failed to register TrafficCraft sign texture " + path, error);
        }
    }

    private static Map<String, LoadedSign> loadSigns(Path directory, ResourcePack resourcePack) {
        Map<String, LoadedSign> out = new HashMap<>();
        if (!Files.isDirectory(directory)) {
            return out;
        }

        BufferedImage blank = readTexture(
                resourcePack.getTexture(new ResourcePath<>("trafficcraft", "block/sign/blank")));

        try (var files = Files.list(directory)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".nbt"))
                    .toList()) {
                String name = file.getFileName().toString();
                String id = name.substring(0, name.length() - 4);
                try {
                    TrafficCraftSignTextureFile data;
                    try (var in = new GZIPInputStream(Files.newInputStream(file))) {
                        data = new BlueNBT().read(in, TrafficCraftSignTextureFile.class);
                    }

                    if (data == null || data.data() == null || data.data().length == 0) continue;
                    BufferedImage front = ImageIO.read(new ByteArrayInputStream(data.data()));
                    if (front == null) continue;

                    BufferedImage back = data.shape() == 11 && blank != null
                            ? miscBackground(front, blank)
                            : null;
                    out.put(id, new LoadedSign(front, back));
                } catch (Exception error) {
                    Logger.global.logWarning(String.format(
                            "Could not load TrafficCraft sign texture %s: %s",
                            file, error));
                }
            }
        } catch (IOException error) {
            Logger.global.logError("Could not scan TrafficCraft sign texture directory " + directory, error);
        }

        return out;
    }

    private static BufferedImage readTexture(Texture texture) {
        if (texture == null || texture.getTexture() == null) return null;
        String data = texture.getTexture();
        int comma = data.indexOf(',');
        if (comma < 0 || comma == data.length() - 1) return null;
        try {
            return ImageIO.read(new ByteArrayInputStream(
                    Base64.getDecoder().decode(data.substring(comma + 1))));
        } catch (IOException | IllegalArgumentException error) {
            return null;
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

    @SuppressWarnings("unchecked")
    private static boolean routeTrafficSign(ResourcePack resourcePack) {
        try {
            Field statesField = ResourcePack.class.getDeclaredField("blockStates");
            Field pathsField = ResourcePack.class.getDeclaredField("blockStatePaths");
            statesField.setAccessible(true);
            pathsField.setAccessible(true);

            Map<ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>,
                    de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState> states =
                    (Map<ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>,
                            de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>)
                            statesField.get(resourcePack);

            Map<String, ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>
                    paths =
                    (Map<String, ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState>>)
                            pathsField.get(resourcePack);

            ResourcePath<de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState> path =
                    paths.get(SIGN_BLOCK);
            if (path == null) {
                Logger.global.logWarning("TrafficCraft traffic_sign blockstate is not loaded");
                return false;
            }

            var original = states.get(path);
            if (original == null) return false;

            ORIGINALS.put(SIGN_BLOCK, original);
            var dispatch = ResourcesGson.INSTANCE.fromJson(
                    new StringReader(DISPATCH_JSON),
                    de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.BlockState.class);
            states.put(path, dispatch);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError("Failed to route TrafficCraft traffic_sign renderer", error);
            return false;
        }
    }

    private record LoadedSign(BufferedImage front, BufferedImage back) {
    }

    private record BuiltIn(int shape, int id) {
        static BuiltIn decode(String code) {
            if (code == null || !code.startsWith("builtIn_")) return null;
            try {
                String location = code.split("@", 2)[0];
                String[] parts = location.split("_");
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
