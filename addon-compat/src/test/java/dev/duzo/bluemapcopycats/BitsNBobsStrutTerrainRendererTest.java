package dev.duzo.bluemapcopycats;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BitsNBobsStrutTerrainRendererTest {

    @Test
    void rejectsMagentaBlackMissingTexture() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, ((x / 4 + y / 4) & 1) == 0
                        ? 0xFFFF00FF
                        : 0xFF000000);
            }
        }

        assertFalse(BitsNBobsStrutTerrainRenderer.isUsableImage(image));
    }

    @Test
    void acceptsOrdinaryDarkMetalTexture() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setRGB(x, y, ((x + y) & 1) == 0
                        ? 0xFF4E5457
                        : 0xFF73797C);
            }
        }

        assertTrue(BitsNBobsStrutTerrainRenderer.isUsableImage(image));
    }
}
