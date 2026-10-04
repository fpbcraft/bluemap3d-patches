package dev.duzo.bluemapfurniture;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Small immutable view of Immersive Furniture's persisted/runtime model.
 *
 * <p>This intentionally does not link against Immersive Furniture. The addon may decode
 * either BlueNBT's Map/List representation or the live mod objects through public fields.
 */
final class ImmersiveFurnitureData {

    enum Axis {
        X, Y, Z
    }

    record Element(
            float[] from,
            float[] to,
            Axis axis,
            float rotation,
            int mask,
            String material,
            int emission) {
        Element {
            from = from.clone();
            to = to.clone();
        }

        boolean visible(int state) {
            return (mask & (1 << state)) != 0;
        }
    }

    record Definition(List<Element> elements) {
        Definition {
            elements = List.copyOf(elements);
        }

        boolean isEmpty() {
            return elements.isEmpty();
        }
    }

    private ImmersiveFurnitureData() {
    }

    static Definition decodeNbt(Object raw) {
        if (!(raw instanceof Map<?, ?> root)) return null;
        Object elementsRaw = value(root, "Elements", "elements");
        if (!(elementsRaw instanceof Iterable<?> iterable)) return null;

        List<Element> elements = new ArrayList<>();
        for (Object entry : iterable) {
            Element element = decodeNbtElement(entry);
            if (element != null) elements.add(element);
        }
        return new Definition(elements);
    }

    static Map<Integer, String> decodeIdentifierRegistry(Object raw) {
        if (!(raw instanceof Map<?, ?> rootMap)) return Map.of();

        Map<?, ?> data = asMap(rootMap.get("data"));
        if (data == null) data = rootMap;

        Map<?, ?> hashes = asMap(data.get("hashToIdentifier"));
        if (hashes == null || hashes.isEmpty()) return Map.of();

        Map<Integer, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : hashes.entrySet()) {
            if (!(entry.getKey() instanceof String hash)
                    || !(entry.getValue() instanceof Number identifier)) {
                continue;
            }
            out.put(identifier.intValue(), hash);
        }
        return Map.copyOf(out);
    }

    static Definition decodeRuntime(Object data) {
        if (data == null) return null;
        Object elementsRaw = field(data, "elements");
        if (!(elementsRaw instanceof Iterable<?> iterable)) return null;

        List<Element> elements = new ArrayList<>();
        for (Object entry : iterable) {
            Element element = decodeRuntimeElement(entry);
            if (element != null) elements.add(element);
        }
        return new Definition(elements);
    }

    private static Element decodeNbtElement(Object raw) {
        if (!(raw instanceof Map<?, ?> element)) return null;
        String type = string(value(element, "Type", "type"));
        if (type != null && !"element".equalsIgnoreCase(type)) return null;

        float[] from = vector(value(element, "From", "from"));
        float[] to = vector(value(element, "To", "to"));
        if (from == null || to == null) return null;

        Map<?, ?> material = asMap(value(element, "Material", "material"));
        String source = material == null
                ? "minecraft:oak_log"
                : string(value(material, "Source", "source"));
        if (source == null || source.isBlank()) source = "minecraft:oak_log";

        return new Element(
                from,
                to,
                axis(string(value(element, "Axis", "axis"))),
                number(value(element, "Rotation", "rotation"), 0F),
                integer(value(element, "Mask", "mask"), 3),
                source,
                integer(value(element, "Emission", "emission"), 0));
    }

    private static Element decodeRuntimeElement(Object element) {
        if (element == null) return null;
        Object type = field(element, "type");
        if (type != null && !"element".equalsIgnoreCase(String.valueOf(type))) return null;

        float[] from = runtimeVector(field(element, "from"));
        float[] to = runtimeVector(field(element, "to"));
        if (from == null || to == null) return null;

        Object material = field(element, "material");
        Object source = field(material, "source");
        String materialId = source == null ? "minecraft:oak_log" : source.toString();

        return new Element(
                from,
                to,
                axis(String.valueOf(field(element, "axis"))),
                number(field(element, "rotation"), 0F),
                integer(field(element, "mask"), 3),
                materialId,
                integer(field(element, "emission"), 0));
    }

    private static Object value(Map<?, ?> map, String primary, String alternate) {
        if (map.containsKey(primary)) return map.get(primary);
        return map.get(alternate);
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static float[] vector(Object raw) {
        if (!(raw instanceof Iterable<?> iterable)) return null;
        float[] out = new float[3];
        int i = 0;
        for (Object value : iterable) {
            if (i >= 3) break;
            if (!(value instanceof Number number)) return null;
            out[i++] = number.floatValue();
        }
        return i == 3 ? out : null;
    }

    private static float[] runtimeVector(Object vector) {
        if (vector == null) return null;
        Object x = field(vector, "x");
        Object y = field(vector, "y");
        Object z = field(vector, "z");
        if (!(x instanceof Number nx)
                || !(y instanceof Number ny)
                || !(z instanceof Number nz)) {
            return null;
        }
        return new float[]{nx.floatValue(), ny.floatValue(), nz.floatValue()};
    }

    private static Axis axis(String value) {
        if (value == null) return Axis.Y;
        try {
            return Axis.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return Axis.Y;
        }
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static float number(Object value, float fallback) {
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static Object field(Object target, String name) {
        if (target == null) return null;
        try {
            Field field = target.getClass().getField(name);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }
}
