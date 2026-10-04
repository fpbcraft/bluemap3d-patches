package dev.duzo.bluemapctm;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConnectedTextureLayoutTest {

    @Test
    void fusionFullMatchesAllFortySevenCanonicalTiles() {
        Set<Integer> tiles = new HashSet<>();
        for (int mask = 0; mask < 256; mask++) {
            tiles.add(ConnectedTextureLayout.fusionTile("full", mask));
        }

        assertEquals(47, tiles.size());
        assertEquals(0, ConnectedTextureLayout.fusionTile("full", 0));
        assertEquals(18, ConnectedTextureLayout.fusionTile("full", 0xFF));
    }

    @Test
    void fusionDirectionalAndCompositeLayoutsMatchUpstreamSemantics() {
        assertEquals(
                1,
                ConnectedTextureLayout.fusionTile(
                        "horizontal", ConnectedTextureLayout.RIGHT));
        assertEquals(
                3,
                ConnectedTextureLayout.fusionTile(
                        "vertical", ConnectedTextureLayout.TOP));

        assertEquals(0, ConnectedTextureLayout.fusionPiecedWholeTile(0));
        assertEquals(1, ConnectedTextureLayout.fusionPiecedWholeTile(0xFF));
        assertTrue(ConnectedTextureLayout.fusionOverlayTiles(0).isEmpty());

        int surrounded = ConnectedTextureLayout.TOP
                | ConnectedTextureLayout.RIGHT
                | ConnectedTextureLayout.BOTTOM
                | ConnectedTextureLayout.LEFT;
        assertEquals(List.of(10), ConnectedTextureLayout.fusionOverlayTiles(surrounded));
    }

    @Test
    void createLayoutsUseCreateSheetIndices() {
        assertEquals(
                3,
                ConnectedTextureLayout.createTile(
                        "horizontal",
                        ConnectedTextureLayout.LEFT | ConnectedTextureLayout.RIGHT));
        assertEquals(
                6,
                ConnectedTextureLayout.createTile(
                        "rectangle",
                        ConnectedTextureLayout.TOP
                                | ConnectedTextureLayout.RIGHT
                                | ConnectedTextureLayout.BOTTOM
                                | ConnectedTextureLayout.LEFT));
    }
}
