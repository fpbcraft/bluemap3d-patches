package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.core.util.math.Color;

/** Immutable Ecliptic color/mix values for one biome, read on the server thread. */
public record SeasonalTint(int grass, int foliage, float mix) {
    public SeasonalTint {
        mix = Math.max(0f, Math.min(1f, mix));
    }

    public Color apply(Color base, boolean leaves) {
        if (mix <= 0f) return base;
        int tint = leaves ? foliage : grass;
        base.straight();
        float keep = 1f - mix;
        base.r = base.r * keep + ((tint >> 16) & 0xff) / 255f * mix;
        base.g = base.g * keep + ((tint >> 8) & 0xff) / 255f * mix;
        base.b = base.b * keep + (tint & 0xff) / 255f * mix;
        return base;
    }
}
