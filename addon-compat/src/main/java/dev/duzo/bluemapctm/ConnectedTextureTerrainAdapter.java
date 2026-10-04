package dev.duzo.bluemapctm;

import de.bluecolored.bluemap.api.BlueMapAPI;

/** Installs native static Fusion/Create connected-texture support for BlueMap 5.7. */
public final class ConnectedTextureTerrainAdapter {

    private ConnectedTextureTerrainAdapter() {
    }

    public static void register() {
        ConnectedTextureResourceExtension.register();
        ConnectedTextureTerrainRenderer.register();
    }

    public static void onBlueMapEnable(BlueMapAPI api) {
        ConnectedTextureTerrainDispatch.apply(api);
    }
}
