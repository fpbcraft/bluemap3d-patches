package dev.duzo.bluemapcopycats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopycatsQuadEmitterTest {
    private static void close(float actual, float expected) {
        assertEquals(expected, actual, 0.00001f);
    }

    @Test
    void rotatedCopycatFacesUseTheirPhysicalNormalsForMaterialAndCt() {
        // Axis-aligned face orientation changes when a Create copycat panel
        // rotates 180 degrees in the terrain renderer.
        assertEquals("SOUTH",
                CopycatsFaceOrientation.of(new float[]{
                        0,0,16, 16,0,16, 16,16,16, 0,16,16},
                        "NORTH"));
        assertEquals("WEST",
                CopycatsFaceOrientation.of(new float[]{
                        0,0,0, 0,0,16, 0,16,16, 0,16,0},
                        "EAST"));
        assertEquals("UP",
                CopycatsFaceOrientation.of(new float[]{
                        0,16,0, 0,16,16, 16,16,16, 16,16,0},
                        "DOWN"));
    }

    @Test
    void twoAdjacentBytePanelsSampleAdjacentHalvesOfTheSameCtTile() {
        float[] left = CopycatsQuadEmitter.projectedUvs(new float[]{
                0,0,16, 8,0,16, 8,8,16, 0,8,16
        }, 0.25f, 0.125f, 0.375f, 0.25f);
        float[] right = CopycatsQuadEmitter.projectedUvs(new float[]{
                8,0,16, 16,0,16, 16,8,16, 8,8,16
        }, 0.25f, 0.125f, 0.375f, 0.25f);

        // Neither quad stretches a full 16x16 tile over its eight-pixel width.
        close(left[0], 0.25f);
        close(left[2], 0.3125f);
        close(right[0], 0.3125f);
        close(right[2], 0.375f);
        // UVs agree exactly along the seam, including the vertical endpoints.
        close(left[2], right[0]);
        close(left[3], right[1]);
        close(left[4], right[6]);
        close(left[5], right[7]);
    }

    @Test
    void fullBlockSouthFaceKeepsOriginalTileCoordinates() {
        float[] uv = CopycatsQuadEmitter.projectedUvs(new float[]{
                0,0,16, 16,0,16, 16,16,16, 0,16,16
        }, 0.25f, 0.125f, 0.375f, 0.25f);
        close(uv[0], 0.25f);
        close(uv[1], 0.25f);
        close(uv[2], 0.375f);
        close(uv[3], 0.25f);
        close(uv[4], 0.375f);
        close(uv[5], 0.125f);
        close(uv[6], 0.25f);
        close(uv[7], 0.125f);
    }

    @Test
    void upAndDownFacesRetainDifferentZOrientations() {
        float[] top = CopycatsQuadEmitter.projectedUvs(new float[]{
                0,16,16, 16,16,16, 16,16,0, 0,16,0
        }, 0,0,1,1);
        float[] bottom = CopycatsQuadEmitter.projectedUvs(new float[]{
                0,0,0, 16,0,0, 16,0,16, 0,0,16
        }, 0,0,1,1);
        close(top[1], 1);
        close(top[5], 0);
        close(bottom[1], 1);
        close(bottom[5], 0);
    }

    @Test
    void eightSampledBytePiecesEachExposeThreeFaces() {
        int total = 0;
        for (int y = 0; y < 2; y++) {
            for (int z = 0; z < 2; z++) {
                for (int x = 0; x < 2; x++) {
                    int visible = 0;
                    for (int face = 0; face < 6; face++) {
                        if (!CopycatsTerrainRenderer.internalBytePieceFace(face, x, y, z))
                            visible++;
                    }
                    assertEquals(3, visible);
                    total += visible;
                }
            }
        }
        assertEquals(24, total);
    }

    @Test
    void remappedCopycatPieceSamplesCopiedSourcePixelsNotDestinationPixels() {
        // Source piece 12..16 is placed at destination 4..8. The copied
        // material must still come from the last four texture pixels.
        float[] source = new float[]{
                12,0,16, 16,0,16, 16,16,16, 12,16,16};
        float[] destination = new float[]{
                4,0,16, 8,0,16, 8,16,16, 4,16,16};
        float[] expected = CopycatsQuadEmitter.projectedUvs(
                source, 0.25f, 0.125f, 0.375f, 0.25f);
        float[] wronglyProjected = CopycatsQuadEmitter.projectedUvs(
                destination, 0.25f, 0.125f, 0.375f, 0.25f);
        close(expected[0], 0.34375f);
        close(expected[2], 0.375f);
        close(wronglyProjected[0], 0.28125f);
    }

    @Test
    void nonAxisAlignedSurfacesKeepExistingUvs() {
        float[] uv = CopycatsQuadEmitter.projectedUvs(new float[]{
                0,0,0, 16,0,0, 16,16,16, 0,16,16
        }, 0.25f, 0.125f, 0.375f, 0.25f);
        close(uv[0], 0.25f);
        close(uv[1], 0.25f);
        close(uv[2], 0.375f);
        close(uv[3], 0.25f);
        close(uv[4], 0.375f);
        close(uv[5], 0.125f);
    }
}
