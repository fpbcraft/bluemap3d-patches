package dev.duzo.bluemapcopycats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

final class CopycatsTemplateGeometryTest {

    @Test
    void resolvesCopycatsTemplateIds() {
        assertEquals(
                "copycats:block/copycat_base/stairs",
                CopycatsTemplateGeometry.modelId("copycats:copycat_stairs"));
        assertEquals(
                "copycats:block/copycat_base/block",
                CopycatsTemplateGeometry.modelId("copycats:wrapped_copycat"));
        assertEquals(
                "copycats:block/copycat_base/pressure_plate",
                CopycatsTemplateGeometry.modelId("copycats:copycat_heavy_weighted_pressure_plate"));
        assertEquals(
                "copycats:block/copycat_base/fluid_pipe",
                CopycatsTemplateGeometry.modelId("copycats:copycat_glass_fluid_pipe"));
    }

    @Test
    void resolvesCreateConnectedTemplateIds() {
        assertEquals(
                "create_connected:block/copycat_base/beam",
                CopycatsTemplateGeometry.modelId("create_connected:copycat_beam"));
        assertEquals(
                "create_connected:block/copycat_base/beam",
                CopycatsTemplateGeometry.modelId("create_connected:wrapped_copycat_beam"));
    }

    @Test
    void rejectsUnsupportedTemplateIds() {
        assertNull(CopycatsTemplateGeometry.modelId("minecraft:stone"));
        assertNull(CopycatsTemplateGeometry.modelId("copycats:not_a_copycat"));
        assertNull(CopycatsTemplateGeometry.modelId("missing_namespace"));
    }
}
