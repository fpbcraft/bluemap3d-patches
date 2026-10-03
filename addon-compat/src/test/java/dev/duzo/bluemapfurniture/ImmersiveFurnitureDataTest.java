package dev.duzo.bluemapfurniture;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImmersiveFurnitureDataTest {

    @Test
    void decodesPersistedElementGeometryAndMaterial() {
        Map<String, Object> element = Map.of(
                "From", List.of(1.0F, 2.0F, 3.0F),
                "To", List.of(9.0F, 10.0F, 11.0F),
                "Axis", "z",
                "Rotation", 22.5F,
                "Type", "element",
                "Mask", 2,
                "Emission", 7,
                "Material", Map.of("Source", "minecraft:spruce_planks"));

        var definition = ImmersiveFurnitureData.decodeNbt(
                Map.of("Elements", List.of(element)));

        assertNotNull(definition);
        assertEquals(1, definition.elements().size());

        var decoded = definition.elements().getFirst();
        assertEquals(1.0F, decoded.from()[0]);
        assertEquals(11.0F, decoded.to()[2]);
        assertEquals(ImmersiveFurnitureData.Axis.Z, decoded.axis());
        assertEquals(22.5F, decoded.rotation());
        assertEquals("minecraft:spruce_planks", decoded.material());
        assertEquals(7, decoded.emission());
        assertFalse(decoded.visible(0));
        assertTrue(decoded.visible(1));
    }

    @Test
    void ignoresNonGeometryElements() {
        var definition = ImmersiveFurnitureData.decodeNbt(Map.of(
                "Elements",
                List.of(
                        Map.of(
                                "Type", "particle_emitter",
                                "From", List.of(0F, 0F, 0F),
                                "To", List.of(16F, 16F, 16F)),
                        Map.of(
                                "Type", "element",
                                "From", List.of(0F, 0F, 0F),
                                "To", List.of(16F, 16F, 16F),
                                "Material", Map.of("Source", "minecraft:stone")))));

        assertNotNull(definition);
        assertEquals(1, definition.elements().size());
        assertEquals("minecraft:stone", definition.elements().getFirst().material());
    }

    @Test
    void furnitureFacingUsesClientRendererOrientation() {
        float[] east = {12F, 4F, 8F};
        ImmersiveFurnitureRenderer.rotateFacing(east, "east");
        assertEquals(8F, east[0], 0.0001F);
        assertEquals(4F, east[2], 0.0001F);

        float[] south = {12F, 4F, 8F};
        ImmersiveFurnitureRenderer.rotateFacing(south, "south");
        assertEquals(4F, south[0], 0.0001F);
        assertEquals(8F, south[2], 0.0001F);
    }
}
