package dev.duzo.bluemapcopycats;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CopycatsTransformTest {

    @Test
    void quarterTurnRotationsUseCopycatsBlockCoordinates() {
        float[] x = {1, 2, 3};
        new CopycatsTransform().rotateX(90).apply(x);
        assertArrayEquals(new float[]{1, 13, 2}, x);

        float[] y = {1, 2, 3};
        new CopycatsTransform().rotateY(90).apply(y);
        assertArrayEquals(new float[]{13, 2, 1}, y);

        float[] z = {1, 2, 3};
        new CopycatsTransform().rotateZ(90).apply(z);
        assertArrayEquals(new float[]{14, 1, 3}, z);
    }

    @Test
    void slopeHighEdgePointsTowardBlockFacingInAllFourDirections() {
        // Procedural Copycats slopes rise along +Z before facing rotation.
        String[] facings = {"south", "west", "north", "east"};
        int[] rotations = {0, 90, 180, 270};
        float[][] expected = {
                {8, 16, 16},  // south
                {0, 16, 8},   // west
                {8, 16, 0},   // north
                {16, 16, 8}   // east
        };
        for (int i = 0; i < facings.length; i++) {
            float[] highEdgeMidpoint = {8, 16, 16};
            new CopycatsTransform().rotateY(rotations[i]).apply(highEdgeMidpoint);
            assertArrayEquals(expected[i], highEdgeMidpoint, facings[i]);
        }
    }

    @Test
    void negativeQuarterTurnUsesEquivalentPositiveTurns() {
        float[] point = {1, 2, 3};
        new CopycatsTransform().rotateY(-90).apply(point);

        assertArrayEquals(new float[]{3, 2, 15}, point);
    }

    @Test
    void oneFlipMarksTransformMirroredAndReversesQuadWinding() {
        CopycatsTransform transform = new CopycatsTransform().flipX(true);
        float[] quad = {
                1, 2, 3,
                4, 5, 6,
                7, 8, 9,
                10, 11, 12
        };

        assertTrue(transform.mirrored());
        transform.applyQuad(quad);

        assertArrayEquals(new float[]{
                15, 2, 3,
                6, 11, 12,
                9, 8, 9,
                12, 5, 6
        }, quad);
    }

    @Test
    void twoFlipsRestoreOriginalWindingParity() {
        CopycatsTransform transform =
                new CopycatsTransform().flipX(true).flipY(true);

        assertFalse(transform.mirrored());

        float[] quad = {
                1, 2, 3,
                4, 5, 6,
                7, 8, 9,
                10, 11, 12
        };
        transform.applyQuad(quad);

        assertArrayEquals(new float[]{
                15, 14, 3,
                12, 11, 6,
                9, 8, 9,
                6, 5, 12
        }, quad);
    }
}
