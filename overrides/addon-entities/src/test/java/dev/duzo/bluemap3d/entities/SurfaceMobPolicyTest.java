package dev.duzo.bluemap3d.entities;

import dev.duzo.bluemap3d.api.SceneObjectLifecycle;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SurfaceMobPolicyTest {
    @Test
    void acceptsMobStandingOnHeightmapSurface() {
        assertTrue(SurfaceMobPolicy.isAtOrAboveSurface(64.0, 64));
    }

    @Test
    void acceptsSmallInterpolationDifferenceAtSurface() {
        assertTrue(SurfaceMobPolicy.isAtOrAboveSurface(63.51, 64));
    }

    @Test
    void rejectsMobBelowRoofOrBridgeSurface() {
        assertFalse(SurfaceMobPolicy.isAtOrAboveSurface(64.0, 71));
    }

    @Test
    void acceptsFlyingMobAboveSurface() {
        assertTrue(SurfaceMobPolicy.isAtOrAboveSurface(100.0, 64));
    }

    @Test
    void providerNeverPersistsOrRecordsMobs() {
        assertEquals(SceneObjectLifecycle.LIVE_ONLY, new SurfaceMobProvider().lifecycle());
    }

    @Test
    void modelLocationPreservesModNamespaceWithoutRegistration() {
        ResourceLocation ostrich = ResourceLocation.fromNamespaceAndPath("examplemod", "ostrich");
        assertEquals(
                ResourceLocation.fromNamespaceAndPath("examplemod", "entity/ostrich/main"),
                SurfaceMobProvider.modelLocation(ostrich));
    }
}
