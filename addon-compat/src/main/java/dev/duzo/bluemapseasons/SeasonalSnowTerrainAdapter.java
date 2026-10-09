package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.world.BlockState;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes only selected vanilla terrain models, leaving the actual resource-pack models intact.
 * Never replaces a renderer claimed by another compatibility addon.
 */
public final class SeasonalSnowTerrainAdapter {
    private static final Key KEY = new Key("bluemap_ecliptic", "snow_surface");
    private static final BlockRendererType TYPE =
            new BlockRendererType.Impl(KEY, SeasonalSnowTerrainRenderer::new);

    private SeasonalSnowTerrainAdapter() {}

    public static void register() {
        if (BlockRendererType.REGISTRY.get(KEY) == null) {
            BlockRendererType.REGISTRY.register(TYPE);
        }
    }

    public static void onEnable(BlueMapAPI api) {
        if (!EclipticSeasonsBridge.isAvailable()
                || !Boolean.parseBoolean(System.getProperty("bluemap.compat.ecliptic.snow", "true")))
            return;
        if (!(api instanceof BlueMapAPIImpl impl)) return;
        ResourcePack pack = impl.blueMapService().getResourcePack();
        if (pack == null) return;

        final Field rendererField;
        try {
            rendererField = Variant.class.getDeclaredField("renderer");
            rendererField.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError("Cannot install Ecliptic snow routing: BlueMap Variant.renderer unavailable", error);
            return;
        }
        int count = 0;
        for (String id : new String[]{
                "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
                "minecraft:podzol", "minecraft:mycelium", "minecraft:stone", "minecraft:gravel"}) {
            var blockstate = pack.getBlockState(new BlockState(id));
            if (blockstate == null) continue;
            List<Variant> variants = new ArrayList<>();
            blockstate.forEach(new BlockState(id), 0, 0, 0, variants::add);
            for (Variant variant : variants) {
                // Do not override renderers installed by Copycats, CTM or mod-specific adapters.
                if (variant.getRenderer() == BlockRendererType.DEFAULT) {
                    try {
                        rendererField.set(variant, TYPE);
                        count++;
                    } catch (IllegalAccessException error) {
                        Logger.global.logError("Could not route Ecliptic snow terrain renderer", error);
                    }
                }
            }
        }
        Logger.global.logInfo("Ecliptic virtual snow top-face renderer routed "
                + count + " original terrain model variant(s)");
    }
}
