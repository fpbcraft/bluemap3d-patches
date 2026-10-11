package dev.duzo.bluemapseasons;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeasonRefreshPolicyTest {
    private final SeasonState spring1 = new SeasonState("BEGINNING_OF_SPRING", "SPRING");
    private final SeasonState spring2 = new SeasonState("RAIN_WATER", "SPRING");
    private final SeasonState summer = new SeasonState("BEGINNING_OF_SUMMER", "SUMMER");

    @Test void offNeverRefreshes() {
        assertFalse(SeasonRefreshPolicy.OFF.shouldRefresh(spring1, summer));
    }

    @Test void seasonOnlyRefreshesOnSeasonBoundaries() {
        assertFalse(SeasonRefreshPolicy.SEASON.shouldRefresh(spring1, spring2));
        assertTrue(SeasonRefreshPolicy.SEASON.shouldRefresh(spring2, summer));
    }

    @Test void solarTermTracksAllTransitions() {
        assertTrue(SeasonRefreshPolicy.SOLAR_TERM.shouldRefresh(spring1, spring2));
        assertFalse(SeasonRefreshPolicy.SOLAR_TERM.shouldRefresh(spring1, spring1));
    }

    @Test void bootstrapNeverForcesARefresh() {
        assertFalse(SeasonRefreshPolicy.SOLAR_TERM.shouldRefresh(null, spring1));
    }

    @Test void invalidConfigurationFailsClosed() {
        assertEquals(SeasonRefreshPolicy.OFF, SeasonRefreshPolicy.parse("nonsense"));
        assertEquals(SeasonRefreshPolicy.SOLAR_TERM, SeasonRefreshPolicy.parse("solar-term"));
    }
}
