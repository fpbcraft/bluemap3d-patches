package dev.duzo.bluemaptrafficcraft;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModel;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType;
import de.bluecolored.bluemap.core.map.hires.block.ResourceModelRenderer;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders TrafficCraft's client-only traffic-sign image over the ordinary sign model.
 */
public final class TrafficCraftSignRenderer implements BlockRenderer {

    private static final Key RENDERER_KEY =
            new Key("bluemap_trafficcraft", "traffic_sign");
    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();

    private final TextureGallery textureGallery;
    private final ResourceModelRenderer delegate;

    public TrafficCraftSignRenderer(
            ResourcePack resourcePack,
            TextureGallery textureGallery,
            RenderSettings renderSettings) {
        this.textureGallery = textureGallery;
        this.delegate = new ResourceModelRenderer(resourcePack, textureGallery, renderSettings);
    }

    static void register() {
        BlockRendererType existing = BlockRendererType.REGISTRY.get(RENDERER_KEY);
        if (existing != null) return;

        BlockRendererType.REGISTRY.register(new BlockRendererType.Impl(
                RENDERER_KEY,
                TrafficCraftSignRenderer::new
        ));
    }

    @Override
    public void render(
            BlockNeighborhood block,
            Variant ignoredVariant,
            TileModelView tileModel,
            Color blockColor) {
        String id = block.getBlockState().getFormatted();
        var original = TrafficCraftSignSupport.original(id);
        if (original == null) return;

        original.forEach(
                block.getBlockState(),
                block.getX(),
                block.getY(),
                block.getZ(),
                variant -> delegate.render(block, variant, tileModel.initialize(), blockColor));

        if (!(block.getBlockEntity() instanceof TrafficCraftColorBlockEntity entity)) {
            return;
        }

        String signTexture = entity.signTexture();
        String shape = block.getBlockState().getProperties().getOrDefault("shape", "square");
        String facing = block.getBlockState().getProperties().getOrDefault("facing", "north");

        ResourcePath<Texture> front =
                TrafficCraftSignSupport.frontTexture(signTexture, shape);
        if (front == null) return;

        int frontIndex = textureGallery.get(front);
        if (frontIndex == 0) {
            if (TRACED.add("missing#" + signTexture)) {
                Logger.global.logWarning(String.format(
                        "TRAFFICCRAFT-SIGN texture not present in BlueMap gallery: %s (%s)",
                        signTexture, front.getFormatted()));
            }
            return;
        }

        float frontZ = "misc".equals(shape) ? 7f / 16f : 6.5f / 16f;
        emitPlane(block, tileModel, frontIndex, frontZ - 0.002f, facing, false);

        ResourcePath<Texture> back = TrafficCraftSignSupport.backTexture(signTexture);
        if ("misc".equals(shape) && back != null) {
            int backIndex = textureGallery.get(back);
            if (backIndex != 0) {
                emitPlane(block, tileModel, backIndex, 9f / 16f + 0.002f, facing, true);
            }
        }

        if (TRACED.add(signTexture)) {
            Logger.global.logInfo(String.format(
                    "TRAFFICCRAFT-SIGN block=%s texture=%s shape=%s facing=%s",
                    id, signTexture, shape, facing));
        }
    }

    private static void emitPlane(
            BlockNeighborhood block,
            TileModelView tileModel,
            int textureIndex,
            float z,
            String facing,
            boolean reverse) {
        float[] positions = reverse
                ? new float[] {
                        0f, 0f, z,
                        1f, 0f, z,
                        1f, 1f, z,
                        0f, 1f, z
                }
                : new float[] {
                        1f, 0f, z,
                        0f, 0f, z,
                        0f, 1f, z,
                        1f, 1f, z
                };

        rotateY(positions, rotation(facing));

        tileModel.initialize();
        tileModel.add(2);
        TileModel target = tileModel.getTileModel();
        int f1 = tileModel.getStart();
        int f2 = f1 + 1;

        target.setPositions(f1,
                positions[0], positions[1], positions[2],
                positions[3], positions[4], positions[5],
                positions[6], positions[7], positions[8]);
        target.setPositions(f2,
                positions[0], positions[1], positions[2],
                positions[6], positions[7], positions[8],
                positions[9], positions[10], positions[11]);

        target.setUvs(f1, 0f, 1f, 1f, 1f, 1f, 0f);
        target.setUvs(f2, 0f, 1f, 1f, 0f, 0f, 0f);

        target.setMaterialIndex(f1, textureIndex);
        target.setMaterialIndex(f2, textureIndex);
        target.setColor(f1, 1f, 1f, 1f);
        target.setColor(f2, 1f, 1f, 1f);

        LightData light = block.getLightData();
        target.setSunlight(f1, light.getSkyLight());
        target.setSunlight(f2, light.getSkyLight());
        target.setBlocklight(f1, light.getBlockLight());
        target.setBlocklight(f2, light.getBlockLight());
        target.setAOs(f1, 1f, 1f, 1f);
        target.setAOs(f2, 1f, 1f, 1f);
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
            double x = positions[i] - 0.5;
            double z = positions[i + 2] - 0.5;
            positions[i] = (float) (x * cos - z * sin + 0.5);
            positions[i + 2] = (float) (x * sin + z * cos + 0.5);
        }
    }
}
