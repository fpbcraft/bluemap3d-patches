package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ProceduralFurnitureOrientationTest {

    @Test
    void facingRotationMatchesImmersiveFurnitureBlockModelRotation() {
        assertFacing(Direction.NORTH, new float[]{8F, 8F, 0F});
        assertFacing(Direction.EAST, new float[]{16F, 8F, 8F});
        assertFacing(Direction.SOUTH, new float[]{8F, 8F, 16F});
        assertFacing(Direction.WEST, new float[]{0F, 8F, 8F});
    }

    private static void assertFacing(Direction facing, float[] expected) {
        float[] point = {8F, 8F, 0F};
        ProceduralBlockSource.rotateFurnitureFacing(point, facing);
        assertArrayEquals(expected, point, 1.0e-4F);
    }
}
