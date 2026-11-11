package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Explicit server-side model for Simulated's symmetric sails.
 *
 * <p>The original model is a 4-pixel-thick slab. The broad faces use Create's sail
 * canvas texture and the four edges use Simulated's color-specific side texture.
 * Reconstructing it directly also gives us a diagnostic boundary: if this source never
 * logs for a ship, the sail blocks are missing from Sable's captured BlockVolume rather
 * than failing model resolution.
 */
public final class SymmetricSailSource implements BlockModelSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/SymmetricSail");
    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();

    private final ResourcePackSource models;

    public SymmetricSailSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return quadsFor(state, null);
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state, CompoundTag metadata) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!"simulated".equals(id.getNamespace())) {
            return List.of();
        }

        String path = id.getPath();
        if (!path.endsWith("_symmetric_sail")) {
            return List.of();
        }

        String color = path.substring(0, path.length() - "_symmetric_sail".length());
        if (color.isBlank()) {
            color = "white";
        }

        Direction.Axis axis = axis(state);
        float[] from = {0f, 0f, 0f};
        float[] to = {16f, 16f, 16f};
        switch (axis) {
            case X -> {
                from[0] = 6f;
                to[0] = 10f;
            }
            case Y -> {
                from[1] = 6f;
                to[1] = 10f;
            }
            case Z -> {
                from[2] = 6f;
                to[2] = 10f;
            }
        }

        String canvas = "create:block/sail/canvas_" + color;
        String side = "simulated:block/symmetric_sail/side_" + color;
        List<ModelQuad> out = new ArrayList<>(6);

        for (Direction face : Direction.values()) {
            boolean broadFace = face.getAxis() == axis;
            float[] uv = ResourcePackGeometry.uvCorners(
                    broadFace
                            ? new float[] {0f, 0f, 16f, 16f}
                            : new float[] {0f, 0f, 16f, 4f},
                    broadFace && face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                            ? 180
                            : 0);

            out.add(new ModelQuad(
                    broadFace ? null : face,
                    face,
                    ResourcePackGeometry.faceCorners(from, to, face),
                    uv,
                    broadFace ? canvas : side,
                    0xFFFFFF));
        }

        String traceKey = id + "|" + axis;
        if (TRACED.add(traceKey)) {
            LOGGER.debug(
                    "SYMMETRIC-SAIL-DIAG block={} axis={} quads={} canvas={} side={}",
                    id, axis, out.size(), canvas, side);
        }

        return List.copyOf(out);
    }

    @Override
    public BufferedImage texture(String texture) {
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return false;
    }

    private static Direction.Axis axis(BlockState state) {
        for (Property<?> property : state.getProperties()) {
            if (!"axis".equals(property.getName())) continue;
            String value = valueName(state, property);
            return switch (value) {
                case "x" -> Direction.Axis.X;
                case "z" -> Direction.Axis.Z;
                default -> Direction.Axis.Y;
            };
        }
        return Direction.Axis.Y;
    }

    private static <T extends Comparable<T>> String valueName(
            BlockState state,
            Property<T> property) {
        return property.getName(state.getValue(property));
    }
}
