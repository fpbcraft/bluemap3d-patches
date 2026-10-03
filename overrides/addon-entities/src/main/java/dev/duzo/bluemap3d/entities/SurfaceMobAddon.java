package dev.duzo.bluemap3d.entities;

import dev.duzo.bluemap3d.api.BlueMap3D;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

/** Registers the vanilla surface-mob provider. */
@Mod(SurfaceMobAddon.MOD_ID)
public final class SurfaceMobAddon {
    public static final String MOD_ID = "bluemap3d_entities";

    public SurfaceMobAddon() {
        BlueMap3D.register(new SurfaceMobProvider());
        NeoForge.EVENT_BUS.addListener(SurfaceMobCommands::register);
    }
}
