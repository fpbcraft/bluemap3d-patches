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
                10,
                ConnectedTextureLayout.createTile(
                        "rectangle",
                        ConnectedTextureLayout.LEFT
                                | ConnectedTextureLayout.RIGHT
                                | ConnectedTextureLayout.TOP
                                | ConnectedTextureLayout.BOTTOM));
    }
}
