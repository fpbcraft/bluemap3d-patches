package dev.duzo.bluemapseasons;

import java.util.Locale;

/** Controls the frequency of expensive forced map refreshes. */
public enum SeasonRefreshPolicy {
    OFF, SEASON, SOLAR_TERM;

    public static SeasonRefreshPolicy parse(String value) {
        if (value == null) return OFF;
        try {
            return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return OFF;
        }
    }

    public boolean shouldRefresh(SeasonState previous, SeasonState next) {
        if (previous == null || next == null) return false;
        return switch (this) {
            case OFF -> false;
            case SEASON -> !previous.season().equals(next.season());
            case SOLAR_TERM -> !previous.solarTerm().equals(next.solarTerm());
        };
    }
}
