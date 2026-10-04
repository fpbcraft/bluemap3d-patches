package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
            case "immersive_furniture:furniture",
                 "immersive_furniture:furniture_entity",
                 "immersive_furniture:furniture_light" ->
                    immersiveFurniture(state, metadata);
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
        List<Direction> connected = new ArrayList<>(6);
        for (Direction direction : Direction.values()) {
            if (booleanProperty(state, direction.getName())) {
                connected.add(direction);
            }
        }

        // Create uses the compact connection partial for a true straight-through axis,
        // while elbows, tees and other junctions use the more detailed rim connector.
        // Neighbour-aware rim/drain selection is client-world dependent, but choosing the
        // right connector family from the blockstate preserves the important silhouette.
        boolean straight = connected.size() == 2
                && connected.get(0).getOpposite() == connected.get(1);
        String partial = straight ? "connection" : "rim_connector";

        for (Direction direction : connected) {
            addModel(out, "create:block/fluid_pipe/" + partial + "/" + direction.getName(),
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
                0, y, 0, 0, -4.0, 0);

        // RollerRenderer lowers the whole actor by 4 model pixels and the wheel by
        // another 8, places it just beyond the facing side, then turns the wheel model
        // 90 degrees around Y. Keep the zero-spin pose but match those fixed offsets.
        addModel(out, "create:block/mechanical_roller/wheel",
                0, y + 90.0, 0,
                facing.getStepX() * 17.0,
                -12.0,
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
                facing.getStepX() * 22.0 + tire.offsetX(),
                -8.0 + tire.offsetY(),
                facing.getStepZ() * 22.0 + tire.offsetZ());
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

    /**
     * Reconstructs Immersive Furniture's data-driven cuboids for moving block volumes.
     *
     * <p>The mod stores either the complete Furniture compound, a FurnitureHash, or a
     * low-memory identifier. Hash/identifier resolution is reflective so BlueMap3D keeps
     * no compile-time dependency on Immersive Furniture.
     */
    private List<ModelQuad> immersiveFurniture(BlockState state, CompoundTag metadata) {
        CompoundTag furniture = resolveFurnitureData(state, metadata);
        if (furniture == null || furniture.isEmpty()) return List.of();

        int visualState = booleanProperty(state, "active") ? 1 : 0;
        Direction facing = horizontalFacing(state);
        float offsetX = offset(metadata, "SubOffsetX") - 8F;
        float offsetY = offset(metadata, "SubOffsetY") - 8F;
        float offsetZ = offset(metadata, "SubOffsetZ") - 8F;

        ListTag elements = furniture.getList("Elements", Tag.TAG_COMPOUND);
        List<ModelQuad> out = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            CompoundTag element = elements.getCompound(i);
            String type = element.contains("Type", Tag.TAG_STRING)
                    ? element.getString("Type")
                    : "element";
            if (!"element".equalsIgnoreCase(type)) continue;

            int mask = element.contains("Mask", Tag.TAG_INT) ? element.getInt("Mask") : 3;
            if ((mask & (1 << visualState)) == 0) continue;

            float[] from = furnitureVector(element, "From");
            float[] to = furnitureVector(element, "To");
            if (from == null || to == null) continue;

            CompoundTag material = element.getCompound("Material");
            String materialId = material.contains("Source", Tag.TAG_STRING)
                    ? material.getString("Source")
                    : "minecraft:oak_log";

            String axis = element.contains("Axis", Tag.TAG_STRING)
                    ? element.getString("Axis")
                    : "y";
            float rotation = element.contains("Rotation", Tag.TAG_FLOAT)
                    ? element.getFloat("Rotation")
                    : 0F;

            addFurnitureCuboid(
                    out,
                    from,
                    to,
                    axis,
                    rotation,
                    facing,
                    offsetX,
                    offsetY,
                    offsetZ,
                    materialId);
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private void addFurnitureCuboid(
            List<ModelQuad> out,
            float[] from,
            float[] to,
            String axis,
            float rotation,
            Direction facing,
            float offsetX,
            float offsetY,
            float offsetZ,
            String materialId) {
        float minX = Math.min(from[0], to[0]);
        float minY = Math.min(from[1], to[1]);
        float minZ = Math.min(from[2], to[2]);
        float maxX = Math.max(from[0], to[0]);
        float maxY = Math.max(from[1], to[1]);
        float maxZ = Math.max(from[2], to[2]);

        if (Math.abs(maxX - minX) < 1.0e-5F
                || Math.abs(maxY - minY) < 1.0e-5F
                || Math.abs(maxZ - minZ) < 1.0e-5F) {
            return;
        }

        float[][] corners = {
                {minX,minY,minZ}, {minX,minY,maxZ},
                {maxX,minY,minZ}, {maxX,minY,maxZ},
                {minX,maxY,minZ}, {minX,maxY,maxZ},
                {maxX,maxY,minZ}, {maxX,maxY,maxZ}
        };

        float centerX = (minX + maxX) * 0.5F;
        float centerY = (minY + maxY) * 0.5F;
        float centerZ = (minZ + maxZ) * 0.5F;
        for (float[] point : corners) {
            rotateFurnitureElement(point, centerX, centerY, centerZ, axis, rotation);
            rotateFurnitureFacing(point, facing);
            point[0] += offsetX;
            point[1] += offsetY;
            point[2] += offsetZ;
        }

        addFurnitureFace(out, Direction.DOWN, materialId,
                corners[0],corners[2],corners[3],corners[1]);
        addFurnitureFace(out, Direction.UP, materialId,
                corners[5],corners[7],corners[6],corners[4]);
        addFurnitureFace(out, Direction.NORTH, materialId,
                corners[2],corners[0],corners[4],corners[6]);
        addFurnitureFace(out, Direction.SOUTH, materialId,
                corners[1],corners[3],corners[7],corners[5]);
        addFurnitureFace(out, Direction.WEST, materialId,
                corners[0],corners[1],corners[5],corners[4]);
        addFurnitureFace(out, Direction.EAST, materialId,
                corners[3],corners[2],corners[6],corners[7]);
    }

    private void addFurnitureFace(
            List<ModelQuad> out,
            Direction face,
            String materialId,
            float[] a,
            float[] b,
            float[] c,
            float[] d) {
        ModelQuad appearance = furnitureMaterialQuad(materialId, face);
        if (appearance == null) return;

        float[] positions = {
                a[0],a[1],a[2],
                b[0],b[1],b[2],
                c[0],c[1],c[2],
                d[0],d[1],d[2]
        };
        out.add(new ModelQuad(
                null,
                face,
                positions,
                new float[]{0F,16F, 16F,16F, 16F,0F, 0F,0F},
                appearance.texture(),
                appearance.tint()));
    }

    private ModelQuad furnitureMaterialQuad(String materialId, Direction face) {
        ResourceLocation id = ResourceLocation.tryParse(materialId);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return null;

        BlockState material = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        List<ModelQuad> quads = models.quadsFor(material);
        if (quads.isEmpty()) return null;

        for (ModelQuad quad : quads) {
            if (quad.shadeFace() == face && quad.texture() != null) return quad;
        }
        for (ModelQuad quad : quads) {
            if (quad.texture() != null) return quad;
        }
        return null;
    }

    private static CompoundTag resolveFurnitureData(BlockState state, CompoundTag metadata) {
        if (metadata != null && metadata.contains("Furniture", Tag.TAG_COMPOUND)) {
            CompoundTag inline = metadata.getCompound("Furniture");
            if (!inline.isEmpty()) return inline;
        }

        String hash = null;
        if (metadata != null && metadata.contains("FurnitureHash", Tag.TAG_STRING)) {
            hash = metadata.getString("FurnitureHash");
        }

        if (hash == null || hash.isBlank()) {
            String identifierValue = stringProperty(state, "identifier");
            if (!identifierValue.isBlank()) {
                try {
                    int identifier = Integer.parseInt(identifierValue);
                    String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    if ("immersive_furniture:furniture_light".equals(blockId)) {
                        identifier += 65536;
                    }

                    Class<?> registry =
                            Class.forName("net.conczin.immersive_furniture.data.FurnitureRegistry");
                    Object resolved = registry
                            .getMethod("resolve", int.class)
                            .invoke(null, identifier);
                    if (resolved instanceof String value) hash = value;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    return null;
                }
            }
        }

        if (hash == null || hash.isBlank()) return null;
        try {
            Class<?> manager =
                    Class.forName("net.conczin.immersive_furniture.data.FurnitureDataManager");
            Object data = manager.getMethod("getData", String.class).invoke(null, hash);
            if (data == null) return null;
            Object encoded = data.getClass().getMethod("toTag").invoke(data);
            return encoded instanceof CompoundTag tag ? tag : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static float[] furnitureVector(CompoundTag tag, String name) {
        ListTag list = tag.getList(name, Tag.TAG_FLOAT);
        if (list.size() < 3) return null;
        return new float[]{list.getFloat(0), list.getFloat(1), list.getFloat(2)};
    }

    private static float offset(CompoundTag metadata, String name) {
        if (metadata == null || !metadata.contains(name, Tag.TAG_INT)) return 8F;
        return metadata.getInt(name);
    }

    private static void rotateFurnitureElement(
            float[] point,
            float centerX,
            float centerY,
            float centerZ,
            String axis,
            float degrees) {
        if (Math.abs(degrees) < 1.0e-6F) return;

        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x = point[0] - centerX;
        double y = point[1] - centerY;
        double z = point[2] - centerZ;

        switch (axis.toLowerCase(java.util.Locale.ROOT)) {
            case "x" -> {
                double ny = y * cos - z * sin;
                double nz = y * sin + z * cos;
                y = ny;
                z = nz;
            }
            case "z" -> {
                double nx = x * cos - y * sin;
                double ny = x * sin + y * cos;
                x = nx;
                y = ny;
            }
            default -> {
                double nx = x * cos - z * sin;
                double nz = x * sin + z * cos;
                x = nx;
                z = nz;
            }
        }

        point[0] = (float) (x + centerX);
        point[1] = (float) (y + centerY);
        point[2] = (float) (z + centerZ);
    }

    private static void rotateFurnitureFacing(float[] point, Direction facing) {
        double degrees = switch (facing) {
            case EAST -> -90D;
            case SOUTH -> 180D;
            case WEST -> 90D;
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
                    new TireModel("offroad:item/small_tire", 90, 0, 0, 0, 0);
            case "offroad:tire" ->
                    new TireModel("offroad:item/tire", 90, 0, 0, 0, 0);
            case "offroad:large_tire" ->
                    new TireModel("offroad:item/large_tire", 90, 0, 0, 0, 0);
            case "offroad:monstrous_tire" ->
                    new TireModel("offroad:item/monstrous_tire", 90, 0, 0, 0, 0);
            case "offroad:rockcutting_wheel" ->
                    new TireModel("offroad:block/rockcutting_wheel/wheel", 90, 0, 0, 0, 0);
            case "create:mechanical_roller" ->
                    new TireModel("create:block/mechanical_roller/wheel", 0, 0, 0, -8, 0);
            case "create:crushing_wheel" ->
                    new TireModel("create:item/crushing_wheel", 90, 0, 0, 0, 0);
            case "create:water_wheel" ->
                    new TireModel("create:item/water_wheel", 90, 0, 0, 0, 0);
            case "create:large_water_wheel" ->
                    new TireModel("create:item/large_water_wheel", 90, 0, 0, 0, 0);
            case "create:flywheel" ->
                    new TireModel("create:item/flywheel", 90, 0, 0, 0, 0);
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

    private record TireModel(
            String model,
            double rotateX,
            double rotateZ,
            double offsetX,
            double offsetY,
            double offsetZ) {
    }
}
