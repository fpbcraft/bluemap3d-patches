package dev.duzo.bluemapctm;

import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CopiedMaterialConnectedTexturesTest {
    @Test
    void preservesCreateOmnidirectionalAtlasCoordinates() {
        ResourcePath<Texture> sheet = new ResourcePath<>("bluemap_compat:virtual/create/example/sheet");
        var uv = CopiedMaterialConnectedTextures.tiled(
                sheet, ConnectedTextureLayout.createGrid("omnidirectional"), 9);
        assertEquals(0.125f, uv.u0(), 0.00001f);
        assertEquals(0.125f, uv.v0(), 0.00001f);
        assertEquals(0.250f, uv.u1(), 0.00001f);
        assertEquals(0.250f, uv.v1(), 0.00001f);
    }

    @Test
    void preservesFusionSheetCoordinates() {
        ResourcePath<Texture> sheet = new ResourcePath<>("bluemap_compat:virtual/fusion/example/sheet");
        var uv = CopiedMaterialConnectedTextures.tiled(
                sheet, ConnectedTextureLayout.fusionGrid("full"), 17);
        assertEquals(0.125f, uv.u0(), 0.00001f);
        assertEquals(2f / 6f, uv.v0(), 0.00001f);
        assertEquals(0.250f, uv.u1(), 0.00001f);
        assertEquals(3f / 6f, uv.v1(), 0.00001f);
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
