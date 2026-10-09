package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.Chunk;
import de.bluecolored.bluemap.core.world.LightData;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeasonalSurfaceTest {
    private Chunk terrain(String surface, boolean lit, int blockLight) {
        return new Chunk() {
            @Override public boolean isGenerated() { return true; }
            @Override public boolean hasWorldSurfaceHeights() { return true; }
            @Override public boolean hasLightData() { return lit; }
            @Override public int getWorldSurfaceY(int x, int z) { return 65; }
            @Override public BlockState getBlockState(int x, int y, int z) {
                return y > 64 ? BlockState.AIR : new BlockState(surface);
            }
            @Override public LightData getLightData(int x, int y, int z, LightData target) {
                return target.set(15, blockLight);
            }
        };
    }
    @Test void savedChunkNeedsNoLiveLevel() {
        SeasonalSurface surface = SeasonalSurface.sample(terrain("minecraft:grass_block", true, 0), -1, -513, -64, 319);
        assertEquals(65, surface.height());
        assertEquals(1, surface.kind());
        assertTrue(surface.snow());
    }
    @Test void missingLightAndHeatDoNotGainSnow() {
        assertFalse(SeasonalSurface.sample(terrain("minecraft:stone", false, 0), 0, 0, -64, 319).snow());
        assertFalse(SeasonalSurface.sample(terrain("minecraft:stone", true, 12), 0, 0, -64, 319).snow());
    }
    @Test void roofsWaterAndUnknownBlocksStopSurfaceScan() {
        for (String block : new String[]{"minecraft:oak_planks", "minecraft:water", "mod:machine"}) {
            SeasonalSurface surface = SeasonalSurface.sample(terrain(block, true, 0), 0, 0, -64, 319);
            assertEquals(0, surface.kind()); assertFalse(surface.snow());
        }
    }
    @Test void absentChunksAndHeightmapsStayUnknown() {
        assertEquals(SeasonalSurface.EMPTY, SeasonalSurface.sample(Chunk.EMPTY_CHUNK, 0, 0, -64, 319));
        assertEquals(SeasonalSurface.EMPTY, SeasonalSurface.sample(new Chunk() {
            @Override public boolean isGenerated() { return true; }
        }, 0, 0, -64, 319));
    }
}
