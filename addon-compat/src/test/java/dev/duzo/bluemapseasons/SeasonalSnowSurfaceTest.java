package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.core.util.math.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeasonalSnowSurfaceTest {
    @Test void limitsCandidatesToOpaqueTerrain() {
        assertTrue(SeasonalSnowSurface.snowCandidate("minecraft:grass_block"));
        assertTrue(SeasonalSnowSurface.snowCandidate("minecraft:stone"));
        assertFalse(SeasonalSnowSurface.snowCandidate("minecraft:water"));
        assertFalse(SeasonalSnowSurface.snowCandidate("minecraft:oak_leaves"));
        assertFalse(SeasonalSnowSurface.snowCandidate("minecraft:snow"));
    }

    @Test void tintPreservesAlphaAndBrightens() {
        Color color = new Color().set(0x80802020);
        SeasonalSnowSurface.snowTint(color);
        assertEquals(0x80, (color.getInt() >>> 24));
        assertTrue(((color.getInt() >>> 16) & 0xff) > 0x80);
    }

    @Test void resetIsSafeOnRepeatedCalls() {
        SeasonalSnowSurface.reset();
        SeasonalSnowSurface.reset();
    }
}
