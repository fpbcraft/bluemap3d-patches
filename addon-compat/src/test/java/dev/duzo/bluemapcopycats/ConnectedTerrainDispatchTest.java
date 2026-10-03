package dev.duzo.bluemapcopycats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class ConnectedTerrainDispatchTest {

    @Test
    void mapsModdedFenceAndWallResourcesToDiagonalGeneratedIds() {
        assertEquals(
                "diagonalfences:natures_spirit/wisteria_fence",
                ConnectedTerrainDispatch.diagonalAlias("natures_spirit:wisteria_fence"));
        assertEquals(
                "diagonalwalls:quark/shale_wall",
                ConnectedTerrainDispatch.diagonalAlias("quark:shale_wall"));
    }

    @Test
    void leavesUnrelatedAndSpecialRendererBlocksAlone() {
        assertNull(ConnectedTerrainDispatch.diagonalAlias("minecraft:stone"));
        assertNull(ConnectedTerrainDispatch.diagonalAlias("copycats:copycat_fence"));
        assertNull(ConnectedTerrainDispatch.diagonalAlias(
                "diagonalfences:natures_spirit/wisteria_fence"));
    }
}
