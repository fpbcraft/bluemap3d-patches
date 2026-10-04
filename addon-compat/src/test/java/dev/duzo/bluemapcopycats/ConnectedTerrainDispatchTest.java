package dev.duzo.bluemapcopycats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class ConnectedTerrainDispatchTest {

    @Test
    void mapsModdedConnectedResourcesToDiagonalGeneratedIds() {
        assertEquals(
                "diagonalfences:natures_spirit/wisteria_fence",
                ConnectedTerrainDispatch.diagonalAlias("natures_spirit:wisteria_fence"));
        assertEquals(
                "diagonalwalls:quark/shale_wall",
                ConnectedTerrainDispatch.diagonalAlias("quark:shale_wall"));
        assertEquals(
                "diagonalwindows:createdeco/industrial_iron_bars",
                ConnectedTerrainDispatch.diagonalAlias("createdeco:industrial_iron_bars"));
        assertEquals(
                "diagonalwindows:createdeco/industrial_iron_bars_overlay",
                ConnectedTerrainDispatch.diagonalAlias("createdeco:industrial_iron_bars_overlay"));
        assertEquals(
                "diagonalwindows:minecraft/white_stained_glass_pane",
                ConnectedTerrainDispatch.diagonalAlias("minecraft:white_stained_glass_pane"));
    }

    @Test
    void leavesUnrelatedAndSpecialRendererBlocksAlone() {
        assertNull(ConnectedTerrainDispatch.diagonalAlias("minecraft:stone"));
        assertNull(ConnectedTerrainDispatch.diagonalAlias("copycats:copycat_fence"));
        assertNull(ConnectedTerrainDispatch.diagonalAlias(
                "diagonalfences:natures_spirit/wisteria_fence"));
    }

    @Test
    void usesDiagonalBlocksCardinalSourceForEachDiagonalArm() {
        assertEquals("east", ConnectedTerrainRenderer.cardinalDirectionForDiagonal("north_east"));
        assertEquals("south", ConnectedTerrainRenderer.cardinalDirectionForDiagonal("south_east"));
        assertEquals("west", ConnectedTerrainRenderer.cardinalDirectionForDiagonal("south_west"));
        assertEquals("north", ConnectedTerrainRenderer.cardinalDirectionForDiagonal("north_west"));
    }

    @Test
    void generatedWindowAliasesAreConnectedBlocks() {
        assertEquals(true, ConnectedTerrainDispatch.isConnectedBlock(
                "diagonalwindows:createdeco/industrial_iron_bars"));
        assertEquals(true, ConnectedTerrainDispatch.isConnectedBlock(
                "diagonalwindows:createdeco/industrial_iron_bars_overlay"));
    }
}
