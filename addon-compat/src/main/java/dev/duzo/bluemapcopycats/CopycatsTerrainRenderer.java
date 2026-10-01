package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModel;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.resources.BlockColorCalculatorFactory;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Element;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Face;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import de.bluecolored.bluemap.core.logger.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BlueMap 5.7 static-terrain renderer for the Copycats+ procedural blocks that are
 * reconstructed by CopycatsSpecialSource when they are moving inside BlueMap3D objects.
 *
 * <p>This renderer is intentionally independent from Copycats+ classes. BlueMap reads the
 * chunk NBT itself, so the only stable inputs here are BlueMap's BlockState and the retained
 * block-entity compounds.
 */
public final class CopycatsTerrainRenderer implements BlockRenderer {

    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();

    private final ResourcePack resourcePack;
    private final TextureGallery textureGallery;
    private final BlockColorCalculatorFactory.BlockColorCalculator blockColorCalculator;

    private BlockNeighborhood block;
    private TileModelView tileModel;

    public CopycatsTerrainRenderer(
            ResourcePack resourcePack,
            TextureGallery textureGallery,
            RenderSettings renderSettings) {
        this.resourcePack = resourcePack;
        this.textureGallery = textureGallery;
        this.blockColorCalculator = resourcePack.getColorCalculatorFactory().createCalculator();
    }

    @Override
    public void render(
            BlockNeighborhood block,
            Variant ignoredVariant,
            TileModelView tileModel,
            Color blockColor) {
        this.block = block;
        this.tileModel = tileModel;

        String id = block.getBlockState().getFormatted();
        if (!(block.getBlockEntity() instanceof CopycatsTerrainBlockEntity entity)) {
            if (TRACED.add(id + "#missing-entity")) {
                Logger.global.logWarning(String.format("STATIC block=%s has no retained Copycats block entity (actual=%s)",
                        id,
                        block.getBlockEntity() == null
                                ? "<null>"
                                : block.getBlockEntity().getClass().getName()));
            }
            return;
        }

        List<Quad> quads = switch (id) {
            case "copycats:copycat_byte" -> byteQuads(entity);
            case "copycats:copycat_vertical_half_layer" -> verticalHalfLayer(entity);
            case "copycats:copycat_flat_pane" -> flatPane(entity);
            case "copycats:copycat_vertical_stairs" -> verticalStairs(entity);
            case "copycats:copycat_slope_layer" -> slopeLayer(entity);
            case "copycats:copycat_slab" -> copycatsSlab(entity);
            case "copycats:copycat_board" -> board(entity);
            case "copycats:copycat_vertical_slice" -> verticalSlice(entity);
            case "copycats:copycat_slice" -> slice(entity);
            case "copycats:copycat_corner_slice" -> cornerSlice(entity);
            case "copycats:copycat_layer" -> layer(entity);
            case "copycats:copycat_half_layer" -> halfLayer(entity);
            case "copycats:copycat_stacked_half_layer" -> stackedHalfLayer(entity);
            case "copycats:copycat_half_panel" -> halfPanel(entity);
            case "copycats:copycat_vertical_step" -> verticalStep(entity);
            case "copycats:copycat_beam" -> beam(entity);
            case "copycats:copycat_byte_panel" -> bytePanel(entity);
            case "copycats:copycat_wooden_pressure_plate",
                 "copycats:copycat_stone_pressure_plate",
                 "copycats:copycat_heavy_weighted_pressure_plate",
                 "copycats:copycat_light_weighted_pressure_plate" -> pressurePlate(entity);
            case "create_connected:copycat_slab" -> connectedSlab(entity);
            default -> templateQuads(entity, id);
        };

        int renderStart = tileModel.getStart();
        int emitted = 0;
        for (Quad quad : quads) {
            if (emit(quad)) emitted++;
        }
        tileModel.initialize(renderStart);

        if (emitted > 0) {
            blockColor.set(1f, 1f, 1f, 1f, true);
        }

        if (TRACED.add(id)) {
            Logger.global.logInfo(String.format("STATIC block=%s state=%s entity=%s geometryQuads=%s emittedQuads=%s",
                    id,
                    block.getBlockState(),
                    entity.getId(),
                    quads.size(),
                    emitted));
        }
    }

