package dev.duzo.bluemapseasons;

/** Immutable server-authoritative seasonal state, deliberately independent of Ecliptic classes. */
public record SeasonState(String solarTerm, String season) {
    public SeasonState {
        if (solarTerm == null || solarTerm.isBlank() || season == null || season.isBlank()) {
            throw new IllegalArgumentException("Solar term and season must be nonempty");
        }
    }
}
