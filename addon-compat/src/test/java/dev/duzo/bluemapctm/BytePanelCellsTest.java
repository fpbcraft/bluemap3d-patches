package dev.duzo.bluemapctm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BytePanelCellsTest {
    @Test
    void upperAndLowerPartsAreDifferentEvenInsideOneBytePanelBlock() {
        assertEquals("top_left", BytePanelCells.partAt("south",1,1,1));
        assertEquals("bottom_left", BytePanelCells.partAt("south",1,0,1));
        assertEquals("top_right", BytePanelCells.partAt("south",0,1,1));
        assertNull(BytePanelCells.partAt("south",1,1,0));
    }

    @Test
    void matchingUpperCellsAcrossVerticallyAdjacentBlocksKeepTheirHalf() {
        // The topmost half of the bottom block (virtual cell 1) meets the
        // bottom half of the block above (virtual cell 2), never a generic
        // material sampled from somewhere else in the neighboring block.
        assertEquals(0, BytePanelCells.worldBlockOffset(1));
        assertEquals(1, BytePanelCells.worldBlockOffset(2));
        assertEquals(0, BytePanelCells.localCell(2));
        assertEquals("top_left", BytePanelCells.partAt("south",1,1,1));
        assertEquals("bottom_left", BytePanelCells.partAt("south",1,0,1));
    }

    @Test
    void allPanelFacesSelectTheirOutwardHalfCellOnly() {
        assertEquals("top_left", BytePanelCells.partAt("north",0,1,0));
        assertEquals("top_left", BytePanelCells.partAt("east",1,1,0));
        assertEquals("top_left", BytePanelCells.partAt("west",0,1,1));
        assertEquals("top_left", BytePanelCells.partAt("up",1,1,0));
        assertEquals("top_left", BytePanelCells.partAt("down",1,0,1));
        assertNull(BytePanelCells.partAt("north",0,1,1));
    }

    @Test
    void negativeNeighborUsesFloorDivAndFloorMod() {
        assertEquals(-1, BytePanelCells.worldBlockOffset(-1));
        assertEquals(1, BytePanelCells.localCell(-1));
    }
}