    private List<Quad> byteQuads(CopycatsTerrainBlockEntity entity) {
        List<Quad> out = new ArrayList<>();
        addByte(out, entity, "bottom_northwest", 0, 0, 0);
        addByte(out, entity, "bottom_northeast", 8, 0, 0);
        addByte(out, entity, "bottom_southwest", 0, 0, 8);
        addByte(out, entity, "bottom_southeast", 8, 0, 8);
        addByte(out, entity, "top_northwest", 0, 8, 0);
        addByte(out, entity, "top_northeast", 8, 8, 0);
        addByte(out, entity, "top_southwest", 0, 8, 8);
        addByte(out, entity, "top_southeast", 8, 8, 8);
        return out;
    }

    private void addByte(
            List<Quad> out,
            CopycatsTerrainBlockEntity entity,
            String key,
            float x,
            float y,
            float z) {
        if (!propertyBool(key)) return;
        Material material = materialFor(entity, key);
        if (!usable(material)) return;
        cuboid(out, new Transform(), x, y, z, x + 8, y + 8, z + 8, material);
    }

    private List<Quad> verticalHalfLayer(CopycatsTerrainBlockEntity entity) {
        List<Quad> out = new ArrayList<>();
        int positive = propertyInt("positive_layers");
        int negative = propertyInt("negative_layers");
        int rot = yRotation(property("facing"));

        if (positive > 0) {
            Material material = materialFor(entity, "positive_layers");
            if (usable(material)) {
                addHalfLayer(out, material, positive, new Transform().rotateY(rot + 180));
            }
        }
        if (negative > 0) {
            Material material = materialFor(entity, "negative_layers");
            if (usable(material)) {
                addHalfLayer(out, material, negative,
                        new Transform().flipX(true).rotateY(rot + 180));
            }
        }
        return out;
    }

    private void addHalfLayer(
            List<Quad> out,
            Material material,
            int layer,
            Transform transform) {
        float l = Math.max(0, Math.min(8, layer));
        if (l <= 0) return;
        piece(out, transform, 0, 0, 0, 0, 0, 0, 4, 16, l, material);
        piece(out, transform, 0, 0, l, 0, 0, 16 - l, 4, 16, 16, material);
        piece(out, transform, 4, 0, 0, 12, 0, 0, 16, 16, l, material);
        piece(out, transform, 4, 0, l, 12, 0, 16 - l, 16, 16, 16, material);
    }

    private List<Quad> flatPane(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        Transform transform = new Transform();
        String axis = property("axis");
        if ("z".equals(axis)) transform.rotateX(90);
        if ("x".equals(axis)) transform.rotateZ(90);

        List<Quad> out = new ArrayList<>();
        cuboid(out, transform, 0, 7, 0, 16, 8, 16, material);
        cuboid(out, transform, 0, 8, 0, 16, 9, 16, material);
        return out;
    }

    private List<Quad> verticalStairs(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        int facing = yRotation(property("facing"));
        boolean right = "right".equals(property("side"));
        String shape = property("vertical_stair_shape");

        Transform transform = new Transform().rotateX(90).rotateZ(90).flipX(right);
        if (shape.endsWith("_top")) transform.flipY(true);
        transform.rotateY(facing);

        List<Quad> out = new ArrayList<>();
        if ("straight".equals(shape)) {
            stairStraight(out, transform, material);
        } else if (shape.startsWith("inner_")) {
            stairInner(out, transform, material);
        } else if (shape.startsWith("outer_")) {
            stairOuter(out, transform, material);
        }
        return out;
    }

    private void stairStraight(List<Quad> out, Transform t, Material m) {
        piece(out,t,0,0,0,  0,0,0,   16,4,8,m);
        piece(out,t,0,4,0,  0,12,0,  16,16,8,m);
        piece(out,t,0,0,8,  0,0,8,   16,8,16,m);
        piece(out,t,0,8,8,  0,8,0,   16,16,4,m);
        piece(out,t,0,8,12, 0,8,12,  16,16,16,m);
    }

    private void stairInner(List<Quad> out, Transform t, Material m) {
        piece(out,t,0,0,0,   0,0,0,   8,4,8,m);
        piece(out,t,0,4,0,   0,12,0,  8,16,8,m);
        piece(out,t,0,0,8,   0,0,8,   16,8,16,m);
        piece(out,t,8,8,8,   8,8,8,   16,16,16,m);
        piece(out,t,0,8,12,  0,8,12,  8,16,16,m);
        piece(out,t,0,8,8,   0,8,0,   8,16,4,m);
        piece(out,t,12,8,0,  12,8,0,  16,16,8,m);
        piece(out,t,8,8,0,   0,8,0,   4,16,8,m);
        piece(out,t,8,0,0,   8,0,0,   16,8,8,m);
    }

