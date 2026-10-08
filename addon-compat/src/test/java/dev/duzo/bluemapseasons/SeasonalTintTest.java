package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.core.util.math.Color;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeasonalTintTest {
    @Test void zeroMixPreservesOriginal() {
        Color base = new Color().set(0xff204060);
        new SeasonalTint(0xffffff, 0xffffff, 0).apply(base, false);
        assertEquals(0xff204060, base.getInt());
    }

    @Test void fullMixUsesRequestedChannel() {
        SeasonalTint colors = new SeasonalTint(0x112233, 0xaabbcc, 1);
        assertEquals(0xff112233, colors.apply(new Color().set(0xffffffff), false).getInt());
        assertEquals(0xffaabbcc, colors.apply(new Color().set(0xffffffff), true).getInt());
    }

    @Test void halfMixBlendsAndPreservesAlpha() {
        Color base = new Color().set(0x80202020);
        new SeasonalTint(0x808080, 0xffffff, .5f).apply(base, false);
        assertEquals(0x80, base.getInt() >>> 24);
        assertEquals(0x50, (base.getInt() >>> 16) & 0xff);
    }

    @Test void clampsOutOfRangeBlend() {
        assertEquals(1f, new SeasonalTint(0, 0, 99f).mix());
        assertEquals(0f, new SeasonalTint(0, 0, -99f).mix());
    }
}
