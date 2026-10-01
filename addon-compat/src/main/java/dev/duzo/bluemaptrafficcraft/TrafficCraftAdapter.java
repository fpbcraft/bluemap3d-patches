package dev.duzo.bluemaptrafficcraft;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType;

import java.util.List;

/**
 * Minimal adapter retained for TrafficCraft behavior that cannot be expressed by the
 * generic rule engine on BlueMap 5.7: decoding the block-entity fields and rendering
 * dynamic traffic-sign textures.
 */
public final class TrafficCraftAdapter {

    private static final List<Key> BLOCK_ENTITY_IDS = List.of(
            new Key("trafficcraft", "colored_block_entity"),
            new Key("trafficcraft", "traffic_light_block_entity"),
            new Key("trafficcraft", "street_sign_block_entity"),
            new Key("trafficcraft", "house_number_sign_block_entity"),
            new Key("trafficcraft", "traffic_sign_block_entity")
    );

    private TrafficCraftAdapter() {
    }

    public static void register() {
        registerBlockEntities();
        TrafficCraftSignRenderer.register();
    }

    public static void onBlueMapEnable(BlueMapAPI api) {
        TrafficCraftSignSupport.install(api);
    }

    private static void registerBlockEntities() {
        for (Key key : BLOCK_ENTITY_IDS) {
            BlockEntityType existing = BlockEntityType.REGISTRY.get(key);
            if (existing != null) {
                if (!TrafficCraftColorBlockEntity.class.equals(existing.getBlockEntityClass())) {
                    Logger.global.logWarning(String.format(
                            "Block entity %s is already registered to %s; "
                                    + "TrafficCraft compatibility NBT may be unavailable",
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
