package dev.duzo.bluemapcompat;

import de.bluecolored.bluemap.core.world.BlockState;

/** Narrow bridge for the seasonal atlas; explicit terrain colour rules retain precedence. */
public final class SeasonalCompatPolicy {
    private SeasonalCompatPolicy() {}
    public static String revision() { return CompatManager.currentFingerprint(); }
    public static boolean hasTint(BlockState state) {
        return CompatManager.rules().tint(state.getFormatted(), state.getProperties(), "terrain") != null;
    }
}
