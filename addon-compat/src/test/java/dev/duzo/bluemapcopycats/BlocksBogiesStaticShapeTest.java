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
    void bogeyRenderOriginMatchesCreateStyleTransform() {
        var large = BlocksBogiesStaticShape.parse("create_bb:l_060");
        var extra = BlocksBogiesStaticShape.parse("create_bb:xl_060");
        assertNotNull(large);
        assertNotNull(extra);
        float origin = .5f - 1.5f - 1f/128f;
        assertEquals(origin, large.staticTerrainOffsetY(), .00001f);
        assertEquals(origin, extra.staticTerrainOffsetY(), .00001f);
        // Upstream OBJ wheels are centered at local y=0 and their wheel
        // radii are 12/16 and 1 respectively.
        assertEquals(-.7578125f, large.wheelY() + origin - .75f, .00001f);
        assertEquals(-.7578125f, extra.wheelY() + origin - 1f, .00001f);
    }

    @Test
    void usesStyleRatherThanAxlesToSelectBogieFrameFamily() {
        var bogie = BlocksBogiesStaticShape.parse("create_bb:l_060");
        assertNotNull(bogie);
        assertEquals("p", bogie.objVariant("create_bb:triple_axle_pistonless", "create_bb:l_060"));
        assertEquals("l", bogie.objVariant("create_bb:triple_axle_long", "create_bb:l_060_rot"));
        assertEquals("lw", bogie.objVariant("create_bb:triple_axle_walschaerts_long", "create_bb:l_060_rot"));
        assertEquals("sw", bogie.objVariant("create_bb:triple_axle_walschaerts_short", "create_bb:l_060_rot"));
        assertEquals("p", bogie.objVariant(null, "create_bb:l_060"));
        assertEquals("l", bogie.objVariant(null, "create_bb:l_060_rot"));
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
