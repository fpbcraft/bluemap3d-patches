package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import net.minecraft.world.level.block.Blocks;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedicated-server models for the Copycats+ procedural blocks that do not have usable JSON
 * geometry on a server. The geometry below follows Copycats+ 3.0.9's model-core definitions
 * instead of using the generic VoxelShape approximation.
 */
public final class CopycatsSpecialSource implements BlockModelSource {
    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Copycats+");
    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();

    private final ResourcePackSource models;
    private final ThreadLocal<BlockRenderContext> renderContext = new ThreadLocal<>();
    private static final boolean TRACE =
            Boolean.getBoolean("bluemap.copycats.trace");
    private static final java.util.concurrent.atomic.AtomicInteger TRACE_LINES =
            new java.util.concurrent.atomic.AtomicInteger();


    public CopycatsSpecialSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return List.of();
    }

    @Override
    public List<ModelQuad> quadsFor(BlockRenderContext context) {
        renderContext.set(context);
        try {
            return quadsFor(context.state(), context.blockEntityData());
        } finally {
            renderContext.remove();
        }
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state, CompoundTag metadata) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if (!isTarget(id)) return List.of();

        List<ModelQuad> result = metadata == null ? List.of() : switch (id) {
            case "copycats:copycat_byte" -> byteQuads(state, metadata);
            case "copycats:copycat_byte_panel" -> bytePanel(state, metadata);
            case "copycats:copycat_vertical_half_layer" -> verticalHalfLayer(state, metadata);
            case "copycats:copycat_flat_pane" -> flatPane(state, metadata);
            case "copycats:copycat_vertical_stairs" -> verticalStairs(state, metadata);
            case "copycats:copycat_slope" -> slope(state, metadata);
            case "copycats:copycat_slope_layer" -> slopeLayer(state, metadata);
            case "copycats:copycat_slab" -> copycatsSlab(state, metadata);
            case "create_connected:copycat_slab" -> connectedSlab(state, metadata);
            default -> List.of();
        };

        // One concise line per affected block type. This makes it unambiguous whether the
        // installed bundle is V16 and whether the dedicated source actually wins over the
        // old "copycat voxel shape" fallback.
        if (TRACED.add(id)) {
            String keys = materialKeys(metadata);
            LOGGER.debug("V21 MOVING block={} state={} metadata={} materialKeys={} quads={}",
                    id, state, metadata == null ? "missing" : "present", keys, result.size());
        }
        if (TRACE && TRACE_LINES.getAndIncrement() < 60) {
            long noFace = result.stream()
                    .filter(quad -> quad.shadeFace() == null && quad.cullFace() == null)
                    .count();
            java.util.Set<String> textures = new java.util.TreeSet<>();
            for (ModelQuad quad : result) {
                if (quad.texture() != null && textures.size() < 12)
                    textures.add(quad.texture());
            }
            BlockRenderContext current = renderContext.get();
            LOGGER.debug("COPYCATS-MOVING-TRACE phase=SOURCE block={} coords={} metadata={} materialKeys={} quads={} missingFace={} textures={}",
                    id,
                    current == null ? "<none>" :
                            current.x() + "," + current.y() + "," + current.z(),
                    metadata == null ? "<null>" : "present",
                    materialKeys(metadata), result.size(), noFace, textures);
        }
        return result;
    }

    @Override
    public BufferedImage texture(String texture) {
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return false;
    }

    private static boolean isTarget(String id) {
        return id.equals("copycats:copycat_byte_panel")
                || id.equals("copycats:copycat_byte")
                || id.equals("copycats:copycat_vertical_half_layer")
                || id.equals("copycats:copycat_flat_pane")
                || id.equals("copycats:copycat_vertical_stairs")
                || id.equals("copycats:copycat_slope")
                || id.equals("copycats:copycat_slope_layer")
                || id.equals("copycats:copycat_slab")
                || id.equals("create_connected:copycat_slab");
    }

    // -------------------------------------------------------------------------
    // Copycat Byte
    // -------------------------------------------------------------------------

    /**
     * Eight bytes are independently populated and independently textured. Evaluate
     * their connected textures on the 2x2x2 part lattice, including seams INSIDE the
     * same block, rather than treating every 8px byte as a separate whole block.
     */
    private List<ModelQuad> byteQuads(BlockState state, CompoundTag metadata) {
        Map<Integer, BlockState> parts = new HashMap<>();
        for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            String key = byteKey(x, y, z);
            BlockState material = materialFor(metadata, key);
            if (booleanProperty(state, key) && usable(material))
                parts.put(cellKey(x, y, z), material);
        }

        List<ModelQuad> result = new ArrayList<>();
        for (int y = 0; y < 2; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
            BlockState material = parts.get(cellKey(x, y, z));
            if (material == null) continue;
            final int ox=x*8, oy=y*8, oz=z*8;
            addPart(parts, x, y, z, () ->
                    addBytePieces(result, ox, oy, oz, material));
        }
        return List.copyOf(result);
    }

    private void addBytePieces(
            List<ModelQuad> result, int ox, int oy, int oz, BlockState material) {
        Transform t = new Transform();
        // Retain the original Copycats+ eight source samples per byte.
        piece(result,t,ox,oy,oz,0,0,0,4,4,4,material);
        piece(result,t,ox+4,oy,oz,12,0,0,16,4,4,material);
        piece(result,t,ox,oy,oz+4,0,0,12,4,4,16,material);
        piece(result,t,ox+4,oy,oz+4,12,0,12,16,4,16,material);
        piece(result,t,ox,oy+4,oz,0,12,0,4,16,4,material);
        piece(result,t,ox+4,oy+4,oz,12,12,0,16,16,4,material);
        piece(result,t,ox,oy+4,oz+4,0,12,12,4,16,16,material);
        piece(result,t,ox+4,oy+4,oz+4,12,12,12,16,16,16,material);
    }

    private List<ModelQuad> bytePanel(BlockState state, CompoundTag metadata) {
        record PanelPart(String name, int x, int y) {}
        PanelPart[] items = {
                new PanelPart("bottom_left", 1, 0),
                new PanelPart("bottom_right", 0, 0),
                new PanelPart("top_left", 1, 1),
                new PanelPart("top_right", 0, 1)
        };
        String facing = stringProperty(state, "facing");
        Map<Integer, BlockState> parts = new HashMap<>();
        for (PanelPart part : items) {
            if (!booleanProperty(state, part.name())) continue;
            BlockState material = materialFor(metadata, part.name());
            if (!usable(material)) continue;
            int[] xyz = panelCell(facing, part.x(), part.y());
            parts.put(cellKey(xyz[0], xyz[1], xyz[2]), material);
        }

        List<ModelQuad> out = new ArrayList<>();
        for (PanelPart part : items) {
            int[] xyz = panelCell(facing, part.x(), part.y());
            BlockState material = parts.get(cellKey(xyz[0], xyz[1], xyz[2]));
            if (material == null) continue;
            addPart(parts, xyz[0], xyz[1], xyz[2], () -> {
                float i = part.x() * 8f;
                float j = part.y() * 8f;
                if ("up".equals(facing)) {
                    addMappedCuboid(out,new Transform(),
                            new float[]{i,13,8-j},new float[]{i+8,16,16-j},
                            new float[]{i,13,8-j},new float[]{i+8,16,16-j},material);
                } else if ("down".equals(facing)) {
                    addMappedCuboid(out,new Transform(),
                            new float[]{i,0,j},new float[]{i+8,3,j+8},
                            new float[]{i,0,j},new float[]{i+8,3,j+8},material);
                } else {
                    Transform rotation = new Transform().rotateY(yRotation(facing));
                    addMappedCuboid(out,rotation,
                            new float[]{i,j,13},new float[]{i+8,j+8,16},
                            new float[]{i,j,13},new float[]{i+8,j+8,16},material);
                }
            });
        }
        return List.copyOf(out);
    }

    private void addPart(Map<Integer,BlockState> parts,
            int x, int y, int z, Runnable build) {
        BlockRenderContext base = renderContext.get();
        if (base == null) {
            build.run();
            return;
        }
        // Temporarily use a virtual 8px lattice. An adjacent byte uses its own
        // copied material; positions beyond the block fall through to the original
        // world/contraption lookup, which already resolves neighbouring copycats.
        BlockRenderContext lookup = new BlockRenderContext(
                parts.get(cellKey(x,y,z)), base.blockEntityData(),
                base.x()*2 + x, base.y()*2 + y, base.z()*2 + z,
                (vx,vy,vz) -> {
                    int bx = Math.floorDiv(vx,2), by = Math.floorDiv(vy,2);
                    int bz = Math.floorDiv(vz,2);
                    if (bx == base.x() && by == base.y() && bz == base.z()) {
                        return parts.getOrDefault(cellKey(Math.floorMod(vx,2),
                                Math.floorMod(vy,2),Math.floorMod(vz,2)),
                                Blocks.AIR.defaultBlockState());
                    }
                    return base.stateAtOffset(bx-base.x(), by-base.y(), bz-base.z());
                });
        renderContext.set(lookup);
        try {
            build.run();
        } finally {
            renderContext.set(base);
        }
    }

    private static int cellKey(int x,int y,int z) {
        return (x << 2) | (y << 1) | z;
    }

    private static String byteKey(int x,int y,int z) {
        return (y==0?"bottom_":"top_")
                + (z==0?"north":"south") + (x==0?"west":"east");
    }

    private static int[] panelCell(String facing, int i, int j) {
        return switch(facing) {
            case "up" -> new int[]{i,1,1-j};
            case "down" -> new int[]{i,0,j};
            case "north" -> new int[]{1-i,j,0};
            case "east" -> new int[]{1,j,1-i};
            case "west" -> new int[]{0,j,i};
            default -> new int[]{i,j,1};
        };
    }

    // -------------------------------------------------------------------------
    // Copycat Vertical Half Layer
    // -------------------------------------------------------------------------

    private List<ModelQuad> verticalHalfLayer(BlockState state, CompoundTag metadata) {
        List<ModelQuad> out = new ArrayList<>();
        int positiveLayers = intProperty(state, "positive_layers");
        int negativeLayers = intProperty(state, "negative_layers");
        int rot = yRotation(stringProperty(state, "facing"));

        if (positiveLayers > 0) {
            BlockState material = materialFor(metadata, "positive_layers");
            if (usable(material)) {
                addHalfLayerPart(out, material, positiveLayers,
                        new Transform().rotateY(rot + 180));
            }
        }
        if (negativeLayers > 0) {
            BlockState material = materialFor(metadata, "negative_layers");
            if (usable(material)) {
                addHalfLayerPart(out, material, negativeLayers,
                        new Transform().flipX(true).rotateY(rot + 180));
            }
        }
        return List.copyOf(out);
    }

    private void addHalfLayerPart(
            List<ModelQuad> out, BlockState material, int layer, Transform transform) {
        // Copycats+ layer values are already model pixels (0..8). V15 multiplied them by
        // two, making every vertical half layer twice as thick as the real model.
        float l = Math.max(0, Math.min(8, layer));
        if (l <= 0) return;
        piece(out, transform, 0, 0, 0, 0, 0, 0, 4, 16, l, material);
        piece(out, transform, 0, 0, l, 0, 0, 16 - l, 4, 16, 16, material);
        piece(out, transform, 4, 0, 0, 12, 0, 0, 16, 16, l, material);
        piece(out, transform, 4, 0, l, 12, 0, 16 - l, 16, 16, 16, material);
    }

    // -------------------------------------------------------------------------
    // Copycat Flat Pane
    // -------------------------------------------------------------------------

    private List<ModelQuad> flatPane(BlockState state, CompoundTag metadata) {
        BlockState material = materialFor(metadata, null);
        if (!usable(material)) return List.of();

        String axis = stringProperty(state, "axis");
        Transform transform = new Transform();
        if ("z".equals(axis)) transform.rotateX(90);
        if ("x".equals(axis)) transform.rotateZ(90);

        List<ModelQuad> out = new ArrayList<>();
        // Exactly the two one-pixel slices emitted by CopycatFlatPaneModelCore.
        piece(out, transform, 0, 7, 0, 0, 0, 0, 16, 1, 16, material);
        piece(out, transform, 0, 8, 0, 0, 0, 0, 16, 1, 16, material);
        return List.copyOf(out);
    }

    // -------------------------------------------------------------------------
    // Copycat Vertical Stairs
    // -------------------------------------------------------------------------

    private List<ModelQuad> verticalStairs(BlockState state, CompoundTag metadata) {
        BlockState material = materialFor(metadata, null);
        if (!usable(material)) return List.of();

        int facing = yRotation(stringProperty(state, "facing"));
        boolean right = "right".equals(stringProperty(state, "side"));
        String shape = stringProperty(state, "vertical_stair_shape");

        Transform transform = new Transform().rotateX(90).rotateZ(90).flipX(right);
        if (shape.endsWith("_top")) transform.flipY(true);
        transform.rotateY(facing);

        List<ModelQuad> out = new ArrayList<>();
        if ("straight".equals(shape)) {
            stairStraight(out, transform, material);
        } else if (shape.startsWith("inner_")) {
            stairInnerLeft(out, transform, material);
        } else if (shape.startsWith("outer_")) {
            stairOuterLeft(out, transform, material);
        }
        return List.copyOf(out);
    }

    // These are the non-enhanced piece layouts from CopycatStairsModelCore. They retain
    // Copycats+'s source sampling and shape instead of replacing the stair with its collision
    // VoxelShape. At BlueMap scale this is substantially closer while remaining server-safe.
    private void stairStraight(List<ModelQuad> out, Transform t, BlockState m) {
        piece(out,t,0,0,0,  0,0,0,   16,4,8,m);
        piece(out,t,0,4,0,  0,12,0,  16,16,8,m);
        piece(out,t,0,0,8,  0,0,8,   16,8,16,m);
        piece(out,t,0,8,8,  0,8,0,   16,16,4,m);
        piece(out,t,0,8,12, 0,8,12,  16,16,16,m);
    }

    private void stairInnerLeft(List<ModelQuad> out, Transform t, BlockState m) {
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

    private void stairOuterLeft(List<ModelQuad> out, Transform t, BlockState m) {
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

    // -------------------------------------------------------------------------
    // Copycat Slabs (Copycats+ and Create: Connected)
    // -------------------------------------------------------------------------

    private List<ModelQuad> copycatsSlab(BlockState state, CompoundTag metadata) {
        String type = stringProperty(state, "type");
        String axis = stringProperty(state, "axis");

        List<ModelQuad> out = new ArrayList<>();
        if (!"top".equals(type)) {
            BlockState bottom = materialFor(metadata, "bottom");
            if (usable(bottom)) addSlabHalf(out, axis, true, bottom);
        }
        if (!"bottom".equals(type)) {
            BlockState top = materialFor(metadata, "top");
            if (usable(top)) addSlabHalf(out, axis, false, top);
        }
        return List.copyOf(out);
    }

    private List<ModelQuad> connectedSlab(BlockState state, CompoundTag metadata) {
        BlockState material = materialFor(metadata, null);
        if (!usable(material)) return List.of();

        String type = stringProperty(state, "type");
        String axis = stringProperty(state, "axis");

        List<ModelQuad> out = new ArrayList<>();
        if (!"top".equals(type)) addSlabHalf(out, axis, true, material);
        if (!"bottom".equals(type)) addSlabHalf(out, axis, false, material);
        return List.copyOf(out);
    }

    private void addSlabHalf(List<ModelQuad> out, String axis, boolean bottomPart, BlockState material) {
        // Copycats+ CopycatMultiSlabModelCore:
        // bottom storage uses the positive axis direction, top storage the negative.
        // The two 4-pixel destination slices together form the 8-pixel slab while sampling
        // the outer four pixels from each side of the copied material.
        if ("y".equals(axis)) {
            Transform t = new Transform().flipY(!bottomPart);
            piece(out, t, 0, 0, 0, 0, 0, 0, 16, 4, 16, material);
            piece(out, t, 0, 4, 0, 0, 12, 0, 16, 16, 16, material);
            return;
        }

        String facing;
        if ("x".equals(axis)) {
            facing = bottomPart ? "east" : "west";
        } else {
            facing = bottomPart ? "south" : "north";
        }
        Transform t = new Transform().rotateY(yRotation(facing));
        piece(out, t, 0, 0, 0, 0, 0, 0, 16, 16, 4, material);
        piece(out, t, 0, 0, 4, 0, 0, 12, 16, 16, 16, material);
    }

    // -------------------------------------------------------------------------
    // Copycat Slope Layer
    // -------------------------------------------------------------------------

    private List<ModelQuad> slope(BlockState state, CompoundTag metadata) {
        BlockState material = materialFor(metadata, null);
        if (!usable(material)) return List.of();
        return slopePrism(state, material, 0, 16);
    }

    private List<ModelQuad> slopeLayer(BlockState state, CompoundTag metadata) {
        BlockState material = materialFor(metadata, null);
        if (!usable(material)) return List.of();

        int layer = intProperty(state, "layers");
        if (layer <= 0) return List.of();
        float minHeight = layer <= 4 ? 0 : (layer - 4) * 4f;
        float maxHeight = layer <= 4 ? layer * 4f : 16f;
        return slopePrism(state, material, minHeight, maxHeight);
    }

    private List<ModelQuad> slopePrism(
            BlockState state, BlockState material, float minHeight, float maxHeight) {
        Transform transform = new Transform()
                .rotateY(yRotation(stringProperty(state, "facing")))
                .flipY("top".equals(stringProperty(state, "half")));

        List<ModelQuad> out = new ArrayList<>();
        addSlopePrism(out, transform, minHeight, maxHeight, material);
        return List.copyOf(out);
    }

    private void addSlopePrism(
            List<ModelQuad> out, Transform t, float minH, float maxH, BlockState material) {
        // Identity orientation matches CopycatSlopeModelCore's SOUTH-facing model: height
        // rises linearly from north (z=0) to south (z=16).
        addQuad(out, t, Direction.DOWN, material,
                p(0,0,0), p(16,0,0), p(16,0,16), p(0,0,16));
        if (minH > 0.0001f) {
            addQuad(out, t, Direction.NORTH, material,
                    p(0,0,0), p(0,minH,0), p(16,minH,0), p(16,0,0));
        }
        addQuad(out, t, Direction.SOUTH, material,
                p(16,0,16), p(16,maxH,16), p(0,maxH,16), p(0,0,16));
        addQuad(out, t, Direction.WEST, material,
                p(0,0,16), p(0,maxH,16), p(0,minH,0), p(0,0,0));
        addQuad(out, t, Direction.EAST, material,
                p(16,0,0), p(16,minH,0), p(16,maxH,16), p(16,0,16));
        addQuad(out, t, Direction.UP, material,
                p(0,minH,0), p(0,maxH,16), p(16,maxH,16), p(16,minH,0));
    }

    // -------------------------------------------------------------------------
    // Geometry / material helpers
    // -------------------------------------------------------------------------

    private void piece(
            List<ModelQuad> out,
            Transform transform,
            float dx, float dy, float dz,
            float sx1, float sy1, float sz1,
            float sx2, float sy2, float sz2,
            BlockState material) {
        float w = sx2 - sx1;
        float h = sy2 - sy1;
        float d = sz2 - sz1;
        addMappedCuboid(out, transform,
                new float[]{dx, dy, dz}, new float[]{dx + w, dy + h, dz + d},
                new float[]{sx1, sy1, sz1}, new float[]{sx2, sy2, sz2}, material);
    }

    private void addMappedCuboid(
            List<ModelQuad> out,
            Transform transform,
            float[] destFrom,
            float[] destTo,
            float[] sourceFrom,
            float[] sourceTo,
            BlockState material) {
        for (Direction face : Direction.values()) {
            float[] positions = ResourcePackGeometry.faceCorners(destFrom, destTo, face);
            transform.apply(positions);
            float[] uvs = ResourcePackGeometry.uvCorners(
                    ResourcePackGeometry.autoUv(sourceFrom, sourceTo, face), 0);
            if (transform.mirrored()) reverseWinding(positions, uvs);
            // The copied material is evaluated in world orientation. Sampling
            // the pre-rotation face used the wrong CT neighbors on assembled
            // rotated byte panels, even when the underlying material matched.
            Direction worldFace = physicalFace(positions, face);
            Appearance appearance = appearance(material, worldFace);
            out.add(new ModelQuad(null, worldFace, positions, uvs,
                    appearance.texture(), appearance.tint()));
        }
    }

    private void addQuad(
            List<ModelQuad> out,
            Transform transform,
            Direction sourceFace,
            BlockState material,
            float[] a, float[] b, float[] c, float[] d) {
        float[] positions = new float[]{
                a[0],a[1],a[2], b[0],b[1],b[2], c[0],c[1],c[2], d[0],d[1],d[2]
        };
        transform.apply(positions);
        float[] uvs = new float[]{0,16, 16,16, 16,0, 0,0};
        if (transform.mirrored()) reverseWinding(positions, uvs);
        Direction worldFace = physicalFace(positions, sourceFace);
        Appearance appearance = appearance(material, worldFace);
        out.add(new ModelQuad(null, worldFace, positions, uvs,
                appearance.texture(), appearance.tint()));
    }

    /** Recover the outward face after copying, rotating or mirroring geometry. */
    static Direction physicalFace(float[] vertices, Direction fallback) {
        if (vertices == null || vertices.length < 9) return fallback;
        float ax=vertices[3]-vertices[0], ay=vertices[4]-vertices[1],
                az=vertices[5]-vertices[2];
        float bx=vertices[6]-vertices[0], by=vertices[7]-vertices[1],
                bz=vertices[8]-vertices[2];
        float x=ay*bz-az*by, y=az*bx-ax*bz, z=ax*by-ay*bx;
        float largest=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));
        if (largest<0.0001f) return fallback;
        if (Math.abs(x)==largest) return x>0 ? Direction.EAST : Direction.WEST;
        if (Math.abs(y)==largest) return y>0 ? Direction.UP : Direction.DOWN;
        return z>0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static float[] p(float x, float y, float z) {
        return new float[]{x,y,z};
    }

    private static void reverseWinding(float[] positions, float[] uvs) {
        swapVertex(positions, 1, 3, 3);
        swapVertex(uvs, 1, 3, 2);
    }

    private static void swapVertex(float[] values, int a, int b, int stride) {
        for (int i = 0; i < stride; i++) {
            float tmp = values[a * stride + i];
            values[a * stride + i] = values[b * stride + i];
            values[b * stride + i] = tmp;
        }
    }

    private Appearance appearance(BlockState material, Direction surface) {
        BlockRenderContext context = renderContext.get();
        List<ModelQuad> quads = context == null
                ? models.quadsFor(material)
                : models.quadsFor(context.withState(material));
        ModelQuad chosen = null;
        for (ModelQuad quad : quads) {
            if (quad.cullFace() == surface) {
                chosen = quad;
                break;
            }
            if (chosen == null && quad.shadeFace() == surface) chosen = quad;
        }
        if (chosen == null && !quads.isEmpty()) chosen = quads.getFirst();

        if (TRACE && TRACE_LINES.getAndIncrement() < 60) {
            LOGGER.debug("COPYCATS-MOVING-TRACE phase=MATERIAL material={} face={} sampledQuads={} matchedTexture={} matchedExists={} chosenCull={} chosenShade={} sampledContext={}",
                    BuiltInRegistries.BLOCK.getKey(material.getBlock()),
                    surface, quads.size(), chosen == null ? "<null>" : chosen.texture(),
                    chosen != null && chosen.texture() != null
                            && models.texture(chosen.texture()) != null,
                    chosen == null ? null : chosen.cullFace(),
                    chosen == null ? null : chosen.shadeFace(),
                    context == null ? "state-only" : "neighbor-aware");
        }
        if (chosen != null && chosen.texture() != null && models.texture(chosen.texture()) != null) {
            return new Appearance(chosen.texture(), chosen.tint());
        }
        String particle = models.particleTexture(material);
        if (particle != null && models.texture(particle) != null) {
            return new Appearance(particle, 0xFFFFFF);
        }
        return new Appearance("minecraft:block/stone", 0xFFFFFF);
    }

    private static BlockState materialFor(CompoundTag root, String part) {
        CompoundTag data = findMaterialData(root, 0);
        BlockState specific = null;
        if (part != null && data != null && data.contains(part, Tag.TAG_COMPOUND)) {
            specific = parseMaterial(findMaterialCompound(data.getCompound(part), 0));
            if (usable(specific)) return specific;
        }

        BlockState direct = parseMaterial(findMaterialCompound(root, 0));
        if (usable(direct)) return direct;

        if (data != null) {
            for (String key : data.getAllKeys()) {
                if (!data.contains(key, Tag.TAG_COMPOUND)) continue;
                BlockState parsed = parseMaterial(findMaterialCompound(data.getCompound(key), 0));
                if (usable(parsed)) return parsed;
            }
        }
        return specific;
    }

    private static CompoundTag findMaterialData(CompoundTag tag, int depth) {
        if (tag == null || depth > 8) return null;
        if (tag.contains("material_data", Tag.TAG_COMPOUND)) {
            CompoundTag data = tag.getCompound("material_data");
            if (!data.isEmpty()) return data;
        }
        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                CompoundTag found = findMaterialData(compound, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static CompoundTag findMaterialCompound(CompoundTag tag, int depth) {
        if (tag == null || depth > 8) return null;
        for (String key : List.of("material", "Material")) {
            if (tag.contains(key, Tag.TAG_COMPOUND)) {
                CompoundTag value = tag.getCompound(key);
                if (!value.isEmpty()) return value;
            }
        }
        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                CompoundTag found = findMaterialCompound(compound, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static BlockState parseMaterial(CompoundTag material) {
        if (material == null || material.isEmpty()) return null;
        try {
            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), material);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static boolean usable(BlockState material) {
        if (material == null) return false;
        String id = BuiltInRegistries.BLOCK.getKey(material.getBlock()).toString();
        return !"create:copycat_base".equals(id) && !"copycats:copycat_base".equals(id);
    }

    private static String materialKeys(CompoundTag metadata) {
        CompoundTag data = findMaterialData(metadata, 0);
        return data == null ? "<none>" : data.getAllKeys().toString();
    }

    private static boolean booleanProperty(BlockState state, String name) {
        return "true".equals(stringProperty(state, name));
    }

    private static int intProperty(BlockState state, String name) {
        try {
            return Integer.parseInt(stringProperty(state, name));
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

    private static String stringProperty(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) return valueName(state, property);
        }
        return "";
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private record Appearance(String texture, int tint) {}

    /** Small transform stack matching Copycats+'s 90-degree AssemblyTransform operations. */
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