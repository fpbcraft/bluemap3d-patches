package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

/**
 * Renderer for server-side helper blocks that intentionally have no client geometry.
 */
public final class InvisibleTerrainRenderer implements BlockRenderer {

    public InvisibleTerrainRenderer(
            ResourcePack resourcePack,
            TextureGallery textureGallery,
            RenderSettings renderSettings) {
    }

    @Override
    public void render(
            BlockNeighborhood block,
            Variant ignoredVariant,
            TileModelView tileModel,
            Color blockColor) {
        // Intentionally empty. struts:girder_strut_structure is RenderShape.INVISIBLE;
        // the visible span is emitted by the endpoint strut renderer instead.
    }
}
