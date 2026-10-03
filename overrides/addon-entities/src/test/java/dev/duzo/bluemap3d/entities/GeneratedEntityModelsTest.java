package dev.duzo.bluemap3d.entities;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedEntityModelsTest {

    @Test
    void bundlesExactModelsForScreenshotVanillaMobs() throws IOException {
        try (InputStream input = GeneratedEntityModelsTest.class.getResourceAsStream(
                "/assets/bluemap3d_entities/entity-models.json")) {
            assertNotNull(input, "generated vanilla entity model resource");
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            for (String model : new String[]{
                    "minecraft:sheep#main",
                    "minecraft:cow#main",
                    "minecraft:pig#main",
                    "minecraft:chicken#main"}) {
                assertTrue(json.contains("\"" + model + "\""),
                        () -> "missing generated model " + model);
            }
        }
    }
}
