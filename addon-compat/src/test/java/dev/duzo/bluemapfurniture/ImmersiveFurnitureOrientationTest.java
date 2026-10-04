package dev.duzo.bluemapfurniture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ImmersiveFurnitureOrientationTest {

    @Test
    void facingRotationMatchesImmersiveFurnitureBlockModelRotation() {
        assertFacing("north", new float[]{8F, 8F, 0F});
        assertFacing("east", new float[]{16F, 8F, 8F});
        assertFacing("south", new float[]{8F, 8F, 16F});
        assertFacing("west", new float[]{0F, 8F, 8F});
    }

    private static void assertFacing(String facing, float[] expected) {
        float[] point = {8F, 8F, 0F};
        ImmersiveFurnitureRenderer.rotateFacing(point, facing);
        assertArrayEquals(expected, point, 1.0e-4F);
    }
}
