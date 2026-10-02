package dev.duzo.bluemap3d.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PersistentSceneObjectProviderContractTest {

    @Test
    void migrationsDropOnlyProvidersWhoseDeletionSemanticsChanged() {
        assertTrue(PersistentSceneObjectProvider.shouldDropForMigration("create_contraptions", 2));
        assertFalse(PersistentSceneObjectProvider.shouldDropForMigration("create_contraptions", 3));

        assertTrue(PersistentSceneObjectProvider.shouldDropForMigration("simulated_springs", 2));
        assertFalse(PersistentSceneObjectProvider.shouldDropForMigration("simulated_springs", 3));

        assertTrue(PersistentSceneObjectProvider.shouldDropForMigration("sable_ships", 3));
        assertFalse(PersistentSceneObjectProvider.shouldDropForMigration("sable_ships", 4));

        assertTrue(PersistentSceneObjectProvider.shouldDropForMigration("simulated_ropes", 4));
        assertFalse(PersistentSceneObjectProvider.shouldDropForMigration("simulated_ropes", 5));

        assertFalse(PersistentSceneObjectProvider.shouldDropForMigration("other_provider", 1));
    }

    @Test
    void authoritativeScopeDeletesOnlyMissingChildrenInSameProviderAndDimension() {
        assertTrue(
                PersistentSceneObjectProvider.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/segment-2",
                        false));

        assertFalse(
                PersistentSceneObjectProvider.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/segment-2",
                        true));

        assertFalse(
                PersistentSceneObjectProvider.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_ropes",
                        "minecraft:the_nether",
                        "rope-1/segment-2",
                        false));

        assertFalse(
                PersistentSceneObjectProvider.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_springs",
                        "minecraft:overworld",
                        "rope-1/segment-2",
                        false));

        assertFalse(
                PersistentSceneObjectProvider.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-2/segment-0",
                        false));
    }
}
