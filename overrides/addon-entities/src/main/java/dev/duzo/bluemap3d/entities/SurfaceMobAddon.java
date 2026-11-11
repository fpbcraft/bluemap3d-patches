package dev.duzo.bluemap3d.entities;

import dev.duzo.bluemap3d.api.BlueMap3D;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;

/** Registers the vanilla surface-mob provider. */
@Mod(SurfaceMobAddon.MOD_ID)
public final class SurfaceMobAddon {
    public static final String MOD_ID = "bluemap3d_entities";

    private final SurfaceMobProvider provider;

    public SurfaceMobAddon() {
        provider = new SurfaceMobProvider();
        BlueMap3D.register(provider);
        NeoForge.EVENT_BUS.addListener(SurfaceMobCommands::register);
        NeoForge.EVENT_BUS.addListener(this::onEntityLeave);
    }

    private void onEntityLeave(EntityLeaveLevelEvent event) {
        provider.entityLeft(event.getEntity(), event.getLevel());
    }
}
