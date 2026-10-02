package dev.duzo.bluemap3d.bake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityModelSourceTest {

    @Test
    void layerNameCannotCreateCrossEntityMatch() {
        assertEquals(
                0,
                EntityAssetMatch.score(
                        "cow",
                        "minecraft:sheep#fur",
                        "fur"));
    }

    @Test
    void exactEntityLayerRanksStrongly() {
        assertTrue(
                EntityAssetMatch.score(
                        "sheep",
                        "minecraft:sheep#fur",
                        "fur") > 0);
    }

    @Test
    void nestedModGeoModelMatchesRegistryId() {
        assertTrue(
                EntityAssetMatch.score(
                        "giraffe",
                        "assets/naturalist/geo/entity/giraffe_baby.geo.json",
                        "main") > 0);
    }
    @Test
    void babyAppearanceOutranksAdultGeoModel() {
        var metadata = java.util.Map.of("__bm3d_visual_age", "baby");

        int adult = EntityAssetMatch.score(
                "giraffe",
                "assets/naturalist/geo/entity/giraffe.geo.json",
                "main")
                + EntityAssetMatch.appearanceScore(
                        metadata,
                        "assets/naturalist/geo/entity/giraffe.geo.json");
        int baby = EntityAssetMatch.score(
                "giraffe",
                "assets/naturalist/geo/entity/giraffe_baby.geo.json",
                "main")
                + EntityAssetMatch.appearanceScore(
                        metadata,
                        "assets/naturalist/geo/entity/giraffe_baby.geo.json");

        assertTrue(baby > adult);
    }

}
