package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.world.mca.blockentity.BlockEntityType;
import de.bluecolored.bluemap.core.logger.Logger;

import java.util.List;

/**
 * Native BlueMap addon entrypoint.
 *
 * <p>This module is deliberately independent from BlueMap3D core. It belongs in
 * BlueMap's packs folder and only teaches BlueMap's normal static terrain renderer how
 * to decode Copycats+ and Create: Connected copycat block entities.
 */
public final class BlueMapCopycatsCompatAddon implements Runnable {

    private static final Key RENDERER_KEY = new Key("bluemap_copycats", "terrain");
    private static final Key GIRDER_RENDERER_KEY = new Key("bluemap_copycats", "bits_n_bobs_girder");
    private static final Key INVISIBLE_RENDERER_KEY = new Key("bluemap_copycats", "invisible");
    private static final Key CONNECTED_RENDERER_KEY = new Key("bluemap_copycats", "connected");

    private static final List<Key> BLOCK_ENTITY_IDS = List.of(
            new Key("copycats", "copycat"),
            new Key("copycats", "multistate_copycat"),
            new Key("copycats", "multistate_ladder_copycat"),
            new Key("copycats", "copycat_shaft"),
            new Key("copycats", "copycat_cogwheel"),
            new Key("copycats", "copycat_fluid_pipe"),
            new Key("copycats", "copycat_glass_fluid_pipe"),
            new Key("copycats", "copycat_sliding_door"),
            new Key("create_connected", "copycat")
    );

    @Override
    public void run() {
        registerRenderer();
        registerGirderRenderer();
        registerInvisibleRenderer();
        registerConnectedRenderer();
        registerBlockEntities();
        registerGirderBlockEntity();

        // Addons load before BlueMap loads its resources. The API enable callback runs
        // after those resources are baked but before the render manager starts, which is
        // the safe point to reroute every loaded fence/wall while retaining its original
        // parsed blockstate for material-specific rendering.
        BlueMapAPI.onEnable(ConnectedTerrainDispatch::apply);

        Logger.global.logInfo("BlueMap Copycats Compat loaded: full Copycats+/Create Connected coverage + Bits & Bobs girders + connected fences/walls");
    }

    private static void registerRenderer() {
        BlockRendererType existing = BlockRendererType.REGISTRY.get(RENDERER_KEY);
        if (existing != null) {
            Logger.global.logWarning(String.format(
                    "Renderer %s is already registered by %s; leaving it unchanged",
                    RENDERER_KEY, existing.getClass().getName()));
            return;
        }

        BlockRendererType.REGISTRY.register(new BlockRendererType.Impl(
                RENDERER_KEY,
                CopycatsTerrainRenderer::new
        ));
    }

    private static void registerGirderRenderer() {
        BlockRendererType existing = BlockRendererType.REGISTRY.get(GIRDER_RENDERER_KEY);
        if (existing != null) {
            Logger.global.logWarning(String.format(
                    "Renderer %s is already registered by %s; leaving it unchanged",
                    GIRDER_RENDERER_KEY, existing.getClass().getName()));
            return;
        }

        BlockRendererType.REGISTRY.register(new BlockRendererType.Impl(
                GIRDER_RENDERER_KEY,
                BitsNBobsStrutTerrainRenderer::new
        ));
    }

    private static void registerInvisibleRenderer() {
        BlockRendererType existing = BlockRendererType.REGISTRY.get(INVISIBLE_RENDERER_KEY);
        if (existing != null) return;

        BlockRendererType.REGISTRY.register(new BlockRendererType.Impl(
                INVISIBLE_RENDERER_KEY,
                InvisibleTerrainRenderer::new
        ));
    }

    private static void registerConnectedRenderer() {
        BlockRendererType existing = BlockRendererType.REGISTRY.get(CONNECTED_RENDERER_KEY);
        if (existing != null) {
            Logger.global.logWarning(String.format(
                    "Renderer %s is already registered by %s; leaving it unchanged",
                    CONNECTED_RENDERER_KEY, existing.getClass().getName()));
            return;
        }

        BlockRendererType.REGISTRY.register(new BlockRendererType.Impl(
                CONNECTED_RENDERER_KEY,
                ConnectedTerrainRenderer::new
        ));
    }

    private static void registerGirderBlockEntity() {
        Key key = new Key("bits_n_bobs", "girder_strut");
        BlockEntityType existing = BlockEntityType.REGISTRY.get(key);
        if (existing != null) {
            if (!BitsNBobsStrutBlockEntity.class.equals(existing.getBlockEntityClass())) {
                Logger.global.logWarning(String.format(
                        "Block entity %s is already registered to %s; girder connection NBT may be unavailable",
                        key, existing.getBlockEntityClass().getName()));
            }
            return;
        }
        BlockEntityType.REGISTRY.register(new BlockEntityType.Impl(
                key,
                BitsNBobsStrutBlockEntity.class
        ));
    }

    private static void registerBlockEntities() {
        for (Key key : BLOCK_ENTITY_IDS) {
            BlockEntityType existing = BlockEntityType.REGISTRY.get(key);
            if (existing != null) {
                if (!CopycatsTerrainBlockEntity.class.equals(existing.getBlockEntityClass())) {
                    Logger.global.logWarning(String.format(
                            "Block entity %s is already registered to %s; "
                                    + "Copycats NBT decoding for that id may be unavailable",
                            key, existing.getBlockEntityClass().getName()));
                }
                continue;
            }

            BlockEntityType.REGISTRY.register(new BlockEntityType.Impl(
                    key,
                    CopycatsTerrainBlockEntity.class
            ));
        }
    }
}
