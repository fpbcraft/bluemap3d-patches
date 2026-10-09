package dev.duzo.bluemapcompat;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.core.logger.Logger;
import dev.duzo.bluemapcopycats.BlueMapCopycatsCompatAddon;
import dev.duzo.bluemapctm.ConnectedTextureTerrainAdapter;
import dev.duzo.bluemaptrafficcraft.TrafficCraftAdapter;
import dev.duzo.bluemapdynamictrees.DynamicTreesTerrainAdapter;
import dev.duzo.bluemapdynamictrees.DynamicTreesTerrainDispatch;
import dev.duzo.bluemapfurniture.ImmersiveFurnitureAdapter;
import dev.duzo.bluemapseasons.EclipticSeasonsAdapter;
import dev.duzo.bluemapseasons.SeasonalSnowTerrainAdapter;

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
        // Resource-pack extensions must register before BlueMap constructs its ResourcePack.
        ConnectedTextureTerrainAdapter.register();
        SeasonalSnowTerrainAdapter.register();

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
            EclipticSeasonsAdapter.onEnable(api);

            // Reclaim Dynamic Trees after generic aliases first, then install
            // Immersive Furniture last so no other resource-pack mutator in this
            // callback can replace its cached custom-renderer blockstates.
            DynamicTreesTerrainDispatch.apply(api);
            ImmersiveFurnitureAdapter.onBlueMapEnable(api);

            // Install CT routing last so blockstates already claimed by a more specific
            // compatibility renderer remain authoritative.
            ConnectedTextureTerrainAdapter.onBlueMapEnable(api);
            SeasonalSnowTerrainAdapter.onEnable(api);
        });

        BlueMapAPI.onDisable(api -> EclipticSeasonsAdapter.onDisable());

        Logger.global.logInfo(
                "BlueMap Compat loaded: config-driven rules + TrafficCraft + Dynamic Trees + Fusion/Create connected textures");
    }
}
