package dev.duzo.bluemapcopycats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopycatsQuadEmitterTest {
    private static void close(float actual, float expected) {
        assertEquals(expected, actual, 0.00001f);
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
