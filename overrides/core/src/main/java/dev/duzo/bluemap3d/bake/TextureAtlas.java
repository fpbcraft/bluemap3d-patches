package dev.duzo.bluemap3d.bake;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Packs mesh textures into a uniform-cell atlas while preserving each source texture's
 * aspect ratio.
 *
 * <p>Block textures are usually square, but entity skins commonly are not (64x32 is
 * still used by cows, sheep, pigs, chickens and many modded mobs). Stretching every
 * source to a square cell changes the aspect of every UV island and visibly scrambles
 * entity faces/body markings. Each sprite is therefore fitted inside its square cell
 * without changing its aspect, and UV remapping targets only the occupied sub-rectangle.
 *
 * <p>Animated non-entity textures retain the historical first-frame heuristic. Entity
 * textures are never cropped solely because they are taller than they are wide: tall
 * skins are legitimate and must remain intact.
 */
final class TextureAtlas {

    /** Sprite ids in insertion order; index in this map is the grid slot. */
    private final Map<String, Integer> slots = new LinkedHashMap<>();
    private final Map<Integer, Sprite> sprites = new LinkedHashMap<>();

    private int tileSize = 16;

    /** Grid geometry, valid after {@link #build()}. */
    private int columns = 1;
    private int atlasSize = 16;

    private record Sprite(BufferedImage image, boolean entityTexture) {
    }

    /**
     * Adds a sprite if absent and returns its slot.
     *
     * @param texture sprite id
     * @param image   the sprite, or {@code null} for a solid-white placeholder
     */
    int add(String texture, BufferedImage image) {
        Integer existing = slots.get(texture);
        if (existing != null) {
            return existing;
        }

        int slot = slots.size();
        slots.put(texture, slot);

        boolean entityTexture = isEntityTexture(texture);
        BufferedImage sprite = image == null
                ? white()
                : entityTexture ? image : firstFrame(image);

        int maxDimension = Math.max(sprite.getWidth(), sprite.getHeight());
        tileSize = Math.max(tileSize, Math.min(maxDimension, 128));
        sprites.put(slot, new Sprite(sprite, entityTexture));
        return slot;
    }

    int size() {
        return slots.size();
    }

    /**
     * Builds the atlas image and freezes the grid so {@link #mapUv} can be used.
     */
    BufferedImage build() {
        int n = Math.max(slots.size(), 1);
        columns = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) columns);
        atlasSize = Math.max(columns, rows) * tileSize;

        BufferedImage atlas = new BufferedImage(atlasSize, atlasSize, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = atlas.createGraphics();
        try {
            // Minecraft textures are pixel art. Nearest-neighbour scaling keeps the atlas
            // deterministic and avoids introducing blended edge pixels before alpha-test.
            g.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            for (Map.Entry<Integer, Sprite> entry : sprites.entrySet()) {
                int slot = entry.getKey();
                BufferedImage image = entry.getValue().image();
                DrawSize draw = drawSize(image);

                int x = (slot % columns) * tileSize;
                int y = (slot / columns) * tileSize;
                g.drawImage(image, x, y, draw.width(), draw.height(), null);
            }
        } finally {
            g.dispose();
        }
        return atlas;
    }

    /**
     * Maps a quad's UVs from the sprite's normalized 0..16 space into atlas space.
     *
     * <p>The occupied part of a cell can be rectangular. This is essential for entity
     * skins: a 64x32 cow texture fitted into a 64x64 cell occupies only the upper half
     * of that cell, and V=16 must map to the bottom of that half rather than the bottom
     * of the square cell.
     */
    void mapUv(int slot, float[] uv16, float[] out) {
        Sprite sprite = sprites.get(slot);
        if (sprite == null) {
            throw new IllegalArgumentException("Unknown atlas slot " + slot);
        }

        DrawSize draw = drawSize(sprite.image());
        float originU = ((slot % columns) * tileSize) / (float) atlasSize;
        float originV = ((slot / columns) * tileSize) / (float) atlasSize;
        float spanU = draw.width() / (float) atlasSize;
        float spanV = draw.height() / (float) atlasSize;

        // Half a texel, so exact UV boundaries do not bleed into transparent padding or
        // the neighbouring atlas cell.
        float inset = 0.5f / atlasSize;
        float usableU = Math.max(0F, spanU - 2F * inset);
        float usableV = Math.max(0F, spanV - 2F * inset);

        for (int i = 0; i < 4; i++) {
            float u = clamp(uv16[i * 2] / 16f, 0f, 1f);
            float v = clamp(uv16[i * 2 + 1] / 16f, 0f, 1f);
            out[i * 2] = originU + inset + u * usableU;
            out[i * 2 + 1] = originV + inset + v * usableV;
        }
    }

    private DrawSize drawSize(BufferedImage image) {
        int width = Math.max(1, image.getWidth());
        int height = Math.max(1, image.getHeight());
        float scale = Math.min(tileSize / (float) width, tileSize / (float) height);
        return new DrawSize(
                Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)));
    }

    private record DrawSize(int width, int height) {
    }

    /**
     * Animated block/item sprites are commonly vertical strips of square frames.
     * Entity skins are excluded by the caller because tall entity textures are valid.
     */
    private static BufferedImage firstFrame(BufferedImage image) {
        int w = image.getWidth();
        int h = image.getHeight();
        if (h > w && w > 0 && h % w == 0) {
            return image.getSubimage(0, 0, w, w);
        }
        return image;
    }

    private static boolean isEntityTexture(String texture) {
        if (texture == null) return false;
        String lower = texture.toLowerCase(Locale.ROOT);
        int colon = lower.indexOf(':');
        String path = colon >= 0 ? lower.substring(colon + 1) : lower;
        return path.startsWith("entity/") || path.contains("/entity/");
    }

    private static BufferedImage white() {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                img.setRGB(x, y, 0xFFFFFFFF);
            }
        }
        return img;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
