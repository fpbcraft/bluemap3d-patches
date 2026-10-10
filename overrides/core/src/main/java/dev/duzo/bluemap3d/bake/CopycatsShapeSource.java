package dev.duzo.bluemap3d.bake;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Material-aware fallback for copycats whose client geometry is emitted in code.
 *
 * <p>Runs before ordinary resource-pack resolution for material wrappers, since a
 * placeholder JSON model can otherwise prevent the copied material from rendering.
 */
public final class CopycatsShapeSource implements BlockModelSource {

    private static final int MAX_BOXES = 128;
    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/CopycatsTrace");
    private static final boolean TRACE = Boolean.getBoolean("bluemap.copycats.trace");
    private static final AtomicInteger TRACE_LINES = new AtomicInteger();


    private final ResourcePackSource models;

    public CopycatsShapeSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return List.of();
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state, CompoundTag metadata) {
        return build(state, metadata, null);
    }

    @Override
    public List<ModelQuad> quadsFor(BlockRenderContext context) {
        return build(context.state(), context.blockEntityData(), context);
    }

    private List<ModelQuad> build(
            BlockState state,
            CompoundTag metadata,
            BlockRenderContext context) {
        if (!CopiedMaterialResolver.isMaterialWrapper(state) || metadata == null) {
            return List.of();
        }

        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        // Railways copycat headstocks have real JSON geometry with non-copycat details and
        // are handled by RailwaysCopycatHeadstockSource before the resource-pack path.
        if ("railways".equals(id.getNamespace())) return List.of();

        BlockState material = CopiedMaterialResolver.materialFor(metadata, null);
        if (!CopiedMaterialResolver.usable(material)) return List.of();

        List<AABB> boxes = boxesOf(state);
        if (boxes.isEmpty() || boxes.size() > MAX_BOXES) return List.of();

        List<ModelQuad> out = new ArrayList<>(boxes.size() * 6);
        for (AABB box : boxes) {
            float[] from = {
                    (float) box.minX * 16f,
                    (float) box.minY * 16f,
                    (float) box.minZ * 16f
            };
            float[] to = {
                    (float) box.maxX * 16f,
                    (float) box.maxY * 16f,
                    (float) box.maxZ * 16f
            };
            if (to[0] - from[0] < 1e-4f
                    || to[1] - from[1] < 1e-4f
                    || to[2] - from[2] < 1e-4f) {
                continue;
            }

            for (Direction face : Direction.values()) {
                Appearance appearance = appearance(material, face, context);
                out.add(new ModelQuad(
                        cullFaceOf(from, to, face),
                        face,
                        ResourcePackGeometry.faceCorners(from, to, face),
                        ResourcePackGeometry.uvCorners(
                                ResourcePackGeometry.autoUv(from, to, face), 0),
                        appearance.texture(),
                        appearance.tint()));
            }
        }

        if (TRACE && TRACE_LINES.getAndIncrement() < 60) {
            long missingFace = out.stream()
                    .filter(q -> q.shadeFace() == null && q.cullFace() == null).count();
            java.util.Set<String> textures = new java.util.TreeSet<>();
            for (ModelQuad quad : out) {
                if (quad.texture() != null && textures.size() < 12)
                    textures.add(quad.texture());
            }
            LOGGER.debug("COPYCATS-MOVING-TRACE phase=SHAPE block={} pos={} material={} metaKeys={} boxes={} quads={} missingFace={} textures={}",
                    id, context == null ? "<none>" : context.x()+","+context.y()+","+context.z(),
                    BuiltInRegistries.BLOCK.getKey(material.getBlock()),
                    metadata.getAllKeys(), boxes.size(), out.size(), missingFace, textures);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    @Override
    public BufferedImage texture(String texture) {
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return false;
    }

    @Override
    public boolean isFaithful() {
        return false;
    }

    @Override
    public String approximation() {
        return "copycat voxel shape";
    }

    private Appearance appearance(
            BlockState material,
            Direction surface,
            BlockRenderContext context) {
        List<ModelQuad> materialQuads = context == null
                ? models.quadsFor(material)
                : models.quadsFor(context.withState(material));
        ModelQuad chosen = null;
        for (ModelQuad quad : materialQuads) {
            if (quad.cullFace() == surface) {
                chosen = quad;
                break;
            }
            if (chosen == null && quad.shadeFace() == surface) chosen = quad;
        }
        if (chosen == null && !materialQuads.isEmpty()) chosen = materialQuads.getFirst();

        if (chosen != null
                && chosen.texture() != null
                && models.texture(chosen.texture()) != null) {
            return new Appearance(chosen.texture(), chosen.tint());
        }

        String particle = models.particleTexture(material);
        if (particle != null && models.texture(particle) != null) {
            return new Appearance(particle, 0xFFFFFF);
        }

        return new Appearance("minecraft:block/stone", 0xFFFFFF);
    }

    private static List<AABB> boxesOf(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if ("create".equals(id.getNamespace()) && "copycat_panel".equals(id.getPath())) {
            // Create's CopycatPanelBlock uses AllShapes.CASING_3PX. The prior
            // one-pixel approximation made panels nearly invisible on trains.
            // Its getShape is state-only, not world-dependent.
            try {
                VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (!shape.isEmpty()) return shape.toAabbs();
            } catch (RuntimeException ignored) {
                // Canonical 3px fallback below.
            }
            return createPanelFallback(property(state, "facing"));
        }
        if ("create".equals(id.getNamespace()) && "copycat_step".equals(id.getPath())) {
            try {
                VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
                if (!shape.isEmpty()) return shape.toAabbs();
            } catch (RuntimeException ignored) {
                // Canonical STEP_BOTTOM / STEP_TOP fallback below.
            }
            return createStepFallback(property(state, "facing"), property(state, "half"));
        }
        try {
            VoxelShape shape = state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            if (shape.isEmpty()) {
                shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            }
            return shape.isEmpty() ? List.of() : shape.toAabbs();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    static List<AABB> createPanelFallback(String facing) {
        double p = 3.0 / 16.0;
        return switch (facing) {
            case "down" -> List.of(new AABB(0, 1-p, 0, 1, 1, 1));
            case "north" -> List.of(new AABB(0, 0, 0, 1, 1, p));
            case "south" -> List.of(new AABB(0, 0, 1-p, 1, 1, 1));
            case "west" -> List.of(new AABB(0, 0, 0, p, 1, 1));
            case "east" -> List.of(new AABB(1-p, 0, 0, 1, 1, 1));
            default -> List.of(new AABB(0, 0, 0, 1, p, 1));
        };
    }

    static List<AABB> createStepFallback(String facing, String half) {
        double bottom = "top".equals(half) ? 0.5 : 0.0;
        double top = bottom + 0.5;
        return switch (facing) {
            case "north" -> List.of(new AABB(0, bottom, 0, 1, top, 0.5));
            case "east" -> List.of(new AABB(0.5, bottom, 0, 1, top, 1));
            case "west" -> List.of(new AABB(0, bottom, 0, 0.5, top, 1));
            default -> List.of(new AABB(0, bottom, 0.5, 1, top, 1));
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String property(BlockState state, String name) {
        for (net.minecraft.world.level.block.state.properties.Property property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return property.getName(state.getValue(property));
            }
        }
        return "";
    }

    private static Direction cullFaceOf(float[] from, float[] to, Direction face) {
        boolean flush = switch (face) {
            case DOWN -> from[1] <= 0f;
            case UP -> to[1] >= 16f;
            case NORTH -> from[2] <= 0f;
            case SOUTH -> to[2] >= 16f;
            case WEST -> from[0] <= 0f;
            case EAST -> to[0] >= 16f;
        };
        return flush ? face : null;
    }

    private record Appearance(String texture, int tint) {}
}
