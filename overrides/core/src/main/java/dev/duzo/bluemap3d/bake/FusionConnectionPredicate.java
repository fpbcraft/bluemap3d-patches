package dev.duzo.bluemap3d.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Server-side implementation of Fusion's built-in connection predicates.
 *
 * <p>Third-party predicates are intentionally not guessed: an unknown predicate evaluates
 * false so a client-only extension cannot accidentally connect unrelated blocks.
 */
interface FusionConnectionPredicate {

    boolean test(
            BlockState own,
            BlockState other,
            BlockState inFront,
            Direction face,
            String connectionDirection);

    static FusionConnectionPredicate sameState() {
        return (own, other, front, face, direction) ->
                own != null && !other.isAir() && own == other;
    }

    static FusionConnectionPredicate parse(JsonObject json) {
        if (json == null) return sameState();
        String type = typeName(json.has("type") ? json.get("type").getAsString() : "is_same_state");

        return switch (type) {
            case "true" -> (own, other, front, face, direction) -> true;
            case "false" -> (own, other, front, face, direction) -> false;
            case "is_same_block" -> (own, other, front, face, direction) ->
                    own != null && !other.isAir() && own.getBlock() == other.getBlock();
            case "is_same_state" -> sameState();
            case "match_block" -> matchBlock(json, false);
            case "match_block_in_front" -> matchBlock(json, true);
            case "match_state" -> matchState(json, false);
            case "match_state_in_front" -> matchState(json, true);
            case "is_direction" -> direction(json);
            case "is_face_visible" -> (own, other, front, face, direction) ->
                    !front.canOcclude() || !other.skipRendering(front, face);
            case "and" -> combine(json, true);
            case "or" -> combine(json, false);
            case "not" -> negate(json);
            default -> (own, other, front, face, direction) -> false;
        };
    }

    static FusionConnectionPredicate or(JsonArray array) {
        List<FusionConnectionPredicate> predicates = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) predicates.add(parse(element.getAsJsonObject()));
        }
        return (own, other, front, face, direction) -> {
            for (FusionConnectionPredicate predicate : predicates) {
                if (predicate.test(own, other, front, face, direction)) return true;
            }
            return false;
        };
    }

    private static FusionConnectionPredicate matchBlock(JsonObject json, boolean front) {
        Set<String> blocks = blockIds(json);
        return (own, other, inFront, face, direction) ->
                blocks.contains(blockId(front ? inFront : other));
    }

    private static FusionConnectionPredicate matchState(JsonObject json, boolean front) {
        Set<String> blocks = blockIds(json);
        Map<String, Set<String>> properties = propertyMatchers(json.get("properties"));
        return (own, other, inFront, face, direction) -> {
            BlockState candidate = front ? inFront : other;
            if (!blocks.isEmpty() && !blocks.contains(blockId(candidate))) return false;
            return matchesProperties(candidate, properties);
        };
    }

    private static FusionConnectionPredicate direction(JsonObject json) {
        Set<String> directions = new HashSet<>();
        addStrings(json.get("direction"), directions);
        addStrings(json.get("directions"), directions);
        return (own, other, front, face, direction) ->
                directions.contains(direction.toLowerCase(Locale.ROOT));
    }

    private static FusionConnectionPredicate combine(JsonObject json, boolean and) {
        JsonArray array = json.has("predicates") && json.get("predicates").isJsonArray()
                ? json.getAsJsonArray("predicates")
                : new JsonArray();
        List<FusionConnectionPredicate> predicates = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) predicates.add(parse(element.getAsJsonObject()));
        }
        return (own, other, front, face, direction) -> {
            if (and) {
                for (FusionConnectionPredicate predicate : predicates) {
                    if (!predicate.test(own, other, front, face, direction)) return false;
                }
                return true;
            }
            for (FusionConnectionPredicate predicate : predicates) {
                if (predicate.test(own, other, front, face, direction)) return true;
            }
            return false;
        };
    }

    private static FusionConnectionPredicate negate(JsonObject json) {
        JsonElement element = json.get("predicate");
        FusionConnectionPredicate child = element != null && element.isJsonObject()
                ? parse(element.getAsJsonObject())
                : (own, other, front, face, direction) -> false;
        return (own, other, front, face, direction) ->
                !child.test(own, other, front, face, direction);
    }

    private static Set<String> blockIds(JsonObject json) {
        Set<String> ids = new HashSet<>();
        addStrings(json.get("block"), ids);
        addStrings(json.get("blocks"), ids);
        return ids;
    }

    private static void addStrings(JsonElement element, Set<String> out) {
        if (element == null) return;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            out.add(element.getAsString().toLowerCase(Locale.ROOT));
            return;
        }
        if (!element.isJsonArray()) return;
        for (JsonElement child : element.getAsJsonArray()) {
            if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                out.add(child.getAsString().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static Map<String, Set<String>> propertyMatchers(JsonElement element) {
        Map<String, Set<String>> result = new HashMap<>();
        if (element == null || !element.isJsonObject()) return result;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            Set<String> values = new HashSet<>();
            addStrings(entry.getValue(), values);
            if (values.isEmpty() && entry.getValue().isJsonPrimitive()) {
                values.add(entry.getValue().getAsString().toLowerCase(Locale.ROOT));
            }
            result.put(entry.getKey(), Set.copyOf(values));
        }
        return Map.copyOf(result);
    }

    private static boolean matchesProperties(
            BlockState state,
            Map<String, Set<String>> expected) {
        if (state == null) return false;
        for (Map.Entry<String, Set<String>> entry : expected.entrySet()) {
            String actual = propertyValue(state, entry.getKey());
            if (actual == null || !entry.getValue().contains(actual)) return false;
        }
        return true;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValue(BlockState state, String name) {
        for (Property property : state.getProperties()) {
            if (!property.getName().equals(name)) continue;
            Comparable value = state.getValue(property);
            return property.getName(value).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private static String blockId(BlockState state) {
        if (state == null) return "";
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().toLowerCase(Locale.ROOT);
    }

    private static String typeName(String type) {
        String value = type.toLowerCase(Locale.ROOT);
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(colon + 1);
    }
}
