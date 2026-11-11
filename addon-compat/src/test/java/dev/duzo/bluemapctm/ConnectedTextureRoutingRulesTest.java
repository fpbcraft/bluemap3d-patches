package dev.duzo.bluemapctm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectedTextureRoutingRulesTest {

    @Test
    void excludesMetalGirderFromGenericConnectedTextureRenderer() {
        assertFalse(ConnectedTextureRoutingRules.shouldRoute("create:metal_girder"));
    }

    @Test
    void preservesAllRailwaysWindowPaletteFaces() {
        assertTrue(ConnectedTextureRoutingRules.keepsTransparentWindowFaces(
                "railways:brown_single_pane_locometal_window"));
        assertTrue(ConnectedTextureRoutingRules.keepsTransparentWindowFaces(
                "railways:blue_four_pane_locometal_window"));
        assertTrue(ConnectedTextureRoutingRules.keepsTransparentWindowFaces(
                "railways:olive_green_round_pane_locometal_window"));
        assertFalse(ConnectedTextureRoutingRules.keepsTransparentWindowFaces(
                "railways:brown_slash_locometal"));
        assertFalse(ConnectedTextureRoutingRules.keepsTransparentWindowFaces(
                "pretty_in_pink:black_brushed_steel"));
    }

    @Test
    void leavesOtherConnectedTextureBlocksEligible() {
        assertTrue(ConnectedTextureRoutingRules.shouldRoute("create:andesite_casing"));
    }
}
