package dev.duzo.bluemapfoliage;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.core.logger.Logger;

/** BlueMap 5.7 compatibility for client-side fixed foliage color providers. */
public final class BlueMapFoliageCompatAddon implements Runnable {

    @Override
    public void run() {
        BlueMapAPI.onEnable(FoliageColorHook::install);
        Logger.global.logInfo(
                "BlueMap Foliage Compat loaded: Quark blossom + Dynamic Trees fixed colors");
    }
}
