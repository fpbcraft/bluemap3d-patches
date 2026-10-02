package dev.duzo.bluemapcopycats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CopycatsFaceSelectorTest {

    private static final List<String> FALLBACK_ORDER =
            List.of("down", "up", "north", "south", "west", "east");

    @Test
    void preferredFaceWinsEvenWhenItAppearsOnALaterElement() {
        String selected = CopycatsFaceSelector.selectPreferredFace(
                List.of(
                        Map.of("north", "first-fallback"),
                        Map.of("east", "preferred")),
                "east",
                FALLBACK_ORDER);

        assertEquals("preferred", selected);
    }

    @Test
    void firstAvailableFaceUsesFallbackDirectionOrderWithinTheFirstElement() {
        String selected = CopycatsFaceSelector.selectPreferredFace(
                List.of(
                        Map.of("south", "south-face", "north", "north-face"),
                        Map.of("down", "later-element")),
                "up",
                FALLBACK_ORDER);

        assertEquals("north-face", selected);
    }

    @Test
    void missingFacesReturnNull() {
        assertNull(CopycatsFaceSelector.selectPreferredFace(
                List.of(Map.of(), Map.of()),
                "down",
                FALLBACK_ORDER));
    }
}
