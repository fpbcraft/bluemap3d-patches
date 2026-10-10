package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.map.hires.block.ResourceModelRenderer;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replays the original Railways headstock resource models with their real buffers,
 * couplers and per-state transforms. Only the Create copycat-base material sprite
 * is substituted, exactly where Railways' client-only CopycatHeadstockModel would
 * provide the block entity material. No extra geometry or texture is overlaid.
 */
public final class HeadstockTerrainRenderer implements BlockRenderer {
    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();

    private final ResourceModelRenderer delegate;
    private final SubstitutionGallery gallery;

    public HeadstockTerrainRenderer(
            ResourcePack pack, TextureGallery textures, RenderSettings settings) {
        this.gallery = new SubstitutionGallery(pack, textures);
        this.delegate = new ResourceModelRenderer(pack, gallery, settings);
    }

    @Override
    public void render(BlockNeighborhood block, Variant ignored,
            TileModelView tile, Color blockColor) {
        String id = block.getBlockState().getFormatted();
        var original = ConnectedTerrainDispatch.original(id);
        if (original == null) return;

        CopycatsTerrainBlockEntity entity =
                CopycatsTerrainBlockEntity.from(block.getBlockEntity());
        CopycatsMaterial material = entity == null ? null
                : CopycatsMaterialResolver.materialFor(entity, null);
        int before = tile.getTileModel().size();
        gallery.select(block, CopycatsMaterialResolver.usable(material) ? material : null);
        try {
            original.forEach(block.getBlockState(), block.getX(), block.getY(),
                    block.getZ(), variant -> {
                        Color color = new Color();
                        delegate.render(block, variant, tile.initialize(), color);
                    });
        } finally {
            gallery.clear();
        }
        tile.initialize(before);
        blockColor.set(1f, 1f, 1f, 1f, true);
        if (TRACED.add(id)) {
            Logger.global.logDebug("STATIC HEADSTOCK block=" + id
                    + " material=" + (material == null ? "<none>" : material.id())
                    + " triangles=" + (tile.getTileModel().size()-before)
                    + " entity=" + (block.getBlockEntity() == null
                        ? "<none>" : block.getBlockEntity().getClass().getSimpleName()));
        }
    }

    /**
     * ResourceModelRenderer resolves a texture for each original JSON face.
     * Replacing the gallery lookup rather than modifying shared model objects
     * keeps the original headstock geometry, UVs and buffer/coupler materials.
     */
    private static final class SubstitutionGallery extends TextureGallery {
        private final TextureGallery original;
        private final CopycatsAppearanceResolver appearances;
        private final ThreadLocal<Integer> copied = new ThreadLocal<>();

        private SubstitutionGallery(ResourcePack pack, TextureGallery original) {
            this.original = original;
            this.appearances = new CopycatsAppearanceResolver(pack, original);
        }

        private void select(BlockNeighborhood block, CopycatsMaterial material) {
            if (material == null) {
                copied.remove();
                return;
            }
            // ResourceModelRenderer does not expose the current face direction to
            // TextureGallery. Use the copied material's north texture as the
            // representative surface; other, non-copycat faces are unchanged.
            var resolved = appearances.resolve(material, Direction.NORTH, block);
            if (resolved != null && resolved.textureIndex() > 0) {
                copied.set(resolved.textureIndex());
            } else {
                copied.remove();
            }
        }

        private void clear() {
            copied.remove();
        }

        @Override
        public int get(Key texture) {
            Integer replacement = copied.get();
            if (replacement != null && texture != null
                    && "create:block/copycat_base".equals(texture.getFormatted())) {
                return replacement;
            }
            return original.get(texture);
        }
    }
}
