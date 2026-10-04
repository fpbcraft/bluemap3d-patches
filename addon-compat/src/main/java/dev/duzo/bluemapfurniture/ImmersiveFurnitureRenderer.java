package dev.duzo.bluemapfurniture;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModel;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Element;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Face;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Renders Immersive Furniture's data-driven element geometry in static BlueMap tiles. */
public final class ImmersiveFurnitureRenderer implements BlockRenderer {

    static final Key RENDERER_KEY =
            new Key("bluemap_immersive_furniture", "furniture");

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final ResourcePack resourcePack;
    private final TextureGallery textureGallery;

    public ImmersiveFurnitureRenderer(
            ResourcePack resourcePack,
            TextureGallery textureGallery,
            RenderSettings renderSettings) {
        this.resourcePack = resourcePack;
        this.textureGallery = textureGallery;
    }

    static void register() {
        if (BlockRendererType.REGISTRY.get(RENDERER_KEY) != null) return;
        BlockRendererType.REGISTRY.register(new BlockRendererType.Impl(
                RENDERER_KEY,
                ImmersiveFurnitureRenderer::new));
    }

    @Override
    public void render(
            BlockNeighborhood block,
            Variant ignoredVariant,
            TileModelView tileModel,
            Color blockColor) {
        Object rawEntity = block.getBlockEntity();
        String blockStateText = block.getBlockState().toString();
        warnInfoOnce(
                blockStateText + "#render",
                "IMMERSIVE-FURNITURE render block=" + blockStateText
                        + " blockEntity="
                        + (rawEntity == null ? "<none>" : rawEntity.getClass().getName()));

        if (!(rawEntity instanceof ImmersiveFurnitureBlockEntity entity)) {
            warnOnce(
                    blockStateText + "#entity",
                    "Immersive Furniture renderer received unsupported/missing block entity: block="
                            + blockStateText
                            + " blockEntity="
                            + (rawEntity == null ? "<none>" : rawEntity.getClass().getName()));
            return;
        }

        warnInfoOnce(
                blockStateText + "#entity-data",
                "IMMERSIVE-FURNITURE entity-data block=" + blockStateText
                        + " Furniture=" + (entity.furniture() != null)
                        + " FurnitureHash="
                        + (entity.furnitureHash() == null ? "<none>" : entity.furnitureHash())
                        + " offsets="
                        + entity.subOffsetX() + ","
                        + entity.subOffsetY() + ","
                        + entity.subOffsetZ());

        ImmersiveFurnitureData.Definition definition =
                ImmersiveFurnitureRuntime.resolve(block, entity);
        if (definition == null || definition.isEmpty()) {
            warnOnce(
                    block.getBlockState().getFormatted() + "#data",
                    "Immersive Furniture data is unavailable for "
                            + block.getBlockState().getFormatted());
            return;
        }

        int state = "true".equals(block.getBlockState().getProperties().get("active")) ? 1 : 0;
        String facing = block.getBlockState().getProperties().getOrDefault("facing", "north");

        float offsetX = entity.subOffsetX() - 8F;
        float offsetY = entity.subOffsetY() - 8F;
        float offsetZ = entity.subOffsetZ() - 8F;

        int emitted = 0;
        for (ImmersiveFurnitureData.Element element : definition.elements()) {
            if (!element.visible(state)) continue;
            emitted += emitElement(
                    block,
                    tileModel,
                    element,
                    facing,
                    offsetX,
                    offsetY,
                    offsetZ);
        }

        if (emitted > 0) {
            blockColor.set(1f, 1f, 1f, 1f, true);
        }
    }

    private int emitElement(
            BlockNeighborhood block,
            TileModelView tileModel,
            ImmersiveFurnitureData.Element element,
            String facing,
            float offsetX,
            float offsetY,
            float offsetZ) {
        float minX = Math.min(element.from()[0], element.to()[0]);
        float minY = Math.min(element.from()[1], element.to()[1]);
        float minZ = Math.min(element.from()[2], element.to()[2]);
        float maxX = Math.max(element.from()[0], element.to()[0]);
        float maxY = Math.max(element.from()[1], element.to()[1]);
        float maxZ = Math.max(element.from()[2], element.to()[2]);

        if (Math.abs(maxX - minX) < 1.0e-5F
                || Math.abs(maxY - minY) < 1.0e-5F
                || Math.abs(maxZ - minZ) < 1.0e-5F) {
            return 0;
        }

        float[][] c = {
                p(minX,minY,minZ), p(minX,minY,maxZ),
                p(maxX,minY,minZ), p(maxX,minY,maxZ),
                p(minX,maxY,minZ), p(minX,maxY,maxZ),
                p(maxX,maxY,minZ), p(maxX,maxY,maxZ)
        };

        float centerX = (minX + maxX) * 0.5F;
        float centerY = (minY + maxY) * 0.5F;
        float centerZ = (minZ + maxZ) * 0.5F;
        for (float[] point : c) {
            rotateElement(
                    point,
                    centerX,
                    centerY,
                    centerZ,
                    element.axis(),
                    element.rotation());
            rotateFacing(point, facing);
            point[0] += offsetX;
            point[1] += offsetY;
            point[2] += offsetZ;
        }

        int emitted = 0;
        emitted += emitFace(block, tileModel, element, Direction.DOWN, c[0],c[2],c[3],c[1]);
        emitted += emitFace(block, tileModel, element, Direction.UP, c[5],c[7],c[6],c[4]);
        emitted += emitFace(block, tileModel, element, Direction.NORTH, c[2],c[0],c[4],c[6]);
        emitted += emitFace(block, tileModel, element, Direction.SOUTH, c[1],c[3],c[7],c[5]);
        emitted += emitFace(block, tileModel, element, Direction.WEST, c[0],c[1],c[5],c[4]);
        emitted += emitFace(block, tileModel, element, Direction.EAST, c[3],c[2],c[6],c[7]);
        return emitted;
    }

