package dev.duzo.bluemapctm;

import de.bluecolored.bluemap.core.world.BlockState;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectedTextureTerrainRendererTest {

    @Test
    void metalGirderUsesConnectedRendererOnlyForPoleState() {
        assertTrue(ConnectedTextureTerrainRenderer.useConnectedRenderer(
                new BlockState("create:metal_girder", Map.of("x", "false", "z", "false"))));

        assertFalse(ConnectedTextureTerrainRenderer.useConnectedRenderer(
                new BlockState("create:metal_girder", Map.of("x", "true", "z", "false"))));
        assertFalse(ConnectedTextureTerrainRenderer.useConnectedRenderer(
                new BlockState("create:metal_girder", Map.of("x", "false", "z", "true"))));
        assertFalse(ConnectedTextureTerrainRenderer.useConnectedRenderer(
                new BlockState("create:metal_girder", Map.of("x", "true", "z", "true"))));
    }

    @Test
    void otherBlocksStillUseConnectedRenderer() {
        assertTrue(ConnectedTextureTerrainRenderer.useConnectedRenderer(
                new BlockState("create:andesite_casing", Map.of())));
    }
}
