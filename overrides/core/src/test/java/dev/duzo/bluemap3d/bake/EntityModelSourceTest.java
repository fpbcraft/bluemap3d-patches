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
    void familySuffixMayBeOmittedFromGeoFilename() {
        assertTrue(
                EntityAssetMatch.score(
                        "badlands_creeper",
                        "assets/creeperoverhaul/geo/badlands.geo.json",
                        "main") > 0);
    }

    @Test
    void familyPrefixMaySelectSharedGeoModel() {
        assertTrue(
                EntityAssetMatch.score(
                        "elokosa_follower_howler",
                        "assets/mowziesmobs/geo/elokosa.geo.json",
                        "main") > 0);
    }

    @Test
    void sharedFamilyTokenCannotSelectDifferentGeoEntity() {
        assertEquals(
                0,
                EntityAssetMatch.geometryScore(
                        "belgian_horse",
                        "assets/icys-better-horses/geo/horse_cart.geo.json",
                        "main"));
    }

    @Test
    void shorterSharedFamilyGeoNameStillMatchesEntity() {
        assertTrue(
                EntityAssetMatch.geometryScore(
                        "badlands_creeper",
                        "assets/creeperoverhaul/geo/badlands.geo.json",
                        "main") > 0);
    }

    @Test
    void entitySpecificHorseGeoStillMatches() {
        assertTrue(
                EntityAssetMatch.geometryScore(
                        "belgian_horse",
                        "assets/icys-better-horses/geo/belgian_horse.geo.json",
                        "main") > 0);
    }

    @Test
    void textureDirectoryMayCarryEntityVariantName() {
        assertTrue(
                EntityAssetMatch.score(
                        "ocean_creeper",
                        "assets/creeperoverhaul/textures/entity/ocean/brown_1.png",
                        "main") > 0);
    }

    @Test
    void glowTextureRanksBelowMainTexture() {
        int main = EntityAssetMatch.score(
                "badlands_creeper",
                "assets/creeperoverhaul/textures/entity/badlands/badlands_creeper.png",
                "main");
        int glow = EntityAssetMatch.score(
                "badlands_creeper",
                "assets/creeperoverhaul/textures/entity/badlands/badlands_creeper_glow.png",
                "main");

        assertTrue(main > glow);
    }

    @Test
    void normalizesRendererTextureLocationForBlueMapLookup() {
        assertEquals(
                "icys-better-horses:entity/horse/belgian/sabino",
                EntityModelSource.exactTextureOverride(
                        java.util.Map.of(
                                "__bm3d_texture_main",
                                "icys-better-horses:textures/entity/horse/belgian/sabino.png"),
                        "main"));
    }

    @Test
    void exactTextureOverrideIsLayerSpecific() {
        var metadata = java.util.Map.of(
                "__bm3d_texture_main",
                "example:textures/entity/horse/coat.png");

        assertEquals(
                "example:entity/horse/coat",
                EntityModelSource.exactTextureOverride(metadata, "main"));
        assertEquals(null, EntityModelSource.exactTextureOverride(metadata, "armor"));
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
