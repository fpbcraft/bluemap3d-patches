package dev.duzo.bluemapctm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopiedMaterialConnectedTexturesTest {
    @Test
    void preservesCreateOmnidirectionalAtlasCoordinates() {
        var uv = CopiedMaterialConnectedTextures.tileUvs(
                ConnectedTextureLayout.createGrid("omnidirectional"), 9);
        assertEquals(0.125f, uv[0], 0.00001f);
        assertEquals(0.125f, uv[1], 0.00001f);
        assertEquals(0.250f, uv[2], 0.00001f);
        assertEquals(0.250f, uv[3], 0.00001f);
    }

    @Test
    void preservesFusionSheetCoordinates() {
        var uv = CopiedMaterialConnectedTextures.tileUvs(
                ConnectedTextureLayout.fusionGrid("full"), 17);
        assertEquals(0.125f, uv[0], 0.00001f);
        assertEquals(2f / 6f, uv[1], 0.00001f);
        assertEquals(0.250f, uv[2], 0.00001f);
        assertEquals(3f / 6f, uv[3], 0.00001f);
    }

    @Test
    void diagonalCannotConnectWithoutBothCardinalNeighbours() {
        int mask = ConnectedTextureLayout.TOP_RIGHT;
        assertEquals(0, CopiedMaterialConnectedTextures.constrainCorners(mask));
        int complete = ConnectedTextureLayout.TOP
                | ConnectedTextureLayout.RIGHT | ConnectedTextureLayout.TOP_RIGHT;
        assertEquals(complete, CopiedMaterialConnectedTextures.constrainCorners(complete));
    }
}
