package dev.duzo.bluemapcopycats;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolves Copycats/Create copycat material compounds without linking to either mod.
 */
final class CopycatsMaterialResolver {

    private CopycatsMaterialResolver() {
    }

    static CopycatsMaterial materialFor(CopycatsTerrainBlockEntity entity, String part) {
        return resolve(entity.material(), entity.materialData(), part);
    }

    static CopycatsMaterial resolve(Object directMaterial, Object materialData, String part) {
        if (part != null && materialData instanceof Map<?, ?> data) {
            CopycatsMaterial specific = fromStorage(data.get(part));
            if (usable(specific)) return specific;
        }

        CopycatsMaterial direct = fromState(directMaterial);
        if (usable(direct)) return direct;

        if (materialData instanceof Map<?, ?> data) {
            for (Object value : data.values()) {
                CopycatsMaterial fallback = fromStorage(value);
                if (usable(fallback)) return fallback;
            }
        }
        return direct;
    }

    /**
     * Untextured Create panels/steps deliberately store create:copycat_base.
     * Unlike unsupported materials, this is a real renderable texture.
     * Limit this exception to Create's panel/step so other copycats retain
     * their existing material validation behavior.
     */
    static CopycatsMaterial createPanelOrStepMaterial(CopycatsTerrainBlockEntity entity) {
        return createDefaultMaterial(entity == null ? null : materialFor(entity, null));
    }

    /** Pure default-material resolution; safe for unit tests without BlueMap's runtime. */
    static CopycatsMaterial createDefaultMaterial(CopycatsMaterial material) {
        if (usable(material)) return material;
        if (material == null || "create:copycat_base".equals(material.id())
                || "copycats:copycat_base".equals(material.id())) {
            return new CopycatsMaterial("create:copycat_base", Map.of());
        }
        return null;
    }

    static CopycatsMaterial fromStorage(Object raw) {
        if (!(raw instanceof Map<?, ?> storage)) return null;
        Object material = storage.containsKey("material")
                ? storage.get("material")
                : storage.get("Material");
        return fromState(material);
    }

    static CopycatsMaterial fromState(Object raw) {
        if (!(raw instanceof Map<?, ?> state)) return null;
        Object nameValue = state.containsKey("Name") ? state.get("Name") : state.get("name");
        if (!(nameValue instanceof String name) || name.isBlank()) return null;

        Map<String, String> properties = new LinkedHashMap<>();
        Object rawProperties = state.containsKey("Properties")
                ? state.get("Properties")
                : state.get("properties");
        if (rawProperties instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key
                        && entry.getValue() instanceof String value) {
                    properties.put(key, value);
                }
            }
        }
        return new CopycatsMaterial(name, Map.copyOf(properties));
    }

    static boolean usable(CopycatsMaterial material) {
        return material != null
                && !"create:copycat_base".equals(material.id())
                && !"copycats:copycat_base".equals(material.id())
                && !"minecraft:air".equals(material.id());
    }
}
