package dev.duzo.bluemapcopycats;

import java.util.LinkedHashMap;
import java.util.Map;

final class DiagonalConnectionProperties {

    private DiagonalConnectionProperties() {
    }

    static Map<String, String> sanitize(Map<String, String> source, boolean wall) {
        Map<String, String> properties = new LinkedHashMap<>(source);
        String disconnected = wall ? "none" : "false";

        // StarCollisionBlock.isFreeForDiagonalProperty() rejects a diagonal when either
        // of its two cardinal neighbours is directly attached. Preserve that invariant
        // when replaying generated diagonal ids through the source resource model.
        suppress(properties, "north_east", disconnected, "north", "east");
        suppress(properties, "south_east", disconnected, "south", "east");
        suppress(properties, "south_west", disconnected, "south", "west");
        suppress(properties, "north_west", disconnected, "north", "west");

        return Map.copyOf(properties);
    }

    private static void suppress(
            Map<String, String> properties,
            String diagonal,
            String disconnected,
            String first,
            String second) {
        if (!"true".equals(properties.get(diagonal))) return;
        if (properties.containsKey(first)) properties.put(first, disconnected);
        if (properties.containsKey(second)) properties.put(second, disconnected);
    }
}
