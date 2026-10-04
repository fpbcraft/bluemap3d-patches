package dev.duzo.bluemapcopycats;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DiagonalConnectionPropertiesTest {

    @Test
    void oppositeDiagonalFenceConnectionsSuppressAllCardinalArms() {
        Map<String, String> result = DiagonalConnectionProperties.sanitize(
                Map.of(
                        "north", "true",
                        "east", "true",
                        "south", "true",
                        "west", "true",
                        "north_east", "true",
                        "south_east", "false",
                        "south_west", "true",
                        "north_west", "false"),
                false);

        assertEquals("false", result.get("north"));
        assertEquals("false", result.get("east"));
        assertEquals("false", result.get("south"));
        assertEquals("false", result.get("west"));
    }

    @Test
    void diagonalOnlySuppressesItsAdjacentCardinals() {
        Map<String, String> result = DiagonalConnectionProperties.sanitize(
                Map.of(
                        "north", "true",
                        "east", "true",
                        "south", "true",
                        "west", "true",
                        "north_east", "true"),
                false);

        assertEquals("false", result.get("north"));
        assertEquals("false", result.get("east"));
        assertEquals("true", result.get("south"));
        assertEquals("true", result.get("west"));
    }

    @Test
    void wallUsesNoneForSuppressedSides() {
        Map<String, String> result = DiagonalConnectionProperties.sanitize(
                Map.of(
                        "north", "low",
                        "east", "low",
                        "north_east", "true"),
                true);

        assertEquals("none", result.get("north"));
        assertEquals("none", result.get("east"));
    }
}
