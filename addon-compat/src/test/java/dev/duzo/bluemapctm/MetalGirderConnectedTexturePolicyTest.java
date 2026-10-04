package dev.duzo.bluemapctm;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetalGirderConnectedTexturePolicyTest {

    @Test
    void metalGirderUsesConnectedRendererOnlyForPoleState() {
        assertTrue(MetalGirderConnectedTexturePolicy.useConnectedRenderer(
                "create:metal_girder", Map.of("x", "false", "z", "false")));

        assertFalse(MetalGirderConnectedTexturePolicy.useConnectedRenderer(
                "create:metal_girder", Map.of("x", "true", "z", "false")));
        assertFalse(MetalGirderConnectedTexturePolicy.useConnectedRenderer(
                "create:metal_girder", Map.of("x", "false", "z", "true")));
        assertFalse(MetalGirderConnectedTexturePolicy.useConnectedRenderer(
                "create:metal_girder", Map.of("x", "true", "z", "true")));
    }

    @Test
    void otherBlocksKeepConnectedTextureRendering() {
        assertTrue(MetalGirderConnectedTexturePolicy.useConnectedRenderer(
                "create:andesite_casing", Map.of()));
    }
}
