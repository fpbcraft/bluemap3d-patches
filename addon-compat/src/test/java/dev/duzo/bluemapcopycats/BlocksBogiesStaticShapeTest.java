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
    void ignoresOtherModsAndNonBogeyEntries() {
        for(String id:new String[]{"create:large_bogey","railways:copycat_headstock",
                "create_bb:bogey","create_bb:sign","create_bb:xl_0120",
                "create_bb:l_0140","create_bb:l_0200"}) {
            assertNull(BlocksBogiesStaticShape.parse(id),id);
        }
    }
}