    private void stairOuter(List<Quad> out, Transform t, Material m) {
        piece(out,t,0,0,0,   0,0,0,   8,4,16,m);
        piece(out,t,0,4,0,   0,12,0,  8,16,16,m);
        piece(out,t,8,0,0,   8,0,0,   16,4,8,m);
        piece(out,t,8,4,0,   8,12,0,  16,16,8,m);
        piece(out,t,8,0,8,   8,0,8,   16,8,16,m);
        piece(out,t,12,8,12, 12,8,12, 16,16,16,m);
        piece(out,t,8,8,12,  0,8,12,  4,16,16,m);
        piece(out,t,12,8,8,  12,8,0,  16,16,4,m);
        piece(out,t,8,8,8,   0,8,0,   4,16,4,m);
    }

    private List<Quad> copycatsSlab(CopycatsTerrainBlockEntity entity) {
        String type = property("type");
        String axis = property("axis");
        List<Quad> out = new ArrayList<>();

        if (!"top".equals(type)) {
            Material bottom = materialFor(entity, "bottom");
            if (usable(bottom)) addSlabHalf(out, axis, true, bottom);
        }
        if (!"bottom".equals(type)) {
            Material top = materialFor(entity, "top");
            if (usable(top)) addSlabHalf(out, axis, false, top);
        }
        return out;
    }

    private List<Quad> connectedSlab(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        String type = property("type");
        String axis = property("axis");
        List<Quad> out = new ArrayList<>();
        if (!"top".equals(type)) addSlabHalf(out, axis, true, material);
        if (!"bottom".equals(type)) addSlabHalf(out, axis, false, material);
        return out;
    }

    private void addSlabHalf(
            List<Quad> out,
            String axis,
            boolean bottomPart,
            Material material) {
        if ("y".equals(axis)) {
            Transform t = new Transform().flipY(!bottomPart);
            cuboid(out, t, 0, 0, 0, 16, 4, 16, material);
            cuboid(out, t, 0, 4, 0, 16, 8, 16, material);
            return;
        }

        String facing;
        if ("x".equals(axis)) {
            facing = bottomPart ? "east" : "west";
        } else {
            facing = bottomPart ? "south" : "north";
        }
        Transform t = new Transform().rotateY(yRotation(facing));
        cuboid(out, t, 0, 0, 0, 16, 16, 4, material);
        cuboid(out, t, 0, 0, 4, 16, 16, 8, material);
    }

    private List<Quad> slopeLayer(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        int layer = propertyInt("layers");
        if (layer <= 0) return List.of();
        float minHeight = layer <= 4 ? 0 : (layer - 4) * 4f;
        float maxHeight = layer <= 4 ? layer * 4f : 16f;

        Transform transform = new Transform()
                .rotateY(yRotation(property("facing")))
                .flipY("top".equals(property("half")));

        List<Quad> out = new ArrayList<>();
        quad(out, transform, Direction.DOWN, material,
                p(0,0,0), p(16,0,0), p(16,0,16), p(0,0,16));
        if (minHeight > 0.0001f) {
            quad(out, transform, Direction.NORTH, material,
                    p(0,0,0), p(0,minHeight,0), p(16,minHeight,0), p(16,0,0));
        }
        quad(out, transform, Direction.SOUTH, material,
                p(16,0,16), p(16,maxHeight,16), p(0,maxHeight,16), p(0,0,16));
        quad(out, transform, Direction.WEST, material,
                p(0,0,16), p(0,maxHeight,16), p(0,minHeight,0), p(0,0,0));
        quad(out, transform, Direction.EAST, material,
                p(16,0,0), p(16,minHeight,0), p(16,maxHeight,16), p(16,0,16));
        quad(out, transform, Direction.UP, material,
                p(0,minHeight,0), p(0,maxHeight,16), p(16,maxHeight,16), p(16,minHeight,0));
        return out;
    }

    private List<Quad> board(CopycatsTerrainBlockEntity entity) {
        List<Quad> out = new ArrayList<>();
        for (Direction face : Direction.values()) {
            String key = face.name().toLowerCase(java.util.Locale.ROOT);
            if (!propertyBool(key)) continue;
            Material material = materialFor(entity, key);
            if (!usable(material)) continue;

            switch (face) {
                case DOWN -> cuboid(out, new Transform(), 0, 0, 0, 16, 1, 16, material);
                case UP -> cuboid(out, new Transform(), 0, 15, 0, 16, 16, 16, material);
                case NORTH -> cuboid(out, new Transform(), 0, 0, 0, 16, 16, 1, material);
                case SOUTH -> cuboid(out, new Transform(), 0, 0, 15, 16, 16, 16, material);
                case WEST -> cuboid(out, new Transform(), 0, 0, 0, 1, 16, 16, material);
                case EAST -> cuboid(out, new Transform(), 15, 0, 0, 16, 16, 16, material);
            }
        }
        return out;
    }

