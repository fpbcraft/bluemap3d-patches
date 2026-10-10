package dev.duzo.bluemap3d.api;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ScenePersistencePolicyTest {

    @Test
    void migrationsDropOnlyProvidersWhoseDeletionSemanticsChanged() {
        assertTrue(ScenePersistencePolicy.shouldDropForMigration("create_contraptions", 2));
        assertTrue(ScenePersistencePolicy.shouldDropForMigration("create_contraptions", 6));
        assertFalse(ScenePersistencePolicy.shouldDropForMigration("create_contraptions", 7));

        assertTrue(ScenePersistencePolicy.shouldDropForMigration("simulated_springs", 2));
        assertFalse(ScenePersistencePolicy.shouldDropForMigration("simulated_springs", 3));

        assertTrue(ScenePersistencePolicy.shouldDropForMigration("sable_ships", 3));
        assertFalse(ScenePersistencePolicy.shouldDropForMigration("sable_ships", 4));

        assertTrue(ScenePersistencePolicy.shouldDropForMigration("simulated_ropes", 4));
        assertFalse(ScenePersistencePolicy.shouldDropForMigration("simulated_ropes", 5));

        assertFalse(ScenePersistencePolicy.shouldDropForMigration("other_provider", 1));
    }

    @Test
    void authoritativeScopeDeletesOnlyMissingChildrenInSameProviderAndDimension() {
        assertTrue(
                ScenePersistencePolicy.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/segment-2",
                        false));

        assertFalse(
                ScenePersistencePolicy.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/segment-2",
                        true));

        assertFalse(
                ScenePersistencePolicy.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_ropes",
                        "minecraft:the_nether",
                        "rope-1/segment-2",
                        false));

        assertFalse(
                ScenePersistencePolicy.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_springs",
                        "minecraft:overworld",
                        "rope-1/segment-2",
                        false));

        assertFalse(
                ScenePersistencePolicy.isAuthoritativelyMissing(
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-1/",
                        "simulated_ropes",
                        "minecraft:overworld",
                        "rope-2/segment-0",
                        false));
    }
}
