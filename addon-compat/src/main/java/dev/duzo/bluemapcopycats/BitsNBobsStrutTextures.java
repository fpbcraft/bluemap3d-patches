package dev.duzo.bluemapcopycats;

final class BitsNBobsStrutTextures {

    private BitsNBobsStrutTextures() {
    }

    static String[] candidates(String id) {
        return switch (id) {
            case "bits_n_bobs:weathered_girder_strut" -> new String[]{
                    "bits_n_bobs:block/weathered_girder",
                    "bits_n_bobs:block/weathered_girder_attachment",
                    "bits_n_bobs:block/weathered_industrial_iron_block",
                    "create:block/industrial_iron_block"
            };
            case "bits_n_bobs:cable_girder_strut" -> new String[]{
                    "bits_n_bobs:block/cable",
                    "create:block/industrial_iron_block"
            };
            default -> new String[]{
                    // Bits 'n' Bobs' own girder item model uses Create's girder texture
                    // for the beam. Prefer this known-good resource before the B&B
                    // attachment texture, which can resolve to a missing-texture placeholder
                    // in dedicated-server resource loads.
                    "create:block/girder",
                    "bits_n_bobs:block/girder_attachment",
                    "create:block/industrial_iron_block"
            };
        };
    }
}
