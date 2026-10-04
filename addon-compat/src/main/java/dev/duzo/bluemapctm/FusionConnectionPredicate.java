package dev.duzo.bluemapctm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.world.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * BlueMap-side implementation of Fusion 1.21's built-in connection predicates.
 *
 * <p>Unknown third-party predicates deliberately evaluate false rather than connecting
 * unrelated blocks. The built-ins use the same JSON shapes as Fusion.
 */
interface FusionConnectionPredicate {

    boolean test(
            BlockState own,
            BlockState other,
            BlockState inFront,
            boolean frontOccluding,
            Direction face,
            String connectionDirection);

    static FusionConnectionPredicate sameState() {
        return (own, other, front, frontOccluding, face, direction) ->
                own != null && other != null && own.equals(other);
    }

    static FusionConnectionPredicate parse(JsonObject json) {
        if (json == null) return sameState();
        String type = typeName(json.has("type") ? json.get("type").getAsString() : "is_same_state");

        return switch (type) {
            case "true" -> (own, other, front, frontOccluding, face, direction) -> true;
            case "false" -> (own, other, front, frontOccluding, face, direction) -> false;
            case "is_same_block" -> (own, other, front, frontOccluding, face, direction) ->
                    own != null && other != null
                            && own.getFormatted().equals(other.getFormatted());
            case "is_same_state" -> sameState();
            case "match_block" -> matchBlock(json, false);
            case "match_block_in_front" -> matchBlock(json, true);
            case "match_state" -> matchState(json, false);
            case "match_state_in_front" -> matchState(json, true);
            case "is_direction" -> direction(json);
            case "is_face_visible" ->
                    (own, other, front, frontOccluding, face, direction) -> !frontOccluding;
            case "and" -> combine(json, true);
            case "or" -> combine(json, false);
            case "not" -> negate(json);
            default -> (own, other, front, frontOccluding, face, direction) -> false;
        };
    }

    static FusionConnectionPredicate or(JsonArray array) {
        List<FusionConnectionPredicate> predicates = new ArrayList<>();
        for (JsonElement element : array) {
            if (element.isJsonObject()) predicates.add(parse(element.getAsJsonObject()));
        }
        return (own, other, front, frontOccluding, face, direction) -> {
            for (FusionConnectionPredicate predicate : predicates) {
                if (predicate.test(own, other, front, frontOccluding, face, direction)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static FusionConnectionPredicate matchBlock(JsonObject json, boolean inFront) {
        Set<String> blocks = blockIds(json);
        return (own, other, front, frontOccluding, face, direction) ->
                blocks.contains((inFront ? front : other).getFormatted());
    }

    private static FusionConnectionPredicate matchState(JsonObject json, boolean inFront) {
        Set<String> blocks = blockIds(json);
        Map<String, Set<String>> properties = propertyMatchers(json.get("properties"));
        return (own, other, front, frontOccluding, face, direction) -> {
            BlockState candidate = inFront ? front : other;
            if (candidate == null) return false;
            if (!blocks.isEmpty() && !blocks.contains(candidate.getFormatted())) return false;
            for (Map.Entry<String, Set<String>> entry : properties.entrySet()) {
                String actual = candidate.getProperties().get(entry.getKey());
                if (actual == null || !entry.getValue().contains(actual)) return false;
            }
            return true;
        };
    }

    private static FusionConnectionPredicate direction(JsonObject json) {
        Set<String> directions = new HashSet<>();
        addStrings(json.get("direction"), directions);
        addStrings(json.get("directions"), directions);
        return (own, other, front, frontOccluding, face, direction) ->
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

        return (own, other, front, frontOccluding, face, direction) -> {
            if (and) {
                for (FusionConnectionPredicate predicate : predicates) {
                    if (!predicate.test(
                            own, other, front, frontOccluding, face, direction)) {
                        return false;
                    }
                }
                return true;
            }

            for (FusionConnectionPredicate predicate : predicates) {
                if (predicate.test(
                        own, other, front, frontOccluding, face, direction)) {
                    return true;
                }
            }
            return false;
        };
    }

    private static FusionConnectionPredicate negate(JsonObject json) {
        JsonElement element = json.get("predicate");
        FusionConnectionPredicate child =
                element != null && element.isJsonObject()
                        ? parse(element.getAsJsonObject())
                        : (own, other, front, frontOccluding, face, direction) -> false;
        return (own, other, front, frontOccluding, face, direction) ->
                !child.test(own, other, front, frontOccluding, face, direction);
    }

    private static Set<String> blockIds(JsonObject json) {
        Set<String> ids = new HashSet<>();
        addStrings(json.get("block"), ids);
        addStrings(json.get("blocks"), ids);
        return ids;
    }

    private static void addStrings(JsonElement element, Set<String> output) {
        if (element == null) return;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            output.add(element.getAsString().toLowerCase(Locale.ROOT));
            return;
        }
        if (!element.isJsonArray()) return;
        for (JsonElement child : element.getAsJsonArray()) {
            if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                output.add(child.getAsString().toLowerCase(Locale.ROOT));
            }
        }
    }

    private static Map<String, Set<String>> propertyMatchers(JsonElement element) {
        Map<String, Set<String>> result = new HashMap<>();
        if (element == null || !element.isJsonObject()) return result;
        for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            Set<String> values = new HashSet<>();
            addStrings(entry.getValue(), values);
            result.put(entry.getKey(), Set.copyOf(values));
        }
        return Map.copyOf(result);
    }

    private static String typeName(String type) {
        String lower = type.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        return colon < 0 ? lower : lower.substring(colon + 1);
    }
}
