package dev.duzo.bluemap3d.bake;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasTest {

    @Test
    void rectangularEntityTextureKeepsItsAspectRatio() {
        BufferedImage skin = opaque(64, 32);
        TextureAtlas atlas = new TextureAtlas();
        int slot = atlas.add("minecraft:entity/cow/cow", skin);

        BufferedImage packed = atlas.build();
        assertEquals(64, packed.getWidth());
        assertEquals(64, packed.getHeight());

        // The 64x32 skin occupies only the upper half of its square atlas cell.
        assertTrue((packed.getRGB(32, 16) >>> 24) != 0);
        assertEquals(0, packed.getRGB(32, 48) >>> 24);

        float[] mapped = new float[8];
        atlas.mapUv(slot, new float[]{0,0, 16,0, 16,16, 0,16}, mapped);

        float uSpan = mapped[2] - mapped[0];
        float vSpan = mapped[5] - mapped[3];
        assertTrue(uSpan > 0.9F, "entity skin should use almost the full cell width");
        assertTrue(vSpan > 0.45F && vSpan < 0.55F,
                "64x32 entity skin should use half the cell height");
    }

    @Test
    void tallEntityTextureIsNotMistakenForAnimationFrames() {
        BufferedImage skin = opaque(32, 64);
        TextureAtlas atlas = new TextureAtlas();
        int slot = atlas.add("example:entity/ostrich/ostrich", skin);

        BufferedImage packed = atlas.build();
        assertEquals(64, packed.getWidth());
        assertEquals(64, packed.getHeight());
        assertTrue((packed.getRGB(16, 48) >>> 24) != 0,
                "bottom half of tall entity skin must not be cropped");

        float[] mapped = new float[8];
        atlas.mapUv(slot, new float[]{0,0, 16,0, 16,16, 0,16}, mapped);
        float uSpan = mapped[2] - mapped[0];
        float vSpan = mapped[5] - mapped[3];
        assertTrue(uSpan > 0.45F && uSpan < 0.55F);
        assertTrue(vSpan > 0.9F);
    }

    @Test
    void verticalBlockAnimationStillUsesFirstFrame() {
        BufferedImage strip = opaque(16, 32);
        TextureAtlas atlas = new TextureAtlas();
        atlas.add("minecraft:block/water_still", strip);

        BufferedImage packed = atlas.build();
        assertEquals(16, packed.getWidth());
        assertEquals(16, packed.getHeight());
    }

    private static BufferedImage opaque(int width, int height) {
        BufferedImage image =
                new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        return image;
    }
}
