package dev.duzo.bluemapdynamictrees;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType;
import de.bluecolored.bluemap.core.util.Key;

/** Registers the native Dynamic Trees terrain renderer and routes loaded custom models to it. */
public final class DynamicTreesTerrainAdapter {

    private static final Key RENDERER_KEY =
            new Key("bluemap_dynamic_trees", "terrain");

    private DynamicTreesTerrainAdapter() {
    }

    public static void register() {
        BlockRendererType existing = BlockRendererType.REGISTRY.get(RENDERER_KEY);
        if (existing == null) {
            BlockRendererType.REGISTRY.register(new BlockRendererType.Impl(
                    RENDERER_KEY,
                    DynamicTreesTerrainRenderer::new));
        } else if (!(existing instanceof BlockRendererType.Impl)) {
            Logger.global.logWarning(String.format(
                    "Renderer %s is already registered by %s; leaving it unchanged",
                    RENDERER_KEY,
                    existing.getClass().getName()));
        }

        BlueMapAPI.onEnable(DynamicTreesTerrainDispatch::apply);
        Logger.global.logInfo("Dynamic Trees native terrain renderer registered");
    }
}
