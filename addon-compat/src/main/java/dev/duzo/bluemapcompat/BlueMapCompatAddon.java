package dev.duzo.bluemapcompat;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.core.logger.Logger;
import dev.duzo.bluemapcopycats.BlueMapCopycatsCompatAddon;
import dev.duzo.bluemaptrafficcraft.TrafficCraftAdapter;
import dev.duzo.bluemapdynamictrees.DynamicTreesTerrainAdapter;
import dev.duzo.bluemapdynamictrees.DynamicTreesTerrainDispatch;
import dev.duzo.bluemapfurniture.ImmersiveFurnitureAdapter;

/**
 * Single native BlueMap compatibility entrypoint.
 *
 * <p>Declarative behavior belongs in compat/*.json. Java adapters are reserved for
 * rendering concepts that BlueMap 5.7 cannot express as data, such as TrafficCraft's
 * dynamic sign textures.
 */
public final class BlueMapCompatAddon implements Runnable {

    @Override
    public void run() {
        // Specialized adapters share this one native BlueMap addon artifact.
        new BlueMapCopycatsCompatAddon().run();
        TrafficCraftAdapter.register();
        DynamicTreesTerrainAdapter.register();
        ImmersiveFurnitureAdapter.register();
        CompatManager.start();

        BlueMapAPI.onEnable(api -> {
            ConfiguredModelAliasHook.install(api);
            ConfiguredTintHook.install(api);
            TrafficCraftAdapter.onBlueMapEnable(api);
            ImmersiveFurnitureAdapter.onBlueMapEnable(api);

            // Run this last. Legacy/fallback Dynamic Trees aliases may still exist in
            // local compat config; the native renderer must reclaim those branch/root
            // blockstates after generic aliases have been applied.
            DynamicTreesTerrainDispatch.apply(api);
        });

        Logger.global.logInfo(
                "BlueMap Compat loaded: config-driven rules + TrafficCraft + native Dynamic Trees terrain");
    }
}
