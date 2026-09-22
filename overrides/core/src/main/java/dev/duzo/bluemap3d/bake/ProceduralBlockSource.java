package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reconstructs geometry that Create/Simulated/Offroad add in client renderers instead
 * of their ordinary blockstate JSON.
 *
 * <p>This deliberately has no compile-time dependency on those mods. It keys off registry
 * ids, block-state properties and the block-entity NBT snapshot already carried by
 * {@link dev.duzo.bluemap3d.api.BlockVolume}, so the source remains harmless when none of
 * the supported mods are installed.
 */
public final class ProceduralBlockSource implements BlockModelSource {
    private final ResourcePackSource models;

    public ProceduralBlockSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return quadsFor(state, null);
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state, CompoundTag metadata) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return switch (id) {
            case "create:fluid_pipe" -> fluidPipe(state);
            case "create:mechanical_roller" -> mechanicalRoller(state);
            case "offroad:wheel_mount" -> wheelMount(state, metadata);
            case "offroad:rockcutting_wheel" -> rockCuttingWheel(state);
            default -> List.of();
        };
    }

    @Override
    public BufferedImage texture(String texture) {
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return false;
    }

    /**
     * Create's fluid-pipe blockstate contains only the 8x8x8 core. The arms from the
     * core to each connected face are added by PipeAttachmentModel on the client.
     */
    private List<ModelQuad> fluidPipe(BlockState state) {
        List<ModelQuad> out = new ArrayList<>(models.quadsFor(state));
        for (Direction direction : Direction.values()) {
            if (!booleanProperty(state, direction.getName())) continue;
            addModel(out, "create:block/fluid_pipe/connection/" + direction.getName(),
                    0, 0, 0, 0, 0, 0);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    /**
     * A mechanical roller's JSON block model is only its casing. The metal frame and
     * roller wheel are PartialModels emitted by RollerRenderer.
     *
     * <p>The wheel is frozen at zero animation angle. BlueMap3D already animates the
     * containing contraption; reproducing wheel spin independently is not necessary to
     * recover the correct silhouette.
     */
    private List<ModelQuad> mechanicalRoller(BlockState state) {
        List<ModelQuad> out = new ArrayList<>(models.quadsFor(state));
        Direction facing = horizontalFacing(state);
        double y = blockstateY(facing);

        addModel(out, "create:block/mechanical_roller/frame",
                0, y, 0, 0, 0, 0);

        // RollerRenderer places the wheel just beyond the facing side, four model pixels
        // lower than the block origin, then turns the wheel model 90 degrees around Y.
        addModel(out, "create:block/mechanical_roller/wheel",
                0, y + 90.0, 0,
                facing.getStepX() * 17.0,
                -4.0,
                facing.getStepZ() * 17.0);

        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    /**
     * Offroad's suspension block stores its installed tire in CurrentStack. The static
     * block model only contains the mount; WheelMountRenderer draws the tire separately.
     * Use the same item/partial models at the suspension's neutral extension.
     */
    private List<ModelQuad> wheelMount(BlockState state, CompoundTag metadata) {
        List<ModelQuad> out = new ArrayList<>(models.quadsFor(state));
        String itemId = currentStackId(metadata);
        TireModel tire = tireModel(itemId);
        if (tire == null) {
            return out.isEmpty() ? List.of() : List.copyOf(out);
        }

        Direction facing = horizontalFacing(state);
        addModel(out, tire.model(),
                tire.rotateX(), blockstateY(facing), tire.rotateZ(),
                facing.getStepX() * 22.0,
                -8.0,
                facing.getStepZ() * 22.0);
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    /**
     * The rock-cutting wheel's base is in its blockstate; the cutting disc is a client
     * partial. Keeping it centred is preferable to the bare machine and is also the exact
     * model used when this block is installed as an Offroad tire.
     */
    private List<ModelQuad> rockCuttingWheel(BlockState state) {
        List<ModelQuad> out = new ArrayList<>(models.quadsFor(state));
        Direction facing = directionProperty(state, "facing", Direction.NORTH);
        double y = facing.getAxis().isHorizontal() ? blockstateY(facing) : 0.0;
        double x = facing == Direction.UP ? -90.0 : facing == Direction.DOWN ? 90.0 : 90.0;
        addModel(out, "offroad:block/rockcutting_wheel/wheel",
                x, y, 0,
                facing.getStepX() * 10.0,
                facing.getStepY() * 8.0,
                facing.getStepZ() * 10.0);
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private void addModel(
            List<ModelQuad> out,
            String modelId,
            double rotateX,
            double rotateY,
            double rotateZ,
            double translateX,
            double translateY,
            double translateZ) {
        ResourceLocation model = ResourceLocation.tryParse(modelId);
        if (model == null) return;

        List<ModelQuad> quads = models.quadsForModel(model, Map.of());
        for (ModelQuad quad : quads) {
            float[] positions = quad.positions().clone();
            transform(positions, rotateX, rotateY, rotateZ,
                    translateX, translateY, translateZ);
            // Client-rendered partials can extend outside the source block. Never let a
            // cardinal neighbour cull those faces.
            out.add(new ModelQuad(
                    null,
                    quad.shadeFace(),
                    positions,
                    quad.uvs().clone(),
                    quad.texture(),
                    quad.tint()));
        }
    }

    private static void transform(
            float[] positions,
            double rotateX,
            double rotateY,
            double rotateZ,
            double translateX,
            double translateY,
            double translateZ) {
        double rx = Math.toRadians(rotateX);
        double ry = Math.toRadians(rotateY);
        double rz = Math.toRadians(rotateZ);
        double cx = Math.cos(rx), sx = Math.sin(rx);
        double cy = Math.cos(ry), sy = Math.sin(ry);
        double cz = Math.cos(rz), sz = Math.sin(rz);

        for (int i = 0; i < positions.length; i += 3) {
            double x = positions[i] - 8.0;
            double y = positions[i + 1] - 8.0;
            double z = positions[i + 2] - 8.0;

            if (rotateX != 0.0) {
                double ny = y * cx - z * sx;
                double nz = y * sx + z * cx;
                y = ny;
                z = nz;
            }
            if (rotateY != 0.0) {
                double nx = x * cy - z * sy;
                double nz = x * sy + z * cy;
                x = nx;
                z = nz;
            }
            if (rotateZ != 0.0) {
                double nx = x * cz - y * sz;
                double ny = x * sz + y * cz;
                x = nx;
                y = ny;
            }

            positions[i] = (float) (x + 8.0 + translateX);
            positions[i + 1] = (float) (y + 8.0 + translateY);
            positions[i + 2] = (float) (z + 8.0 + translateZ);
        }
    }

    private static TireModel tireModel(String itemId) {
        if (itemId == null || itemId.isBlank() || "minecraft:air".equals(itemId)) {
            return null;
        }
        return switch (itemId) {
            case "offroad:small_tire" ->
                    new TireModel("offroad:item/small_tire", 90, 0);
            case "offroad:tire" ->
                    new TireModel("offroad:item/tire", 90, 0);
            case "offroad:large_tire" ->
                    new TireModel("offroad:item/large_tire", 90, 0);
            case "offroad:monstrous_tire" ->
                    new TireModel("offroad:item/monstrous_tire", 90, 0);
            case "offroad:rockcutting_wheel" ->
                    new TireModel("offroad:block/rockcutting_wheel/wheel", 90, 0);
            case "create:mechanical_roller" ->
                    new TireModel("create:block/mechanical_roller/wheel", 0, 0);
            case "create:crushing_wheel" ->
                    new TireModel("create:item/crushing_wheel", 90, 0);
            case "create:water_wheel" ->
                    new TireModel("create:item/water_wheel", 90, 0);
            case "create:large_water_wheel" ->
                    new TireModel("create:item/large_water_wheel", 90, 0);
            case "create:flywheel" ->
                    new TireModel("create:item/flywheel", 90, 0);
            default -> null;
        };
    }

    private static String currentStackId(CompoundTag metadata) {
        CompoundTag stack = findCompound(metadata, "CurrentStack", 0);
        if (stack == null) return null;
        if (stack.contains("id", Tag.TAG_STRING)) return stack.getString("id");
        if (stack.contains("Id", Tag.TAG_STRING)) return stack.getString("Id");
        return null;
    }

    private static CompoundTag findCompound(CompoundTag root, String name, int depth) {
        if (root == null || depth > 8) return null;
        if (root.contains(name, Tag.TAG_COMPOUND)) {
            CompoundTag result = root.getCompound(name);
            if (!result.isEmpty()) return result;
        }
        for (String key : root.getAllKeys()) {
            Tag child = root.get(key);
            if (child instanceof CompoundTag compound) {
                CompoundTag found = findCompound(compound, name, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Direction horizontalFacing(BlockState state) {
        Direction facing = directionProperty(state, "facing", Direction.NORTH);
        return facing.getAxis().isHorizontal() ? facing : Direction.NORTH;
    }

    private static Direction directionProperty(BlockState state, String name, Direction fallback) {
        String value = stringProperty(state, name);
        Direction parsed = Direction.byName(value);
        return parsed == null ? fallback : parsed;
    }

    private static double blockstateY(Direction facing) {
        return switch (facing) {
            case EAST -> 90.0;
            case SOUTH -> 180.0;
            case WEST -> 270.0;
            default -> 0.0;
        };
    }

    private static boolean booleanProperty(BlockState state, String name) {
        return "true".equals(stringProperty(state, name));
    }

    private static String stringProperty(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name)) {
                return valueName(state, property);
            }
        }
        return "";
    }

    private static <T extends Comparable<T>> String valueName(
            BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    private record TireModel(String model, double rotateX, double rotateZ) {
    }
}
