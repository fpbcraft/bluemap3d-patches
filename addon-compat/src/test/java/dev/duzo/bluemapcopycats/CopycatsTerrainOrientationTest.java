package dev.duzo.bluemapcopycats;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Orientation regressions for terrain blocks; moving Create shapes are separate. */
final class CopycatsTerrainOrientationTest {

    private static final float EPS = 0.00001f;

    private static void position(float[] point, CopycatsTransform transform,
                                 float x, float y, float z) {
        transform.apply(point);
        assertEquals(x, point[0], EPS, "X");
        assertEquals(y, point[1], EPS, "Y");
        assertEquals(z, point[2], EPS, "Z");
    }

    @Test
    void staticCreatePanelHorizontalFacingsMatchStationaryWorld() {
        // Canonical Create panel is a 3px plate at Y=0..3.
        float[] corner = {8, 1.5f, 8};
        position(corner.clone(), CopycatsStaticFacing.panel("north"), 8, 8, 14.5f);
        position(corner.clone(), CopycatsStaticFacing.panel("south"), 8, 8, 1.5f);
        position(corner.clone(), CopycatsStaticFacing.panel("west"), 14.5f, 8, 8);
        position(corner.clone(), CopycatsStaticFacing.panel("east"), 1.5f, 8, 8);
    }

    @Test
    void staticCreatePanelUpAndDownKeepTheirExistingOrientation() {
        float[] point = {8, 1.5f, 8};
        position(point.clone(), CopycatsStaticFacing.panel("up"), 8, 1.5f, 8);
        position(point.clone(), CopycatsStaticFacing.panel("down"), 8, 14.5f, 8);
    }

    @Test
    void staticVerticalStepCorrectsAllFourHorizontalFacings() {
        // Copycats+ canonical part occupies its positive X/Z quarter.
        float[] center = {12, 8, 12};
        position(center.clone(), CopycatsStaticFacing.verticalStep("south"), 4, 8, 4);
        position(center.clone(), CopycatsStaticFacing.verticalStep("west"), 12, 8, 4);
        position(center.clone(), CopycatsStaticFacing.verticalStep("north"), 12, 8, 12);
        position(center.clone(), CopycatsStaticFacing.verticalStep("east"), 4, 8, 12);
    }
}
