package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Element;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.world.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * Reconstructs fallback Copycats template geometry from resource-pack models.
 *
 * <p>The terrain renderer owns Copycats material selection and BlueMap emission. This class
 * only resolves the Copycats/Create Connected base template and applies blockstate and
 * element transforms to its faces.
 */
final class CopycatsTemplateGeometry {

    private final ResourcePack resourcePack;

    CopycatsTemplateGeometry(ResourcePack resourcePack) {
        this.resourcePack = resourcePack;
    }

    List<TemplateQuad> quads(String id, BlockState state) {
        String modelId = modelId(id);
        if (modelId == null) return List.of();

        Model model = resourcePack.getModel(new ResourcePath<>(modelId));
        if (model == null) return List.of();

        model.applyParent(resourcePack);
        Element[] elements = model.getElements();
        if (elements == null || elements.length == 0) return List.of();

        Transform blockTransform = stateTransform(state);
        List<TemplateQuad> out = new ArrayList<>();
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

            float[][] corners = {
                    point(minX, minY, minZ), point(minX, minY, maxZ),
                    point(maxX, minY, minZ), point(maxX, minY, maxZ),
                    point(minX, maxY, minZ), point(minX, maxY, maxZ),
                    point(maxX, maxY, minZ), point(maxX, maxY, maxZ)
            };

            face(out, element, Direction.DOWN, blockTransform,
                    corners[0], corners[2], corners[3], corners[1]);
            face(out, element, Direction.UP, blockTransform,
                    corners[5], corners[7], corners[6], corners[4]);
            face(out, element, Direction.NORTH, blockTransform,
                    corners[2], corners[0], corners[4], corners[6]);
            face(out, element, Direction.SOUTH, blockTransform,
                    corners[1], corners[3], corners[7], corners[5]);
            face(out, element, Direction.WEST, blockTransform,
                    corners[0], corners[1], corners[5], corners[4]);
            face(out, element, Direction.EAST, blockTransform,
                    corners[3], corners[2], corners[6], corners[7]);
        }
        return out;
    }

    private static void face(
            List<TemplateQuad> out,
            Element element,
            Direction direction,
            Transform blockTransform,
            float[] a, float[] b, float[] c, float[] d) {
        if (!element.getFaces().containsKey(direction)) return;

        float[] positions = {
                a[0], a[1], a[2],
                b[0], b[1], b[2],
                c[0], c[1], c[2],
                d[0], d[1], d[2]
        };
        applyElementRotation(positions, element);
        blockTransform.apply(positions);
        if (blockTransform.mirrored()) reverseWinding(positions);
        out.add(new TemplateQuad(positions, direction));
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

    private static Transform stateTransform(BlockState state) {
        Transform transform = new Transform();

        String axis = property(state, "axis");
        if ("x".equals(axis)) transform.rotateZ(90);
        if ("z".equals(axis)) transform.rotateX(90);

        String facing = property(state, "facing");
        if (!facing.isEmpty()) {
            switch (facing) {
                case "north", "south", "east", "west" -> transform.rotateY(yRotation(facing));
                case "up" -> { }
                case "down" -> transform.rotateX(180);
                default -> { }
            }
        }

        if ("top".equals(property(state, "half"))) transform.flipY(true);
        if ("ceiling".equals(property(state, "face"))) transform.flipY(true);

        return transform;
    }

    static String modelId(String id) {
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

    private static String property(BlockState state, String name) {
        return state.getProperties().getOrDefault(name, "");
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

    private static float[] point(float x, float y, float z) {
        return new float[]{x, y, z};
    }

    private static void reverseWinding(float[] positions) {
        for (int i = 0; i < 3; i++) {
            float tmp = positions[3 + i];
            positions[3 + i] = positions[9 + i];
            positions[9 + i] = tmp;
        }
    }

    record TemplateQuad(float[] positions, Direction face) {
    }

    private static final class Transform {
        private static final int RX = 1, RY = 2, RZ = 3, FX = 4, FY = 5, FZ = 6;
        private final List<Integer> ops = new ArrayList<>();
        private boolean mirrored;

        Transform rotateX(int degrees) { addRot(RX, degrees); return this; }
        Transform rotateY(int degrees) { addRot(RY, degrees); return this; }
        Transform rotateZ(int degrees) { addRot(RZ, degrees); return this; }
        Transform flipY(boolean yes) {
            if (yes) {
                ops.add(FY);
                mirrored = !mirrored;
            }
            return this;
        }
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
                    float nx = x;
                    float ny = y;
                    float nz = z;
                    switch (op) {
                        case FX -> nx = 16 - x;
                        case FY -> ny = 16 - y;
                        case FZ -> nz = 16 - z;
                        case RX -> {
                            ny = 16 - z;
                            nz = y;
                        }
                        case RY -> {
                            nx = 16 - z;
                            nz = x;
                        }
                        case RZ -> {
                            nx = 16 - y;
                            ny = x;
                        }
                        default -> { }
                    }
                    x = nx;
                    y = ny;
                    z = nz;
                }
                positions[i] = x;
                positions[i + 1] = y;
                positions[i + 2] = z;
            }
        }
    }
}
