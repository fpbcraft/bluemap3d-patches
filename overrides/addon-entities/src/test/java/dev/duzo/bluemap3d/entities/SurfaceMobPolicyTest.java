package dev.duzo.bluemap3d.entities;

import dev.duzo.bluemap3d.api.SceneObjectLifecycle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
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
    void providerRestoresMobsButDoesNotRecordHistory() {
        assertEquals(SceneObjectLifecycle.RESTORE_ONLY, new SurfaceMobProvider().lifecycle());
    }

    @Test
    void keepsMobsWhenMinecraftUnloadsThemForPersistence() {
        assertFalse(SurfaceMobProvider.shouldForget(Entity.RemovalReason.UNLOADED_TO_CHUNK));
        assertFalse(SurfaceMobProvider.shouldForget(Entity.RemovalReason.UNLOADED_WITH_PLAYER));
    }

    @Test
    void forgetsMobsThatWereActuallyRemoved() {
        assertTrue(SurfaceMobProvider.shouldForget(Entity.RemovalReason.KILLED));
        assertTrue(SurfaceMobProvider.shouldForget(Entity.RemovalReason.DISCARDED));
        assertTrue(SurfaceMobProvider.shouldForget(Entity.RemovalReason.CHANGED_DIMENSION));
        assertFalse(SurfaceMobProvider.shouldForget(null));
    }

    @Test
    void modelLocationPreservesModNamespaceWithoutRegistration() {
        ResourceLocation ostrich = ResourceLocation.fromNamespaceAndPath("examplemod", "ostrich");
        assertEquals(
                ResourceLocation.fromNamespaceAndPath("examplemod", "entity/ostrich/main"),
                SurfaceMobProvider.modelLocation(ostrich));
    }
}
