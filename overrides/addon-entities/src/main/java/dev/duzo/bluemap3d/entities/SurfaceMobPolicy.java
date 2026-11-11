package dev.duzo.bluemap3d.entities;

/** Pure surface-visibility policy, separated so its boundary is regression-testable. */
final class SurfaceMobPolicy {
    static final double SURFACE_TOLERANCE = 0.5D;

    private SurfaceMobPolicy() {
    }

    /**
     * Heightmaps report the first free Y above the top blocking block/fluid.
     * A small tolerance keeps mobs on slabs, slopes and interpolation boundaries visible.
     */
    static boolean isAtOrAboveSurface(double feetY, int surfaceY) {
        return Double.isFinite(feetY) && feetY + SURFACE_TOLERANCE >= surfaceY;
    }
}
