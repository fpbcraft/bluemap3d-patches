package dev.duzo.bluemaptrafficcraft;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType;

import java.util.List;

/**
 * BlueMap 5.7 compatibility for TrafficCraft's client-side painted block colors.
 */
public final class BlueMapTrafficCraftCompatAddon implements Runnable {

    private static final List<Key> COLOR_BLOCK_ENTITY_IDS = List.of(
            new Key("trafficcraft", "colored_block_entity"),
            new Key("trafficcraft", "traffic_light_block_entity"),
            new Key("trafficcraft", "street_sign_block_entity"),
            new Key("trafficcraft", "house_number_sign_block_entity")
    );

    @Override
    public void run() {
        registerColorBlockEntities();

        // BlueMap has loaded its resource/color configuration by the enable callback.
        // Install the TrafficCraft callbacks after that load so they win over the
        // generic/default foliage tint without replacing any original block models.
        BlueMapAPI.onEnable(TrafficCraftColorHook::install);

        Logger.global.logInfo(
                "BlueMap TrafficCraft Compat loaded: NBT paint colors for BlueMap 5.7");
    }

    private static void registerColorBlockEntities() {
        for (Key key : COLOR_BLOCK_ENTITY_IDS) {
            BlockEntityType existing = BlockEntityType.REGISTRY.get(key);
            if (existing != null) {
                if (!TrafficCraftColorBlockEntity.class.equals(existing.getBlockEntityClass())) {
                    Logger.global.logWarning(String.format(
                            "Block entity %s is already registered to %s; "
                                    + "TrafficCraft paint NBT may be unavailable",
                            key, existing.getBlockEntityClass().getName()));
                }
                continue;
            }

            BlockEntityType.REGISTRY.register(new BlockEntityType.Impl(
                    key,
                    TrafficCraftColorBlockEntity.class
            ));
        }
    }
}
