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
    void createCopycatPanelsAndStepsUseDedicatedMaterialRenderer() {
        assertEquals(true, ConnectedTerrainDispatch.isCreateCopycat("create:copycat_panel"));
        assertEquals(true, ConnectedTerrainDispatch.isCreateCopycat("create:copycat_step"));
        assertEquals(false, ConnectedTerrainDispatch.isCreateCopycat("copycats:copycat_byte_panel"));
        assertEquals(false, ConnectedTerrainDispatch.isCreateCopycat("create:copycat_base"));
        assertEquals(false, ConnectedTerrainDispatch.isCreateCopycat("railways:copycat_headstock"));

        // They must bypass the fence/wall renderer rather than being mistaken for
        // normal connected blocks or diverted away from procedural geometry.
        assertEquals(false, ConnectedTerrainDispatch.isConnectedBlock("create:copycat_panel"));
        assertEquals(false, ConnectedTerrainDispatch.isConnectedBlock("create:copycat_step"));
    }

    @Test
    void generatedWindowAliasesAreConnectedBlocks() {
        assertEquals(true, ConnectedTerrainDispatch.isConnectedBlock(
                "diagonalwindows:createdeco/industrial_iron_bars"));
        assertEquals(true, ConnectedTerrainDispatch.isConnectedBlock(
                "diagonalwindows:createdeco/industrial_iron_bars_overlay"));
    }
}
