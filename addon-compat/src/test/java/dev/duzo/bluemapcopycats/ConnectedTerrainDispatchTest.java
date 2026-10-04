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
        assertEquals("north", DiagonalDirectionMapping.cardinalFor("north_east"));
        assertEquals("east", DiagonalDirectionMapping.cardinalFor("south_east"));
        assertEquals("south", DiagonalDirectionMapping.cardinalFor("south_west"));
        assertEquals("west", DiagonalDirectionMapping.cardinalFor("north_west"));
        assertEquals(-45f, DiagonalDirectionMapping.rotationDegrees());
    }

    @Test
    void identifiesFenceGeometryThatUsesDeterministicComposition() {
        assertEquals(true, ConnectedTerrainRenderer.isFence("minecraft:oak_fence"));
        assertEquals(true, ConnectedTerrainRenderer.isFence(
                "diagonalfences:minecraft/oak_fence"));
        assertEquals(false, ConnectedTerrainRenderer.isFence(
                "diagonalwindows:createdeco/industrial_iron_bars"));
    }

    @Test
    void generatedWindowAliasesAreConnectedBlocks() {
        assertEquals(true, ConnectedTerrainDispatch.isConnectedBlock(
                "diagonalwindows:createdeco/industrial_iron_bars"));
        assertEquals(true, ConnectedTerrainDispatch.isConnectedBlock(
                "diagonalwindows:createdeco/industrial_iron_bars_overlay"));
    }
}
