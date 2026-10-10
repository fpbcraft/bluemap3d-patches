package dev.duzo.bluemapcopycats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CopycatsMaterialResolverTest {

    @Test
    void selectedPartWinsOverDirectMaterial() {
        Object direct = state("minecraft:stone", Map.of());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("top", storage(state("minecraft:oak_planks", Map.of("axis", "y"))));

        CopycatsMaterial material = CopycatsMaterialResolver.resolve(direct, data, "top");

        assertEquals("minecraft:oak_planks", material.id());
        assertEquals(Map.of("axis", "y"), material.properties());
    }

    @Test
    void directMaterialIsUsedWhenNoUsablePartExists() {
        Object direct = state("minecraft:deepslate", Map.of("axis", "y"));

        CopycatsMaterial material = CopycatsMaterialResolver.resolve(direct, Map.of(), "missing");

        assertEquals("minecraft:deepslate", material.id());
        assertEquals(Map.of("axis", "y"), material.properties());
    }

    @Test
    void materialDataProvidesFallbackWhenDirectMaterialIsCopycatBase() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("first", storage(state("minecraft:air", Map.of())));
        data.put("second", storage(state("minecraft:bricks", Map.of())));

        CopycatsMaterial material = CopycatsMaterialResolver.resolve(
                state("copycats:copycat_base", Map.of()),
                data,
                null);

        assertEquals("minecraft:bricks", material.id());
    }

    @Test
    void stateParsingAcceptsLowercaseAndUppercaseNbtKeys() {
        CopycatsMaterial upper = CopycatsMaterialResolver.fromState(
                Map.of("Name", "minecraft:stone", "Properties", Map.of("axis", "x")));
        CopycatsMaterial lower = CopycatsMaterialResolver.fromState(
                Map.of("name", "minecraft:dirt", "properties", Map.of("snowy", "false")));

        assertEquals("minecraft:stone", upper.id());
        assertEquals(Map.of("axis", "x"), upper.properties());
        assertEquals("minecraft:dirt", lower.id());
        assertEquals(Map.of("snowy", "false"), lower.properties());
    }

    @Test
    void copycatsAcceptGenericPrettyInPinkAndRailwaysCopiedMaterials() {
        for (String id : new String[]{
                "pretty_in_pink:black_brushed_steel",
                "pretty_in_pink:white_brushed_steel",
                "railways:brown_single_pane_locometal_window",
                "railways:blue_four_pane_locometal_window"}) {
            CopycatsMaterial direct = CopycatsMaterialResolver.resolve(
                    state(id, Map.of("axis", "y")), null, null);
            assertEquals(id, direct.id());
            assertTrue(CopycatsMaterialResolver.usable(direct));

            CopycatsMaterial multipart = CopycatsMaterialResolver.resolve(
                    null, Map.of("top", storage(state(id, Map.of()))), "top");
            assertEquals(id, multipart.id());
            assertTrue(CopycatsMaterialResolver.usable(multipart));
        }
    }

    @Test
    void unusableMaterialsRemainRejected() {
        assertFalse(CopycatsMaterialResolver.usable(null));
        assertFalse(CopycatsMaterialResolver.usable(new CopycatsMaterial("minecraft:air", Map.of())));
        assertFalse(CopycatsMaterialResolver.usable(new CopycatsMaterial("create:copycat_base", Map.of())));
        assertFalse(CopycatsMaterialResolver.usable(new CopycatsMaterial("copycats:copycat_base", Map.of())));
        assertTrue(CopycatsMaterialResolver.usable(new CopycatsMaterial("minecraft:stone", Map.of())));
        assertNull(CopycatsMaterialResolver.fromState(Map.of()));
    }

    private static Map<String, Object> state(String name, Map<String, String> properties) {
        return Map.of("Name", name, "Properties", properties);
    }

    private static Map<String, Object> storage(Object state) {
        return Map.of("material", state);
    }
}
