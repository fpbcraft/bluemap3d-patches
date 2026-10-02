package dev.duzo.bluemap3d.bake;

import com.google.gson.JsonObject;
import net.minecraft.core.Direction;

/**
 * Pure block-model geometry helpers shared by resource-pack and procedural model sources.
 *
 * <p>This class owns Minecraft face/UV construction and model-space rotations. Resource
 * lookup and model-chain semantics stay in {@link ResourcePackModelResolver}; blockstate
 * and model orchestration stay in {@link ResourcePackSource}.
 */
final class ResourcePackGeometry {

    private ResourcePackGeometry() {
    }

    /**
     * Minecraft's default UV when a face declares none: sample the element footprint on
     * the face's axis pair, with V measured from the top.
     */
    static float[] autoUv(float[] from, float[] to, Direction face) {
        float x0 = from[0], y0 = from[1], z0 = from[2];
        float x1 = to[0], y1 = to[1], z1 = to[2];
        return switch (face) {
            case DOWN -> new float[]{x0, z0, x1, z1};
            case UP -> new float[]{x0, 16 - z1, x1, 16 - z0};
            case NORTH -> new float[]{16 - x1, 16 - y1, 16 - x0, 16 - y0};
            case SOUTH -> new float[]{x0, 16 - y1, x1, 16 - y0};
            case WEST -> new float[]{z0, 16 - y1, z1, 16 - y0};
            case EAST -> new float[]{16 - z1, 16 - y1, 16 - z0, 16 - y0};
        };
    }

    /**
     * Four corners of a face of the box {@code from..to}, ordered to match the UV window.
     */
    static float[] faceCorners(float[] from, float[] to, Direction face) {
        float x0 = from[0], y0 = from[1], z0 = from[2];
        float x1 = to[0], y1 = to[1], z1 = to[2];
        return switch (face) {
            case UP -> new float[]{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1};
            case DOWN -> new float[]{x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0};
            case NORTH -> new float[]{x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0};
            case SOUTH -> new float[]{x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1};
            case WEST -> new float[]{x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0};
            case EAST -> new float[]{x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1};
        };
    }

    /** Expands a {@code [u0,v0,u1,v1]} window to four corners, with face rotation. */
    static float[] uvCorners(float[] uv, int rotation) {
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};

        int steps = normaliseSteps(rotation);
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            float[] corner = corners[(i + steps) % 4];
            out[i * 2] = corner[0];
            out[i * 2 + 1] = corner[1];
        }
        return out;
    }

    /** Applies an element's {@code {origin, axis, angle}} rotation. */
    static void applyElementRotation(float[] corners, JsonObject rotation) {
        float angle = rotation.has("angle") ? rotation.get("angle").getAsFloat() : 0f;
        if (Math.abs(angle) < 1e-6f) {
            return;
        }

        float[] origin = rotation.has("origin")
                ? vec3(rotation.getAsJsonArray("origin"))
                : new float[]{8, 8, 8};
        String axis = rotation.has("axis") ? rotation.get("axis").getAsString() : "y";

        double radians = Math.toRadians(angle);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        for (int i = 0; i < 4; i++) {
            float x = corners[i * 3] - origin[0];
            float y = corners[i * 3 + 1] - origin[1];
            float z = corners[i * 3 + 2] - origin[2];
            float nx = x, ny = y, nz = z;
            switch (axis) {
                case "x" -> {
                    ny = y * cos - z * sin;
                    nz = y * sin + z * cos;
                }
                case "z" -> {
                    nx = x * cos - y * sin;
                    ny = x * sin + y * cos;
                }
                default -> {
                    nx = x * cos + z * sin;
                    nz = -x * sin + z * cos;
                }
            }
            corners[i * 3] = nx + origin[0];
            corners[i * 3 + 1] = ny + origin[1];
            corners[i * 3 + 2] = nz + origin[2];
        }
    }

    /**
     * Applies a blockstate variant's model rotation about the block centre.
     *
     * <p>The X steps intentionally rotate the negative way, matching vanilla's
     * {@code rotationXYZ(-x, -y, 0)} semantics.
     */
    static void applyVariantRotation(float[] corners, int rotX, int rotY) {
        int stepsX = normaliseSteps(rotX);
        double radiansY = Math.toRadians(rotY);
        double cosY = Math.cos(radiansY);
        double sinY = Math.sin(radiansY);

        for (int i = 0; i < 4; i++) {
            float x = corners[i * 3] - 8f;
            float y = corners[i * 3 + 1] - 8f;
            float z = corners[i * 3 + 2] - 8f;

            for (int step = 0; step < stepsX; step++) {
                // up -> north, matching rotateAroundX.
                float ny = z, nz = -y;
                y = ny;
                z = nz;
            }

            double nx = x * cosY - z * sinY;
            double nz = x * sinY + z * cosY;
            corners[i * 3] = (float) nx + 8f;
            corners[i * 3 + 1] = y + 8f;
            corners[i * 3 + 2] = (float) nz + 8f;
        }
    }

    /** Rotates a cull/shade direction with its model. */
    static Direction rotateDirection(Direction direction, int rotX, int rotY) {
        if (direction == null) {
            return null;
        }

        Direction out = direction;
        for (int step = 0; step < normaliseSteps(rotX); step++) {
            out = rotateAroundX(out);
        }
        for (int step = 0; step < normaliseSteps(rotY); step++) {
            out = out.getClockWise(Direction.Axis.Y);
        }
        return out;
    }

    private static Direction rotateAroundX(Direction direction) {
        return switch (direction) {
            case NORTH -> Direction.DOWN;
            case DOWN -> Direction.SOUTH;
            case SOUTH -> Direction.UP;
            case UP -> Direction.NORTH;
            default -> direction;
        };
    }

    private static int normaliseSteps(int degrees) {
        return ((degrees % 360) + 360) % 360 / 90;
    }

    private static float[] vec3(com.google.gson.JsonArray array) {
        return new float[]{
                array.get(0).getAsFloat(),
                array.get(1).getAsFloat(),
                array.get(2).getAsFloat()
        };
    }
}
