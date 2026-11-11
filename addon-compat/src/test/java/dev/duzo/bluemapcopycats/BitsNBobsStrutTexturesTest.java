package dev.duzo.bluemapcopycats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class BitsNBobsStrutTexturesTest {

    @Test
    void normalStrutPrefersCreatesRealGirderTexture() {
        assertArrayEquals(
                new String[]{
                        "create:block/girder",
                        "bits_n_bobs:block/girder_attachment",
                        "create:block/industrial_iron_block"
                },
                BitsNBobsStrutTextures.candidates("bits_n_bobs:girder_strut"));
    }

    @Test
    void cableAndWeatheredStrutsHaveSafeCreateFallbacks() {
        assertArrayEquals(
                new String[]{
                        "bits_n_bobs:block/cable",
                        "create:block/industrial_iron_block"
                },
                BitsNBobsStrutTextures.candidates("bits_n_bobs:cable_girder_strut"));

        assertArrayEquals(
                new String[]{
                        "bits_n_bobs:block/weathered_girder",
                        "bits_n_bobs:block/weathered_girder_attachment",
                        "bits_n_bobs:block/weathered_industrial_iron_block",
                        "create:block/industrial_iron_block"
                },
                BitsNBobsStrutTextures.candidates("bits_n_bobs:weathered_girder_strut"));
    }
}