    private int emitFace(
            BlockNeighborhood block,
            TileModelView tileModel,
            ImmersiveFurnitureData.Element element,
            Direction face,
            float[] a,
            float[] b,
            float[] c,
            float[] d) {
        Appearance appearance = appearance(element.material(), face, block);
        if (appearance == null) return 0;

        float[] p = {
                a[0],a[1],a[2],
                b[0],b[1],b[2],
                c[0],c[1],c[2],
                d[0],d[1],d[2]
        };

        tileModel.initialize();
        tileModel.add(2);
        TileModel target = tileModel.getTileModel();
        int first = tileModel.getStart();
        int second = first + 1;

        target.setPositions(first,
                p[0]/16F,p[1]/16F,p[2]/16F,
                p[3]/16F,p[4]/16F,p[5]/16F,
                p[6]/16F,p[7]/16F,p[8]/16F);
        target.setPositions(second,
                p[0]/16F,p[1]/16F,p[2]/16F,
                p[6]/16F,p[7]/16F,p[8]/16F,
                p[9]/16F,p[10]/16F,p[11]/16F);

        target.setUvs(first, 0F,1F, 1F,1F, 1F,0F);
        target.setUvs(second, 0F,1F, 1F,0F, 0F,0F);
        target.setMaterialIndex(first, appearance.textureIndex());
        target.setMaterialIndex(second, appearance.textureIndex());
        target.setColor(first, 1F,1F,1F);
        target.setColor(second, 1F,1F,1F);

        LightData light = block.getLightData();
        int blockLight = Math.max(light.getBlockLight(), Math.min(15, element.emission()));
        target.setSunlight(first, light.getSkyLight());
        target.setSunlight(second, light.getSkyLight());
        target.setBlocklight(first, blockLight);
        target.setBlocklight(second, blockLight);
        target.setAOs(first, 1F,1F,1F);
        target.setAOs(second, 1F,1F,1F);
        return 1;
    }

    private Appearance appearance(
            String materialId,
            Direction wantedFace,
            BlockNeighborhood block) {
        String id = materialId == null || materialId.isBlank()
                ? "minecraft:oak_log"
                : materialId;
        BlockState materialState = new BlockState(id, Map.of());
        var stateResource = resourcePack.getBlockState(materialState);
        if (stateResource == null) return null;

        List<Variant> variants = new ArrayList<>(2);
        stateResource.forEach(
                materialState,
                block.getX(),
                block.getY(),
                block.getZ(),
                variants::add);
        if (variants.isEmpty()) return null;

        Model model = variants.getFirst().getModel().getResource(resourcePack::getModel);
        if (model == null || model.getElements() == null) return null;

        Face selected = null;
        for (Element modelElement : model.getElements()) {
            if (modelElement == null) continue;
            Face exact = modelElement.getFaces().get(wantedFace);
            if (exact != null) {
                selected = exact;
                break;
            }
            if (selected == null && !modelElement.getFaces().isEmpty()) {
                selected = modelElement.getFaces().values().iterator().next();
            }
        }
        if (selected == null) return null;

        ResourcePath<Texture> texture =
                selected.getTexture().getTexturePath(model.getTextures()::get);
        if (texture == null) texture = ResourcePack.MISSING_TEXTURE;
        return new Appearance(textureGallery.get(texture));
    }

    static void rotateElement(
            float[] point,
            float centerX,
            float centerY,
            float centerZ,
            ImmersiveFurnitureData.Axis axis,
            float degrees) {
        if (Math.abs(degrees) < 1.0e-6F) return;
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);

        double x = point[0] - centerX;
        double y = point[1] - centerY;
        double z = point[2] - centerZ;

        switch (axis) {
            case X -> {
                double ny = y * cos - z * sin;
                double nz = y * sin + z * cos;
                y = ny;
                z = nz;
            }
            case Y -> {
                double nx = x * cos - z * sin;
                double nz = x * sin + z * cos;
                x = nx;
                z = nz;
            }
            case Z -> {
                double nx = x * cos - y * sin;
                double ny = x * sin + y * cos;
                x = nx;
                y = ny;
            }
        }

        point[0] = (float) (x + centerX);
        point[1] = (float) (y + centerY);
        point[2] = (float) (z + centerZ);
    }

    static void rotateFacing(float[] point, String facing) {
        double degrees = switch (facing) {
            case "east" -> -90D;
            case "south" -> 180D;
            case "west" -> 90D;
            default -> 0D;
        };
        if (degrees == 0D) return;

        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x = point[0] - 8D;
        double z = point[2] - 8D;
        point[0] = (float) (x * cos - z * sin + 8D);
        point[2] = (float) (x * sin + z * cos + 8D);
    }

    private static float[] p(float x, float y, float z) {
        return new float[]{x,y,z};
    }

    private static void warnOnce(String key, String message) {
        if (WARNED.add(key)) Logger.global.logWarning(message);
    }

    private static void warnInfoOnce(String key, String message) {
        if (WARNED.add(key)) Logger.global.logInfo(message);
    }

    private record Appearance(int textureIndex) {
    }
}