    private List<Quad> verticalSlice(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        float layers = Math.max(1, Math.min(8, propertyInt("layers")));
        float size = layers * 2f;
        Transform t = new Transform().rotateY(yRotation(property("facing")));
        List<Quad> out = new ArrayList<>();
        cuboid(out, t, 16 - size, 0, 16 - size, 16, 16, 16, material);
        return out;
    }

    private List<Quad> slice(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        float layers = Math.max(1, Math.min(8, propertyInt("layers")));
        float size = layers * 2f;
        Transform t = new Transform()
                .rotateY(yRotation(property("facing")))
                .flipY("top".equals(property("half")));
        List<Quad> out = new ArrayList<>();
        cuboid(out, t, 0, 0, 16 - size, 16, size, 16, material);
        return out;
    }

    private List<Quad> cornerSlice(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        float layers = Math.max(1, Math.min(8, propertyInt("layers")));
        float size = layers * 2f;
        Transform t = new Transform()
                .rotateY(yRotation(property("facing")))
                .flipY("top".equals(property("half")));
        List<Quad> out = new ArrayList<>();
        cuboid(out, t, 16 - size, 0, 16 - size, 16, size, 16, material);
        return out;
    }

    private List<Quad> layer(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        float layers = Math.max(1, Math.min(8, propertyInt("layers")));
        float size = layers * 2f;
        String facing = property("facing");
        List<Quad> out = new ArrayList<>();

        if ("up".equals(facing) || "down".equals(facing)) {
            Transform t = new Transform().flipY("down".equals(facing));
            cuboid(out, t, 0, 0, 0, 16, size, 16, material);
        } else {
            Transform t = new Transform().rotateY(yRotation(facing));
            cuboid(out, t, 0, 0, 0, 16, 16, size, material);
        }
        return out;
    }

    private List<Quad> halfLayer(CopycatsTerrainBlockEntity entity) {
        int positive = propertyInt("positive_layers");
        int negative = propertyInt("negative_layers");
        int rot = "z".equals(property("axis")) ? 90 : 0;
        boolean top = "top".equals(property("half"));
        List<Quad> out = new ArrayList<>();

        if (negative > 0) {
            Material material = materialFor(entity, "negative_layers");
            if (usable(material)) {
                float size = Math.min(8, negative) * 2f;
                Transform t = new Transform().rotateY(rot).flipY(top);
                cuboid(out, t, 0, 0, 0, 8, size, 16, material);
            }
        }
        if (positive > 0) {
            Material material = materialFor(entity, "positive_layers");
            if (usable(material)) {
                float size = Math.min(8, positive) * 2f;
                Transform t = new Transform().rotateY(rot + 180).flipY(top);
                cuboid(out, t, 0, 0, 0, 8, size, 16, material);
            }
        }
        return out;
    }

    private List<Quad> stackedHalfLayer(CopycatsTerrainBlockEntity entity) {
        int positive = propertyInt("positive_layers");
        int negative = propertyInt("negative_layers");
        int rot = yRotation(property("facing")) + 180;
        List<Quad> out = new ArrayList<>();

        if (negative > 0) {
            Material material = materialFor(entity, "negative_layers");
            if (usable(material)) {
                float size = Math.min(8, negative) * 2f;
                cuboid(out, new Transform().rotateY(rot),
                        0, 0, 0, 16, 8, size, material);
            }
        }
        if (positive > 0) {
            Material material = materialFor(entity, "positive_layers");
            if (usable(material)) {
                float size = Math.min(8, positive) * 2f;
                cuboid(out, new Transform().flipY(true).rotateY(rot),
                        0, 0, 0, 16, 8, size, material);
            }
        }
        return out;
    }

