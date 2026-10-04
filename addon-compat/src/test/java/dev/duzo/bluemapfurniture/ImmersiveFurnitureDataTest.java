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
                "Color", 0xFFFF0000,
                "Material", Map.of(
                        "Source", "minecraft:spruce_planks",
                        "Transparency", "translucent"));

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
        assertEquals(0xFFFF0000, decoded.color());
        assertEquals(
                ImmersiveFurnitureData.Transparency.TRANSLUCENT,
                decoded.transparency());
        assertFalse(decoded.visible(0));
        assertTrue(decoded.visible(1));
    }

    @Test
    void convertsNativeAbgrPixelsToArgb() {
        // NativeImage stores 0xAABBGGRR; BufferedImage expects 0xAARRGGBB.
        assertEquals(
                0xFFFF0000,
                ImmersiveFurnitureData.nativeAbgrToArgb(0xFF0000FF));
        assertEquals(
                0xFF0000FF,
                ImmersiveFurnitureData.nativeAbgrToArgb(0xFFFF0000));
    }

    @Test
    void dithersOnlyTrulyTranslucentFurniturePixels() {
        int halfRedAbgr = 0x800000FF;

        int solid = ImmersiveFurnitureData.normalizeBakedPixel(
                halfRedAbgr,
                ImmersiveFurnitureData.Transparency.SOLID,
                0,
                0);
        assertEquals(0x80FF0000, solid);

        int dithered = ImmersiveFurnitureData.normalizeBakedPixel(
                halfRedAbgr,
                ImmersiveFurnitureData.Transparency.TRANSLUCENT,
                0,
                0);
        assertTrue((dithered >>> 24) == 0 || (dithered >>> 24) == 0xFF);
        assertEquals(0x00FF0000, dithered & 0x00FFFFFF);
    }

    @Test
    void decodesSavedDataIdentifierRegistry() {
        var registry = ImmersiveFurnitureData.decodeIdentifierRegistry(Map.of(
                "DataVersion", 3955,
                "data", Map.of(
                        "usageCount", Map.of("abc123", 4),
                        "hashToIdentifier", Map.of(
                                "abc123", 7,
                                "lit456", 65539))));

        assertEquals("abc123", registry.get(7));
        assertEquals("lit456", registry.get(65539));
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

}
