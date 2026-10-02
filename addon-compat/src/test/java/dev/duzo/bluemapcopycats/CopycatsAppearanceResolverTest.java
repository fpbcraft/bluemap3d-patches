package dev.duzo.bluemapcopycats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import de.bluecolored.bluemap.core.util.Direction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class CopycatsAppearanceResolverTest {

    @Test
    void preferredFaceWinsEvenWhenItAppearsOnaLaterElement() {
        String selected = CopycatsAppearanceResolver.selectPreferredFace(
                List.of(
                        Map.of(Direction.NORTH, "first-fallback"),
                        Map.of(Direction.EAST, "preferred")),
                Direction.EAST);

        assertEquals("preferred", selected);
    }

    @Test
    void firstAvailableFaceIsUsedWhenPreferredFaceIsMissing() {
        String selected = CopycatsAppearanceResolver.selectPreferredFace(
                List.of(
                        Map.of(Direction.SOUTH, "first"),
                        Map.of(Direction.NORTH, "second")),
                Direction.UP);

        assertEquals("first", selected);
    }

    @Test
    void missingFacesReturnNull() {
        assertNull(CopycatsAppearanceResolver.selectPreferredFace(
                List.of(Map.of(), Map.of()),
                Direction.DOWN));
    }
}