    private List<Quad> halfPanel(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        String facing = property("facing");
        String offset = property("offset");
        List<Quad> out = new ArrayList<>();

        if ("up".equals(facing) || "down".equals(facing)) {
            Transform t = new Transform()
                    .rotateY(yRotation(offset))
                    .flipY("up".equals(facing));
            cuboid(out, t, 0, 0, 8, 16, 3, 16, material);
            return out;
        }

        boolean sameAxis = (("north".equals(facing) || "south".equals(facing))
                && ("north".equals(offset) || "south".equals(offset)))
                || (("east".equals(facing) || "west".equals(facing))
                && ("east".equals(offset) || "west".equals(offset)));

        Transform t = new Transform().rotateY(yRotation(facing));
        if (sameAxis) {
            boolean positive = "south".equals(offset) || "east".equals(offset);
            t.flipY(positive);
            cuboid(out, t, 0, 0, 13, 16, 8, 16, material);
        } else {
            boolean left = isCounterClockwise(offset, facing);
            float x = left ? 8 : 0;
            cuboid(out, t, x, 0, 13, x + 8, 16, 16, material);
        }
        return out;
    }

    private List<Quad> verticalStep(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        List<Quad> out = new ArrayList<>();
        cuboid(out,
                new Transform().rotateY(yRotation(property("facing"))),
                8, 0, 8, 16, 16, 16, material);
        return out;
    }

    private List<Quad> beam(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        Transform t = new Transform();
        String axis = property("axis");
        if ("y".equals(axis)) t.rotateX(90);
        if ("x".equals(axis)) t.rotateY(90);
        List<Quad> out = new ArrayList<>();
        cuboid(out, t, 4, 4, 0, 12, 12, 16, material);
        return out;
    }

    private List<Quad> bytePanel(CopycatsTerrainBlockEntity entity) {
        List<Quad> out = new ArrayList<>();
        addBytePanelPart(out, entity, "bottom_left", 1, 0);
        addBytePanelPart(out, entity, "bottom_right", 0, 0);
        addBytePanelPart(out, entity, "top_left", 1, 1);
        addBytePanelPart(out, entity, "top_right", 0, 1);
        return out;
    }

    private void addBytePanelPart(
            List<Quad> out,
            CopycatsTerrainBlockEntity entity,
            String key,
            int i,
            int j) {
        if (!propertyBool(key)) return;
        Material material = materialFor(entity, key);
        if (!usable(material)) return;

        String facing = property("facing");
        if ("up".equals(facing)) {
            cuboid(out, new Transform(),
                    i * 8f, 13, 8 - j * 8f,
                    i * 8f + 8, 16, 16 - j * 8f,
                    material);
        } else if ("down".equals(facing)) {
            cuboid(out, new Transform(),
                    i * 8f, 0, j * 8f,
                    i * 8f + 8, 3, j * 8f + 8,
                    material);
        } else {
            cuboid(out, new Transform().rotateY(yRotation(facing)),
                    i * 8f, j * 8f, 13,
                    i * 8f + 8, j * 8f + 8, 16,
                    material);
        }
    }

