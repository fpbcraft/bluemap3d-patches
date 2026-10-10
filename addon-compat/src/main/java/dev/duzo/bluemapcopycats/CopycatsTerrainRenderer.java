package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import de.bluecolored.bluemap.core.logger.Logger;

import java.util.ArrayList;
import java.util.List;
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

    private final CopycatsTemplateGeometry templateGeometry;
    private final CopycatsQuadEmitter quadEmitter;

    private BlockNeighborhood block;

    public CopycatsTerrainRenderer(
            ResourcePack resourcePack,
            TextureGallery textureGallery,
            RenderSettings renderSettings) {
        this.templateGeometry = new CopycatsTemplateGeometry(resourcePack);
        this.quadEmitter = new CopycatsQuadEmitter(
                new CopycatsAppearanceResolver(resourcePack, textureGallery));
    }

    @Override
    public void render(
            BlockNeighborhood block,
            Variant ignoredVariant,
            TileModelView tileModel,
            Color blockColor) {
        this.block = block;

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
            case "create:copycat_panel" -> createPanel(entity);
            case "create:copycat_step" -> createStep(entity);
            case "copycats:copycat_byte" -> byteQuads(entity);
            case "copycats:copycat_vertical_half_layer" -> verticalHalfLayer(entity);
            case "copycats:copycat_flat_pane" -> flatPane(entity);
            case "copycats:copycat_vertical_stairs" -> verticalStairs(entity);
            case "copycats:copycat_slope" -> slope(entity);
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
            if (quadEmitter.emit(
                    quad.positions(),
                    quad.face(),
                    quad.material(),
                    block,
                    tileModel)) {
                emitted++;
            }
        }
        tileModel.initialize(renderStart);

        if (emitted > 0) {
            blockColor.set(1f, 1f, 1f, 1f, true);
        }

        if (TRACED.add(id)) {
            Logger.global.logDebug(String.format("STATIC block=%s state=%s entity=%s geometryQuads=%s emittedQuads=%s",
                    id,
                    block.getBlockState(),
                    entity.getId(),
                    quads.size(),
                    emitted));
        }
    }

    /** Create's CASING_3PX profile, facing the clicked support surface. */
    private List<Quad> createPanel(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        CopycatsTransform transform = new CopycatsTransform();
        switch (property("facing")) {
            case "down" -> transform.flipY(true);
            case "north" -> transform.rotateX(90);
            case "south" -> transform.rotateX(270);
            case "west" -> transform.rotateZ(270);
            case "east" -> transform.rotateZ(90);
            default -> { /* up: base slab at y=0 */ }
        }
        List<Quad> out = new ArrayList<>();
        cuboid(out, transform, 0, 0, 0, 16, 3, 16, material);
        return out;
    }

    /** Create's STEP_BOTTOM/STEP_TOP: half-height, half-depth, rotated about Y. */
    private List<Quad> createStep(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        CopycatsTransform transform = new CopycatsTransform()
                .rotateY(yRotation(property("facing")));
        List<Quad> out = new ArrayList<>();
        boolean top = "top".equals(property("half"));
        cuboid(out, transform, 0, top ? 8 : 0, 8, 16, top ? 16 : 8, 16, material);
        return out;
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
        CopycatsMaterial material = materialFor(entity, key);
        if (!usable(material)) return;
        cuboid(out, new CopycatsTransform(), x, y, z, x + 8, y + 8, z + 8, material);
    }

    private List<Quad> verticalHalfLayer(CopycatsTerrainBlockEntity entity) {
        List<Quad> out = new ArrayList<>();
        int positive = propertyInt("positive_layers");
        int negative = propertyInt("negative_layers");
        int rot = yRotation(property("facing"));

        if (positive > 0) {
            CopycatsMaterial material = materialFor(entity, "positive_layers");
            if (usable(material)) {
                addHalfLayer(out, material, positive, new CopycatsTransform().rotateY(rot + 180));
            }
        }
        if (negative > 0) {
            CopycatsMaterial material = materialFor(entity, "negative_layers");
            if (usable(material)) {
                addHalfLayer(out, material, negative,
                        new CopycatsTransform().flipX(true).rotateY(rot + 180));
            }
        }
        return out;
    }

    private void addHalfLayer(
            List<Quad> out,
            CopycatsMaterial material,
            int layer,
            CopycatsTransform transform) {
        float l = Math.max(0, Math.min(8, layer));
        if (l <= 0) return;
        piece(out, transform, 0, 0, 0, 0, 0, 0, 4, 16, l, material);
        piece(out, transform, 0, 0, l, 0, 0, 16 - l, 4, 16, 16, material);
        piece(out, transform, 4, 0, 0, 12, 0, 0, 16, 16, l, material);
        piece(out, transform, 4, 0, l, 12, 0, 16 - l, 16, 16, 16, material);
    }

    private List<Quad> flatPane(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        CopycatsTransform transform = new CopycatsTransform();
        String axis = property("axis");
        if ("z".equals(axis)) transform.rotateX(90);
        if ("x".equals(axis)) transform.rotateZ(90);

        List<Quad> out = new ArrayList<>();
        cuboid(out, transform, 0, 7, 0, 16, 8, 16, material);
        cuboid(out, transform, 0, 8, 0, 16, 9, 16, material);
        return out;
    }

    private List<Quad> verticalStairs(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        int facing = yRotation(property("facing"));
        boolean right = "right".equals(property("side"));
        String shape = property("vertical_stair_shape");

        CopycatsTransform transform = new CopycatsTransform().rotateX(90).rotateZ(90).flipX(right);
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

    private void stairStraight(List<Quad> out, CopycatsTransform t, CopycatsMaterial m) {
        piece(out,t,0,0,0,  0,0,0,   16,4,8,m);
        piece(out,t,0,4,0,  0,12,0,  16,16,8,m);
        piece(out,t,0,0,8,  0,0,8,   16,8,16,m);
        piece(out,t,0,8,8,  0,8,0,   16,16,4,m);
        piece(out,t,0,8,12, 0,8,12,  16,16,16,m);
    }

    private void stairInner(List<Quad> out, CopycatsTransform t, CopycatsMaterial m) {
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

    private void stairOuter(List<Quad> out, CopycatsTransform t, CopycatsMaterial m) {
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
            CopycatsMaterial bottom = materialFor(entity, "bottom");
            if (usable(bottom)) addSlabHalf(out, axis, true, bottom);
        }
        if (!"bottom".equals(type)) {
            CopycatsMaterial top = materialFor(entity, "top");
            if (usable(top)) addSlabHalf(out, axis, false, top);
        }
        return out;
    }

    private List<Quad> connectedSlab(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
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
            CopycatsMaterial material) {
        if ("y".equals(axis)) {
            CopycatsTransform t = new CopycatsTransform().flipY(!bottomPart);
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
        CopycatsTransform t = new CopycatsTransform().rotateY(yRotation(facing));
        cuboid(out, t, 0, 0, 0, 16, 16, 4, material);
        cuboid(out, t, 0, 0, 4, 16, 16, 8, material);
    }

    private List<Quad> slope(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        return slopePrism(material, 0, 16);
    }

    private List<Quad> slopeLayer(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        int layer = propertyInt("layers");
        if (layer <= 0) return List.of();
        float minHeight = layer <= 4 ? 0 : (layer - 4) * 4f;
        float maxHeight = layer <= 4 ? layer * 4f : 16f;
        return slopePrism(material, minHeight, maxHeight);
    }

    private List<Quad> slopePrism(CopycatsMaterial material, float minHeight, float maxHeight) {
        CopycatsTransform transform = new CopycatsTransform()
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
            CopycatsMaterial material = materialFor(entity, key);
            if (!usable(material)) continue;

            switch (face) {
                case DOWN -> cuboid(out, new CopycatsTransform(), 0, 0, 0, 16, 1, 16, material);
                case UP -> cuboid(out, new CopycatsTransform(), 0, 15, 0, 16, 16, 16, material);
                case NORTH -> cuboid(out, new CopycatsTransform(), 0, 0, 0, 16, 16, 1, material);
                case SOUTH -> cuboid(out, new CopycatsTransform(), 0, 0, 15, 16, 16, 16, material);
                case WEST -> cuboid(out, new CopycatsTransform(), 0, 0, 0, 1, 16, 16, material);
                case EAST -> cuboid(out, new CopycatsTransform(), 15, 0, 0, 16, 16, 16, material);
            }
        }
        return out;
    }

    private List<Quad> verticalSlice(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        float layers = Math.max(1, Math.min(8, propertyInt("layers")));
        float size = layers * 2f;
        CopycatsTransform t = new CopycatsTransform().rotateY(yRotation(property("facing")));
        List<Quad> out = new ArrayList<>();
        cuboid(out, t, 16 - size, 0, 16 - size, 16, 16, 16, material);
        return out;
    }

    private List<Quad> slice(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        float layers = Math.max(1, Math.min(8, propertyInt("layers")));
        float size = layers * 2f;
        CopycatsTransform t = new CopycatsTransform()
                .rotateY(yRotation(property("facing")))
                .flipY("top".equals(property("half")));
        List<Quad> out = new ArrayList<>();
        cuboid(out, t, 0, 0, 16 - size, 16, size, 16, material);
        return out;
    }

    private List<Quad> cornerSlice(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        float layers = Math.max(1, Math.min(8, propertyInt("layers")));
        float size = layers * 2f;
        CopycatsTransform t = new CopycatsTransform()
                .rotateY(yRotation(property("facing")))
                .flipY("top".equals(property("half")));
        List<Quad> out = new ArrayList<>();
        cuboid(out, t, 16 - size, 0, 16 - size, 16, size, 16, material);
        return out;
    }

    private List<Quad> layer(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        float layers = Math.max(1, Math.min(8, propertyInt("layers")));
        float size = layers * 2f;
        String facing = property("facing");
        List<Quad> out = new ArrayList<>();

        if ("up".equals(facing) || "down".equals(facing)) {
            CopycatsTransform t = new CopycatsTransform().flipY("down".equals(facing));
            cuboid(out, t, 0, 0, 0, 16, size, 16, material);
        } else {
            CopycatsTransform t = new CopycatsTransform().rotateY(yRotation(facing));
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
            CopycatsMaterial material = materialFor(entity, "negative_layers");
            if (usable(material)) {
                float size = Math.min(8, negative) * 2f;
                CopycatsTransform t = new CopycatsTransform().rotateY(rot).flipY(top);
                cuboid(out, t, 0, 0, 0, 8, size, 16, material);
            }
        }
        if (positive > 0) {
            CopycatsMaterial material = materialFor(entity, "positive_layers");
            if (usable(material)) {
                float size = Math.min(8, positive) * 2f;
                CopycatsTransform t = new CopycatsTransform().rotateY(rot + 180).flipY(top);
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
            CopycatsMaterial material = materialFor(entity, "negative_layers");
            if (usable(material)) {
                float size = Math.min(8, negative) * 2f;
                cuboid(out, new CopycatsTransform().rotateY(rot),
                        0, 0, 0, 16, 8, size, material);
            }
        }
        if (positive > 0) {
            CopycatsMaterial material = materialFor(entity, "positive_layers");
            if (usable(material)) {
                float size = Math.min(8, positive) * 2f;
                cuboid(out, new CopycatsTransform().flipY(true).rotateY(rot),
                        0, 0, 0, 16, 8, size, material);
            }
        }
        return out;
    }

    private List<Quad> halfPanel(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        String facing = property("facing");
        String offset = property("offset");
        List<Quad> out = new ArrayList<>();

        if ("up".equals(facing) || "down".equals(facing)) {
            CopycatsTransform t = new CopycatsTransform()
                    .rotateY(yRotation(offset))
                    .flipY("up".equals(facing));
            cuboid(out, t, 0, 0, 8, 16, 3, 16, material);
            return out;
        }

        boolean sameAxis = (("north".equals(facing) || "south".equals(facing))
                && ("north".equals(offset) || "south".equals(offset)))
                || (("east".equals(facing) || "west".equals(facing))
                && ("east".equals(offset) || "west".equals(offset)));

        CopycatsTransform t = new CopycatsTransform().rotateY(yRotation(facing));
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
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        List<Quad> out = new ArrayList<>();
        cuboid(out,
                new CopycatsTransform().rotateY(yRotation(property("facing"))),
                8, 0, 8, 16, 16, 16, material);
        return out;
    }

    private List<Quad> beam(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        CopycatsTransform t = new CopycatsTransform();
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
        CopycatsMaterial material = materialFor(entity, key);
        if (!usable(material)) return;

        String facing = property("facing");
        if ("up".equals(facing)) {
            cuboid(out, new CopycatsTransform(),
                    i * 8f, 13, 8 - j * 8f,
                    i * 8f + 8, 16, 16 - j * 8f,
                    material);
        } else if ("down".equals(facing)) {
            cuboid(out, new CopycatsTransform(),
                    i * 8f, 0, j * 8f,
                    i * 8f + 8, 3, j * 8f + 8,
                    material);
        } else {
            cuboid(out, new CopycatsTransform().rotateY(yRotation(facing)),
                    i * 8f, j * 8f, 13,
                    i * 8f + 8, j * 8f + 8, 16,
                    material);
        }
    }

    private List<Quad> pressurePlate(CopycatsTerrainBlockEntity entity) {
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();
        boolean powered = propertyBool("powered") || propertyInt("power") > 0;
        float height = powered ? 0.5f : 1f;
        List<Quad> out = new ArrayList<>();
        cuboid(out, new CopycatsTransform(), 1, 0, 1, 15, height, 15, material);
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
        CopycatsMaterial material = materialFor(entity, null);
        if (!usable(material)) return List.of();

        List<CopycatsTemplateGeometry.TemplateQuad> templateQuads =
                templateGeometry.quads(id, block.getBlockState());
        if (templateQuads.isEmpty()) return List.of();

        List<Quad> out = new ArrayList<>(templateQuads.size());
        for (CopycatsTemplateGeometry.TemplateQuad quad : templateQuads) {
            out.add(new Quad(quad.positions(), quad.face(), material));
        }
        return out;
    }

    private void piece(
            List<Quad> out,
            CopycatsTransform transform,
            float dx, float dy, float dz,
            float sx1, float sy1, float sz1,
            float sx2, float sy2, float sz2,
            CopycatsMaterial material) {
        cuboid(out, transform,
                dx, dy, dz,
                dx + (sx2 - sx1),
                dy + (sy2 - sy1),
                dz + (sz2 - sz1),
                material);
    }

    private void cuboid(
            List<Quad> out,
            CopycatsTransform transform,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            CopycatsMaterial material) {
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
            CopycatsTransform transform,
            Direction materialFace,
            CopycatsMaterial material,
            float[] a, float[] b, float[] c, float[] d) {
        float[] positions = {
                a[0],a[1],a[2],
                b[0],b[1],b[2],
                c[0],c[1],c[2],
                d[0],d[1],d[2]
        };
        transform.applyQuad(positions);
        out.add(new Quad(positions, materialFace, material));
    }

    private CopycatsMaterial materialFor(CopycatsTerrainBlockEntity entity, String part) {
        return CopycatsMaterialResolver.materialFor(entity, part);
    }

    private static boolean usable(CopycatsMaterial material) {
        return CopycatsMaterialResolver.usable(material);
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

    private record Quad(float[] positions, Direction face, CopycatsMaterial material) {
    }


}
