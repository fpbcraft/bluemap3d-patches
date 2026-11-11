package dev.duzo.bluemap3d.bake;

import com.google.gson.JsonObject;
import net.minecraft.core.Direction;

/**
 * Minecraft-facing adapter for pure block-model geometry helpers.
 *
 * <p>Resource lookup and model-chain semantics stay in {@link ResourcePackModelResolver};
 * blockstate and model orchestration stay in {@link ResourcePackSource}. The numeric math
 * lives in {@link ResourcePackGeometryMath} so it can be regression-tested without a
 * Minecraft/Gson test classpath.
 */
final class ResourcePackGeometry {

    private ResourcePackGeometry() {
    }

    static float[] autoUv(float[] from, float[] to, Direction face) {
        return ResourcePackGeometryMath.autoUv(from, to, faceCode(face));
    }

    static float[] faceCorners(float[] from, float[] to, Direction face) {
        return ResourcePackGeometryMath.faceCorners(from, to, faceCode(face));
    }

    static float[] uvCorners(float[] uv, int rotation) {
        return ResourcePackGeometryMath.uvCorners(uv, rotation);
    }

    static void applyElementRotation(float[] corners, JsonObject rotation) {
        float angle = rotation.has("angle") ? rotation.get("angle").getAsFloat() : 0f;
        if (Math.abs(angle) < 1e-6f) {
            return;
        }

        float[] origin = rotation.has("origin")
                ? vec3(rotation.getAsJsonArray("origin"))
                : new float[]{8, 8, 8};
        String axis = rotation.has("axis") ? rotation.get("axis").getAsString() : "y";
        ResourcePackGeometryMath.applyElementRotation(corners, origin, axis, angle);
    }

    static void applyVariantRotation(float[] corners, int rotX, int rotY) {
        ResourcePackGeometryMath.applyVariantRotation(corners, rotX, rotY);
    }

    static Direction rotateDirection(Direction direction, int rotX, int rotY) {
        if (direction == null) {
            return null;
        }
        return directionOf(ResourcePackGeometryMath.rotateDirection(
                faceCode(direction), rotX, rotY));
    }

    private static int faceCode(Direction direction) {
        return switch (direction) {
            case DOWN -> ResourcePackGeometryMath.DOWN;
            case UP -> ResourcePackGeometryMath.UP;
            case NORTH -> ResourcePackGeometryMath.NORTH;
            case SOUTH -> ResourcePackGeometryMath.SOUTH;
            case WEST -> ResourcePackGeometryMath.WEST;
            case EAST -> ResourcePackGeometryMath.EAST;
        };
    }

    private static Direction directionOf(int direction) {
        return switch (direction) {
            case ResourcePackGeometryMath.DOWN -> Direction.DOWN;
            case ResourcePackGeometryMath.UP -> Direction.UP;
            case ResourcePackGeometryMath.NORTH -> Direction.NORTH;
            case ResourcePackGeometryMath.SOUTH -> Direction.SOUTH;
            case ResourcePackGeometryMath.WEST -> Direction.WEST;
            case ResourcePackGeometryMath.EAST -> Direction.EAST;
            default -> throw new IllegalArgumentException("Unknown direction code: " + direction);
        };
    }

    private static float[] vec3(com.google.gson.JsonArray array) {
        return new float[]{
                array.get(0).getAsFloat(),
                array.get(1).getAsFloat(),
                array.get(2).getAsFloat()
        };
    }
}
