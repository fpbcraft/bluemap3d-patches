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
    void leavesOtherConnectedTextureBlocksEligible() {
        assertTrue(ConnectedTextureRoutingRules.shouldRoute("create:andesite_casing"));
    }
}