    private List<Quad> pressurePlate(CopycatsTerrainBlockEntity entity) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        boolean powered = propertyBool("powered") || propertyInt("power") > 0;
        float height = powered ? 0.5f : 1f;
        List<Quad> out = new ArrayList<>();
        cuboid(out, new Transform(), 1, 0, 1, 15, height, 15, material);
        return out;
    }

    private static boolean isCounterClockwise(String offset, String facing) {
        return switch (facing) {
            case "north" -> "west".equals(offset);
            case "west" -> "south".equals(offset);
            case "south" -> "east".equals(offset);
            case "east" -> "north".equals(offset);
            default -> false;
        };
    }

    private List<Quad> templateQuads(CopycatsTerrainBlockEntity entity, String id) {
        Material material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        String modelId = templateModelId(id);
        if (modelId == null) return List.of();

        Model model = resourcePack.getModel(new ResourcePath<>(modelId));
        if (model == null) {
            return List.of();
        }
        model.applyParent(resourcePack);
        Element[] elements = model.getElements();
        if (elements == null || elements.length == 0) return List.of();

        Transform blockTransform = templateStateTransform(id);
        List<Quad> out = new ArrayList<>();
        for (Element element : elements) {
            if (element == null) continue;

            var from = element.getFrom();
            var to = element.getTo();
            float minX = Math.min(from.getX(), to.getX());
            float minY = Math.min(from.getY(), to.getY());
            float minZ = Math.min(from.getZ(), to.getZ());
            float maxX = Math.max(from.getX(), to.getX());
            float maxY = Math.max(from.getY(), to.getY());
            float maxZ = Math.max(from.getZ(), to.getZ());

            float[][] c = {
                    p(minX,minY,minZ), p(minX,minY,maxZ),
                    p(maxX,minY,minZ), p(maxX,minY,maxZ),
                    p(minX,maxY,minZ), p(minX,maxY,maxZ),
                    p(maxX,maxY,minZ), p(maxX,maxY,maxZ)
            };

            templateFace(out, element, Direction.DOWN, material, blockTransform, c[0],c[2],c[3],c[1]);
            templateFace(out, element, Direction.UP, material, blockTransform, c[5],c[7],c[6],c[4]);
            templateFace(out, element, Direction.NORTH, material, blockTransform, c[2],c[0],c[4],c[6]);
            templateFace(out, element, Direction.SOUTH, material, blockTransform, c[1],c[3],c[7],c[5]);
            templateFace(out, element, Direction.WEST, material, blockTransform, c[0],c[1],c[5],c[4]);
            templateFace(out, element, Direction.EAST, material, blockTransform, c[3],c[2],c[6],c[7]);
        }
        return out;
    }

    private void templateFace(
            List<Quad> out,
            Element element,
            Direction direction,
            Material material,
            Transform blockTransform,
            float[] a, float[] b, float[] c, float[] d) {
        if (!element.getFaces().containsKey(direction)) return;

        float[] positions = {
                a[0],a[1],a[2],
                b[0],b[1],b[2],
                c[0],c[1],c[2],
                d[0],d[1],d[2]
        };
        applyElementRotation(positions, element);
        blockTransform.apply(positions);
        if (blockTransform.mirrored()) reverseWinding(positions);
        out.add(new Quad(positions, direction, material));
    }

    private static void applyElementRotation(float[] positions, Element element) {
        var rotation = element.getRotation();
        float angle = rotation.getAngle();
        if (Math.abs(angle) < 0.0001f) return;

        var origin = rotation.getOrigin();
        var axis = rotation.getAxis().toVector();
        double rad = Math.toRadians(angle);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        double ax = axis.getX();
        double ay = axis.getY();
        double az = axis.getZ();

        for (int i = 0; i < positions.length; i += 3) {
            double x = positions[i] - origin.getX();
            double y = positions[i + 1] - origin.getY();
            double z = positions[i + 2] - origin.getZ();

            double dot = ax * x + ay * y + az * z;
            double rx = x * cos + (ay * z - az * y) * sin + ax * dot * (1 - cos);
            double ry = y * cos + (az * x - ax * z) * sin + ay * dot * (1 - cos);
            double rz = z * cos + (ax * y - ay * x) * sin + az * dot * (1 - cos);

            positions[i] = (float) (rx + origin.getX());
            positions[i + 1] = (float) (ry + origin.getY());
            positions[i + 2] = (float) (rz + origin.getZ());
        }
    }

    private Transform templateStateTransform(String id) {
        Transform transform = new Transform();

        String axis = property("axis");
        if ("x".equals(axis)) transform.rotateZ(90);
        if ("z".equals(axis)) transform.rotateX(90);

        String facing = property("facing");
        if (!facing.isEmpty()) {
            switch (facing) {
                case "north", "south", "east", "west" -> transform.rotateY(yRotation(facing));
                case "up" -> { }
                case "down" -> transform.rotateX(180);
                default -> { }
            }
        }

        if ("top".equals(property("half"))) transform.flipY(true);
        if ("ceiling".equals(property("face"))) transform.flipY(true);

        return transform;
    }

    private static String templateModelId(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) return null;

        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);
        String model;

        if ("copycats".equals(namespace)) {
            if ("wrapped_copycat".equals(path)) {
                model = "block";
            } else if (path.startsWith("copycat_")) {
                model = path.substring("copycat_".length());
            } else {
                return null;
            }

            model = switch (model) {
                case "wooden_button", "stone_button" -> "button";
                case "wooden_pressure_plate", "stone_pressure_plate",
                     "heavy_weighted_pressure_plate", "light_weighted_pressure_plate" -> "pressure_plate";
                case "iron_trapdoor" -> "trapdoor";
                case "iron_door" -> "door";
                case "glass_fluid_pipe" -> "fluid_pipe";
                default -> model;
            };
            return "copycats:block/copycat_base/" + model;
        }

        if ("create_connected".equals(namespace)) {
            if (path.startsWith("wrapped_copycat_")) {
                model = path.substring("wrapped_copycat_".length());
            } else if (path.startsWith("copycat_")) {
                model = path.substring("copycat_".length());
            } else {
                return null;
            }
            return "create_connected:block/copycat_base/" + model;
        }

        return null;
    }

    private void piece(
            List<Quad> out,
            Transform transform,
            float dx, float dy, float dz,
            float sx1, float sy1, float sz1,
            float sx2, float sy2, float sz2,
            Material material) {
        cuboid(out, transform,
                dx, dy, dz,
                dx + (sx2 - sx1),
                dy + (sy2 - sy1),
                dz + (sz2 - sz1),
                material);
    }

    private void cuboid(
            List<Quad> out,
            Transform transform,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            Material material) {
        float[][] c = {
                p(minX,minY,minZ), p(minX,minY,maxZ),
                p(maxX,minY,minZ), p(maxX,minY,maxZ),
                p(minX,maxY,minZ), p(minX,maxY,maxZ),
                p(maxX,maxY,minZ), p(maxX,maxY,maxZ)
        };
        quad(out, transform, Direction.DOWN, material, c[0],c[2],c[3],c[1]);
        quad(out, transform, Direction.UP, material, c[5],c[7],c[6],c[4]);
        quad(out, transform, Direction.NORTH, material, c[2],c[0],c[4],c[6]);
        quad(out, transform, Direction.SOUTH, material, c[1],c[3],c[7],c[5]);
        quad(out, transform, Direction.WEST, material, c[0],c[1],c[5],c[4]);
        quad(out, transform, Direction.EAST, material, c[3],c[2],c[6],c[7]);
    }

    private void quad(
            List<Quad> out,
            Transform transform,
            Direction materialFace,
            Material material,
            float[] a, float[] b, float[] c, float[] d) {
        float[] positions = {
                a[0],a[1],a[2],
                b[0],b[1],b[2],
                c[0],c[1],c[2],
                d[0],d[1],d[2]
        };
        transform.apply(positions);
        if (transform.mirrored()) reverseWinding(positions);
        out.add(new Quad(positions, materialFace, material));
    }

    private boolean emit(Quad quad) {
        Appearance appearance = appearance(quad.material(), quad.face());
        if (appearance == null) return false;

        float[] p = quad.positions();
        tileModel.initialize();
        tileModel.add(2);
        TileModel target = tileModel.getTileModel();
        int f1 = tileModel.getStart();
        int f2 = f1 + 1;

        target.setPositions(f1,
                p[0]/16f,p[1]/16f,p[2]/16f,
                p[3]/16f,p[4]/16f,p[5]/16f,
                p[6]/16f,p[7]/16f,p[8]/16f);
        target.setPositions(f2,
                p[0]/16f,p[1]/16f,p[2]/16f,
                p[6]/16f,p[7]/16f,p[8]/16f,
                p[9]/16f,p[10]/16f,p[11]/16f);

        target.setUvs(f1, 0f,1f, 1f,1f, 1f,0f);
        target.setUvs(f2, 0f,1f, 1f,0f, 0f,0f);
        target.setMaterialIndex(f1, appearance.textureIndex());
        target.setMaterialIndex(f2, appearance.textureIndex());

        Color tint = appearance.tint();
        target.setColor(f1, tint.r, tint.g, tint.b);
        target.setColor(f2, tint.r, tint.g, tint.b);

        LightData light = block.getLightData();
        target.setSunlight(f1, light.getSkyLight());
        target.setSunlight(f2, light.getSkyLight());
        target.setBlocklight(f1, light.getBlockLight());
        target.setBlocklight(f2, light.getBlockLight());
        target.setAOs(f1, 1f,1f,1f);
        target.setAOs(f2, 1f,1f,1f);
        return true;
    }

    private Appearance appearance(Material material, Direction wantedFace) {
        BlockState materialState = material.asBlockState();
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
        for (Element element : model.getElements()) {
            if (element == null) continue;
            Face face = element.getFaces().get(wantedFace);
            if (face != null) {
                selected = face;
                break;
            }
        }
        if (selected == null) {
            outer:
            for (Element element : model.getElements()) {
                if (element == null) continue;
                for (Direction direction : Direction.values()) {
                    Face face = element.getFaces().get(direction);
                    if (face != null) {
                        selected = face;
                        break outer;
                    }
                }
            }
        }
        if (selected == null) return null;

        ResourcePath<Texture> texture =
                selected.getTexture().getTexturePath(model.getTextures()::get);
        if (texture == null) texture = ResourcePack.MISSING_TEXTURE;
        int textureIndex = textureGallery.get(texture);

        Color tint = new Color().set(1f, 1f, 1f, 1f, true);
        if (selected.getTintindex() >= 0) {
            blockColorCalculator.getBlockColor(
                    new MaterialStateBlock(block, materialState),
                    tint);
            if (tint.a < 0) tint.set(1f,1f,1f,1f,true);
        }
        return new Appearance(textureIndex, tint);
    }

    private Material materialFor(CopycatsTerrainBlockEntity entity, String part) {
        if (part != null && entity.materialData() instanceof Map<?, ?> data) {
            Material specific = materialFromStorage(data.get(part));
            if (usable(specific)) return specific;
        }

        Material direct = materialFromState(entity.material());
        if (usable(direct)) return direct;

        if (entity.materialData() instanceof Map<?, ?> data) {
            for (Object value : data.values()) {
                Material fallback = materialFromStorage(value);
                if (usable(fallback)) return fallback;
            }
        }
        return direct;
    }

    private static Material materialFromStorage(Object raw) {
        if (!(raw instanceof Map<?, ?> storage)) return null;
        Object material = storage.containsKey("material")
                ? storage.get("material")
                : storage.get("Material");
        return materialFromState(material);
    }

    private static Material materialFromState(Object raw) {
        if (!(raw instanceof Map<?, ?> state)) return null;
        Object nameValue = state.containsKey("Name") ? state.get("Name") : state.get("name");
        if (!(nameValue instanceof String name) || name.isBlank()) return null;

        Map<String, String> properties = new LinkedHashMap<>();
        Object rawProperties = state.containsKey("Properties")
                ? state.get("Properties")
                : state.get("properties");
        if (rawProperties instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key
                        && entry.getValue() instanceof String value) {
                    properties.put(key, value);
                }
            }
        }
        return new Material(name, Map.copyOf(properties));
    }

    private static boolean usable(Material material) {
        return material != null
                && !"create:copycat_base".equals(material.id())
                && !"copycats:copycat_base".equals(material.id())
                && !"minecraft:air".equals(material.id());
    }

    private String property(String name) {
        return block.getBlockState().getProperties().getOrDefault(name, "");
    }

    private boolean propertyBool(String name) {
        return "true".equals(property(name));
    }

    private int propertyInt(String name) {
        try {
            return Integer.parseInt(property(name));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static int yRotation(String facing) {
        return switch (facing) {
            case "south" -> 0;
            case "west" -> 90;
            case "north" -> 180;
            case "east" -> 270;
            default -> 0;
        };
    }

    private static float[] p(float x, float y, float z) {
        return new float[]{x,y,z};
    }

    private static void reverseWinding(float[] p) {
        for (int i = 0; i < 3; i++) {
            float tmp = p[3 + i];
            p[3 + i] = p[9 + i];
            p[9 + i] = tmp;
        }
    }

    private record Material(String id, Map<String, String> properties) {
        BlockState asBlockState() {
            return new BlockState(id, properties);
        }
    }

    private record Quad(float[] positions, Direction face, Material material) {
    }

    private record Appearance(int textureIndex, Color tint) {
    }

    private static final class Transform {
        private static final int RX = 1, RY = 2, RZ = 3, FX = 4, FY = 5, FZ = 6;
        private final List<Integer> ops = new ArrayList<>();
        private boolean mirrored;

        Transform rotateX(int degrees) { addRot(RX, degrees); return this; }
        Transform rotateY(int degrees) { addRot(RY, degrees); return this; }
        Transform rotateZ(int degrees) { addRot(RZ, degrees); return this; }
        Transform flipX(boolean yes) { if (yes) { ops.add(FX); mirrored = !mirrored; } return this; }
        Transform flipY(boolean yes) { if (yes) { ops.add(FY); mirrored = !mirrored; } return this; }
        Transform flipZ(boolean yes) { if (yes) { ops.add(FZ); mirrored = !mirrored; } return this; }
        boolean mirrored() { return mirrored; }

        private void addRot(int op, int degrees) {
            int turns = Math.floorMod(degrees / 90, 4);
            for (int i = 0; i < turns; i++) ops.add(op);
        }

        void apply(float[] positions) {
            for (int i = 0; i < positions.length; i += 3) {
                float x = positions[i];
                float y = positions[i + 1];
                float z = positions[i + 2];
                for (int op : ops) {
                    float nx = x, ny = y, nz = z;
                    switch (op) {
                        case FX -> nx = 16 - x;
                        case FY -> ny = 16 - y;
                        case FZ -> nz = 16 - z;
                        case RX -> { ny = 16 - z; nz = y; }
                        case RY -> { nx = 16 - z; nz = x; }
                        case RZ -> { nx = 16 - y; ny = x; }
                        default -> { }
                    }
                    x = nx; y = ny; z = nz;
                }
                positions[i] = x;
                positions[i + 1] = y;
                positions[i + 2] = z;
            }
        }
    }
}
