package dev.duzo.bluemap3d.bake;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConnectedTextureLayoutTest {

    @Test
    void fusionFullUsesAllFortySevenCanonicalTiles() {
        java.util.Set<Integer> tiles = new java.util.HashSet<>();
        for (int mask = 0; mask < 256; mask++) {
            tiles.add(ConnectedTextureLayout.fusionTile("full", mask));
        }

        assertEquals(47, tiles.size());
        assertEquals(0, ConnectedTextureLayout.fusionTile("full", 0));
        assertEquals(
                18,
                ConnectedTextureLayout.fusionTile(
                        "full",
                        ConnectedTextureLayout.TOP
                                | ConnectedTextureLayout.TOP_RIGHT
                                | ConnectedTextureLayout.RIGHT
                                | ConnectedTextureLayout.BOTTOM_RIGHT
                                | ConnectedTextureLayout.BOTTOM
                                | ConnectedTextureLayout.BOTTOM_LEFT
                                | ConnectedTextureLayout.LEFT
                                | ConnectedTextureLayout.TOP_LEFT));
    }

    @Test
    void fusionSimpleAndDirectionalLayoutsIgnoreDiagonalNoise() {
        int direct = ConnectedTextureLayout.TOP | ConnectedTextureLayout.RIGHT;
        int noisy = direct | ConnectedTextureLayout.BOTTOM_LEFT | ConnectedTextureLayout.TOP_LEFT;

        assertEquals(
                ConnectedTextureLayout.fusionTile("simple", direct),
                ConnectedTextureLayout.fusionTile("simple", noisy));
        assertEquals(1, ConnectedTextureLayout.fusionTile("horizontal", ConnectedTextureLayout.RIGHT));
        assertEquals(3, ConnectedTextureLayout.fusionTile("vertical", ConnectedTextureLayout.TOP));
    }

    @Test
    void fusionPiecedCanUseWholeTilesAndCornerPieces() {
        int all = 0xFF;
        assertEquals(1, ConnectedTextureLayout.fusionPiecedWholeTile(all));
        assertEquals(0, ConnectedTextureLayout.fusionPiecedWholeTile(0));

        int partial = ConnectedTextureLayout.TOP | ConnectedTextureLayout.LEFT;
        assertEquals(-1, ConnectedTextureLayout.fusionPiecedWholeTile(partial));
        assertEquals(
                4,
                ConnectedTextureLayout.fusionPiecedCornerTile(true, true, partial));
    }

    @Test
    void fusionOverlayEmitsNothingWhenDisconnected() {
        assertTrue(ConnectedTextureLayout.fusionOverlayTiles(0).isEmpty());

        List<Integer> surrounded = ConnectedTextureLayout.fusionOverlayTiles(
                ConnectedTextureLayout.TOP
                        | ConnectedTextureLayout.RIGHT
                        | ConnectedTextureLayout.BOTTOM
                        | ConnectedTextureLayout.LEFT);
        assertEquals(List.of(10), surrounded);
    }

    @Test
    void createCornersRequireTheirAdjacentCardinalConnections() {
        int topRightWithoutRight =
                ConnectedTextureLayout.TOP | ConnectedTextureLayout.TOP_RIGHT;
        assertEquals(
                ConnectedTextureLayout.TOP,
                ConnectedTextureResolver.constrainCreateCorners(topRightWithoutRight));

        int validTopRight =
                ConnectedTextureLayout.TOP
                        | ConnectedTextureLayout.RIGHT
                        | ConnectedTextureLayout.TOP_RIGHT;
        assertEquals(
                validTopRight,
                ConnectedTextureResolver.constrainCreateCorners(validTopRight));
    }

    @Test
    void createLayoutsMatchExpectedSheetCoordinates() {
        assertEquals(
                0,
                ConnectedTextureLayout.createTile("omnidirectional", 0));
        assertEquals(
                3,
                ConnectedTextureLayout.createTile(
                        "horizontal",
                        ConnectedTextureLayout.LEFT | ConnectedTextureLayout.RIGHT));
        assertEquals(
                6,
                ConnectedTextureLayout.createTile(
                        "rectangle",
                        ConnectedTextureLayout.LEFT
                                | ConnectedTextureLayout.RIGHT
                                | ConnectedTextureLayout.TOP
                                | ConnectedTextureLayout.BOTTOM));
    }
    @Test
    void movingCopiedMaterialConnectsAfterWrapperSubstitution() {
        // The sampler replaces the wrapper with the copied material before
        // connected-texture resolution. A wrapper-only check always failed.
        assertTrue(ConnectedTextureResolver.connectsResolvedCopiedMaterial(
                true, true, false));
        org.junit.jupiter.api.Assertions.assertFalse(
                ConnectedTextureResolver.connectsResolvedCopiedMaterial(false, true, false));
        org.junit.jupiter.api.Assertions.assertFalse(
                ConnectedTextureResolver.connectsResolvedCopiedMaterial(true, false, false));
        org.junit.jupiter.api.Assertions.assertFalse(
                ConnectedTextureResolver.connectsResolvedCopiedMaterial(true, true, true));
    }

    @Test
    void assembledRotatedCopycatsUsePhysicalSurfaceNormals() {
        assertEquals(net.minecraft.core.Direction.SOUTH,
                CopycatsSpecialSource.physicalFace(new float[]{
                        0,0,16, 16,0,16, 16,16,16, 0,16,16},
                        net.minecraft.core.Direction.NORTH));
        assertEquals(net.minecraft.core.Direction.WEST,
                CopycatsSpecialSource.physicalFace(new float[]{
                        0,0,0, 0,0,16, 0,16,16, 0,16,0},
                        net.minecraft.core.Direction.EAST));
        assertEquals(net.minecraft.core.Direction.UP,
                CopycatsSpecialSource.physicalFace(new float[]{
                        0,16,0, 0,16,16, 16,16,16, 16,16,0},
                        net.minecraft.core.Direction.DOWN));
    }

    @Test
    void railwaysVerticalPinkmachineUsesItsCustomTwoByTwoOrdering() {
        assertEquals(0, ConnectedTextureLayout.createTile("vertical_pinkmachine", 0));
        assertEquals(
                2,
                ConnectedTextureLayout.createTile(
                        "vertical_pinkmachine", ConnectedTextureLayout.TOP));
        assertEquals(
                3,
                ConnectedTextureLayout.createTile(
                        "vertical_pinkmachine", ConnectedTextureLayout.BOTTOM));
        assertEquals(
                1,
                ConnectedTextureLayout.createTile(
                        "vertical_pinkmachine",
                        ConnectedTextureLayout.TOP | ConnectedTextureLayout.BOTTOM));
        assertEquals(2, ConnectedTextureLayout.createGrid("vertical_pinkmachine").width());
        assertEquals(2, ConnectedTextureLayout.createGrid("vertical_pinkmachine").height());
    }


}
