package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModel;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.ResourceModelRenderer;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

/**
 * Decorates the real block model with an ephemeral snow top face.
 * Never changes Minecraft block states, BlueMap chunk data, or original model resources.
 */
public final class SeasonalSnowTerrainRenderer extends ResourceModelRenderer {
    private final int snowMaterial;

    public SeasonalSnowTerrainRenderer(
            ResourcePack pack, TextureGallery gallery, RenderSettings settings) {
        super(pack, gallery, settings);
        this.snowMaterial = gallery.get(new ResourcePath<>("minecraft:block/snow"));
    }

    @Override
    public void render(BlockNeighborhood block, Variant variant, TileModelView model, Color color) {
        int start = model.getStart();
        super.render(block, variant, model, color);
        // The vanilla material and its sides are always rendered unchanged.
        if (!SeasonalSnowSurface.isSnowy(block)) return;

        int originalEnd = model.getTileModel().size();
        model.initialize();
        model.add(2);
        TileModel target = model.getTileModel();
        int a = originalEnd;
        int b = a + 1;
        // Slightly lifted surface prevents z fighting with grass/stone top faces.
        final float y = 1.002f;
        target.setPositions(a, 0f, y, 0f, 0f, y, 1f, 1f, y, 1f);
        target.setPositions(b, 0f, y, 0f, 1f, y, 1f, 1f, y, 0f);
        target.setUvs(a, 0f, 0f, 0f, 1f, 1f, 1f);
        target.setUvs(b, 0f, 0f, 1f, 1f, 1f, 0f);
        target.setMaterialIndex(a, snowMaterial);
        target.setMaterialIndex(b, snowMaterial);
        target.setColor(a, 1f, 1f, 1f);
        target.setColor(b, 1f, 1f, 1f);
        target.setAOs(a, 1f, 1f, 1f);
        target.setAOs(b, 1f, 1f, 1f);
        target.setSunlight(a, 15);
        target.setSunlight(b, 15);
        target.setBlocklight(a, 0);
        target.setBlocklight(b, 0);
        model.initialize(start);
    }
}
