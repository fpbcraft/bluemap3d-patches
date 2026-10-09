package dev.duzo.bluemapseasons;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SeasonalSnowSurfaceTest {
    @Test void limitsCandidatesToOpaqueTerrain() {
        assertTrue(SeasonalSnowSurface.snowCandidate("minecraft:grass_block"));
        assertTrue(SeasonalSnowSurface.snowCandidate("minecraft:stone"));
        assertFalse(SeasonalSnowSurface.snowCandidate("minecraft:water"));
        assertFalse(SeasonalSnowSurface.snowCandidate("minecraft:oak_leaves"));
        assertFalse(SeasonalSnowSurface.snowCandidate("minecraft:snow"));
        assertTrue(SeasonalSnowSurface.snowCandidate("minecraft:grass_block[snowy=false]"));
    }

    @Test void resetIsSafeOnRepeatedCalls() {
        SeasonalSnowSurface.reset();
        SeasonalSnowSurface.reset();
    }
}
