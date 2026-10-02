package dev.duzo.bluemap3d.bake;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

final class ResourcePackGeometryTest {

    @Test
    void automaticUvsUseTheElementFootprint() {
        float[] from = {2f, 4f, 6f};
        float[] to = {10f, 12f, 14f};

        assertArrayEquals(
                new float[]{2f, 2f, 10f, 10f},
                ResourcePackGeometry.autoUv(from, to, Direction.UP),
                1e-5f);
        assertArrayEquals(
                new float[]{6f, 4f, 14f, 12f},
                ResourcePackGeometry.autoUv(from, to, Direction.NORTH),
                1e-5f);
    }

    @Test
    void faceCornersPreserveTheExpectedUvOrder() {
        float[] corners = ResourcePackGeometry.faceCorners(
                new float[]{2f, 4f, 6f},
                new float[]{10f, 12f, 14f},
                Direction.EAST);

        assertArrayEquals(
                new float[]{
                        10f, 12f, 14f,
                        10f, 12f, 6f,
                        10f, 4f, 6f,
                        10f, 4f, 14f
                },
                corners,
                1e-5f);
    }

    @Test
    void uvRotationUsesQuarterTurnSteps() {
        assertArrayEquals(
                new float[]{16f, 0f, 16f, 8f, 0f, 8f, 0f, 0f},
                ResourcePackGeometry.uvCorners(new float[]{0f, 0f, 16f, 8f}, 90),
                1e-5f);
        assertArrayEquals(
                new float[]{0f, 8f, 0f, 0f, 16f, 0f, 16f, 8f},
                ResourcePackGeometry.uvCorners(new float[]{0f, 0f, 16f, 8f}, -90),
                1e-5f);
    }

    @Test
    void variantRotationKeepsGeometryAndCullDirectionAligned() {
        float[] corners = repeatedPoint(8f, 16f, 8f);
        ResourcePackGeometry.applyVariantRotation(corners, 90, 0);

        assertArrayEquals(repeatedPoint(8f, 8f, 0f), corners, 1e-5f);
        assertEquals(
                Direction.NORTH,
                ResourcePackGeometry.rotateDirection(Direction.UP, 90, 0));

        float[] north = repeatedPoint(8f, 8f, 0f);
        ResourcePackGeometry.applyVariantRotation(north, 0, 90);
        assertArrayEquals(repeatedPoint(16f, 8f, 8f), north, 1e-5f);
        assertEquals(
                Direction.EAST,
                ResourcePackGeometry.rotateDirection(Direction.NORTH, 0, 90));
    }

    @Test
    void elementRotationUsesItsOwnOriginAndAxis() {
        JsonObject rotation = new JsonObject();
        rotation.addProperty("angle", 90);
        rotation.addProperty("axis", "y");
        JsonArray origin = new JsonArray();
        origin.add(8);
        origin.add(8);
        origin.add(8);
        rotation.add("origin", origin);

        float[] corners = repeatedPoint(16f, 8f, 8f);
        ResourcePackGeometry.applyElementRotation(corners, rotation);

        assertArrayEquals(repeatedPoint(8f, 8f, 0f), corners, 1e-5f);
    }

    private static float[] repeatedPoint(float x, float y, float z) {
        return new float[]{
                x, y, z,
                x, y, z,
                x, y, z,
                x, y, z
        };
    }
}
