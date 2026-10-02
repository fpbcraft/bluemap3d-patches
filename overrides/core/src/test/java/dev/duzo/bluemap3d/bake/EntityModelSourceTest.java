package dev.duzo.bluemap3d.bake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityModelSourceTest {

    @Test
    void layerNameCannotCreateCrossEntityMatch() {
        assertEquals(
                0,
                EntityModelSource.assetMatchScore(
                        "cow",
                        "minecraft:sheep#fur",
                        "fur"));
    }

    @Test
    void exactEntityLayerRanksStrongly() {
        assertTrue(
                EntityModelSource.assetMatchScore(
                        "sheep",
                        "minecraft:sheep#fur",
                        "fur") > 0);
    }

    @Test
    void nestedModGeoModelMatchesRegistryId() {
        assertTrue(
                EntityModelSource.assetMatchScore(
                        "giraffe",
                        "assets/naturalist/geo/entity/giraffe_baby.geo.json",
                        "main") > 0);
    }
}
