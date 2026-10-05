package dev.duzo.bluemap3d.publish;

import com.flowpowered.math.vector.Vector2i;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TileRefreshQueueTest {

    @Test
    void convertsBlockCoordinatesToMcaWorldRegions() {
        assertEquals(new Vector2i(0, 0),
                TileRefreshQueue.worldRegionFor(new BlockPos(0, 64, 0)));
        assertEquals(new Vector2i(0, 0),
                TileRefreshQueue.worldRegionFor(new BlockPos(511, 64, 511)));
        assertEquals(new Vector2i(1, 1),
                TileRefreshQueue.worldRegionFor(new BlockPos(512, 64, 512)));

        assertEquals(new Vector2i(-1, -1),
                TileRefreshQueue.worldRegionFor(new BlockPos(-1, 64, -1)));
        assertEquals(new Vector2i(-1, -1),
                TileRefreshQueue.worldRegionFor(new BlockPos(-512, 64, -512)));
        assertEquals(new Vector2i(-2, -2),
                TileRefreshQueue.worldRegionFor(new BlockPos(-513, 64, -513)));
    }
}
