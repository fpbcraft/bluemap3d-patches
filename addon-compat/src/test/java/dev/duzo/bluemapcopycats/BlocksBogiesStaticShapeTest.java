package dev.duzo.bluemapcopycats;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BlocksBogiesStaticShapeTest {
    @Test
    void supportsEveryLargeAndExtraLargeAxleVariant() {
        for (String prefix : new String[]{"l", "xl"}) {
            int maximum = prefix.equals("xl") ? 5 : 6;
            for (int axles=1; axles<=maximum; axles++) {
                String numeric = axles*20 == 100 || axles*20 == 120
                        ? "0" + (axles*20) : String.format("0%02d", axles*20);
                var spec=BlocksBogiesStaticShape.parse("create_bb:"+prefix+"_"+numeric);
                assertNotNull(spec, prefix+"_"+numeric);
                assertEquals(axles,spec.axles());
                assertNotNull(BlocksBogiesStaticShape.parse(
                        "create_bb:"+prefix+"_"+numeric+"_rot"));
            }
        }
    }

    @Test
    void handlesSmallTrailingOffsetAndStandardBogies() {
        for(String id:new String[]{"s_020_trailing","s_040_trailing",
                "s_060_trailing","s_080_trailing","s_020_offset",
                "s_020_standard","s_060_standard","s_080_standard",
                "s_0100_standard"}) {
            var spec=BlocksBogiesStaticShape.parse("create_bb:"+id);
            assertNotNull(spec,id);
            assertTrue(spec.small());
            assertFalse(spec.extraLarge());
        }
    }

    @Test
    void realObjWheelPartsFollowMinecraftLargeTripleAxlePositions() {
        var large = BlocksBogiesStaticShape.parse("create_bb:l_060");
        assertNotNull(large);
        assertArrayEquals(new float[]{-1.6875f, 0f, 1.6875f},
                large.wheelPositions(), .00001f);
        assertEquals(1f, large.wheelY(), .00001f);

        var extraLarge = BlocksBogiesStaticShape.parse("create_bb:xl_060");
        assertNotNull(extraLarge);
        assertArrayEquals(new float[]{-2.25f, 0f, 2.25f},
                extraLarge.wheelPositions(), .00001f);
        assertEquals(1.25f, extraLarge.wheelY(), .00001f);

        var small = BlocksBogiesStaticShape.parse("create_bb:s_060");
        assertNotNull(small);
        assertArrayEquals(new float[]{-1f, 0f, 1f},
                small.wheelPositions(), .00001f);
        assertEquals(.75f, small.wheelY(), .00001f);
    }

    @Test
    void terrainRailAlignmentKeepsWheelAndFrameTransformsTogether() {
        // OBJ wheel radii: small 7/16, large 12/16, extra large 16/16.
        // This tests the world-space wheel clearance when the whole model
        // is offset, not an independent wheel translation.
        var small = BlocksBogiesStaticShape.parse("create_bb:s_060");
        var large = BlocksBogiesStaticShape.parse("create_bb:l_060");
        var extraLarge = BlocksBogiesStaticShape.parse("create_bb:xl_060");
        assertNotNull(small);
        assertNotNull(large);
        assertNotNull(extraLarge);

        assertEquals(-.1875f, small.staticTerrainOffsetY(), .00001f);
        assertEquals(small.staticTerrainOffsetY(), large.staticTerrainOffsetY(), .00001f);
        assertEquals(large.staticTerrainOffsetY(), extraLarge.staticTerrainOffsetY(), .00001f);

        assertEquals(.125f, small.wheelY() + small.staticTerrainOffsetY() - 7f/16f, .00001f);
        assertEquals(.0625f, large.wheelY() + large.staticTerrainOffsetY() - 12f/16f, .00001f);
        assertEquals(.0625f, extraLarge.wheelY() + extraLarge.staticTerrainOffsetY() - 1f, .00001f);
    }

    @Test
    void ignoresOtherModsAndNonBogeyEntries() {
        for(String id:new String[]{"create:large_bogey","railways:copycat_headstock",
                "create_bb:bogey","create_bb:sign","create_bb:xl_0120",
                "create_bb:l_0140","create_bb:l_0200"}) {
            assertNull(BlocksBogiesStaticShape.parse(id),id);
        }
    }
}
