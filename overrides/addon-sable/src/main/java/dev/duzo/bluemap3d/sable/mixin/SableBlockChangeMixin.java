package dev.duzo.bluemap3d.sable.mixin;

import dev.duzo.bluemap3d.sable.ShipGeometryRevisionTracker;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.SableCommonEvents;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Sable already sees every server-side block change in a sub-level here. Reuse that
 * authoritative path instead of rescanning whole ships every BlueMap publish.
 */
@Mixin(value = SableCommonEvents.class, remap = false)
public abstract class SableBlockChangeMixin {

    @Inject(method = "handleBlockChange", at = @At("TAIL"))
    private static void bluemap3d$markShipGeometryDirty(
            ServerLevel level,
            LevelChunk chunk,
            int x,
            int y,
            int z,
            BlockState oldState,
            BlockState newState,
            CallbackInfo ci) {
        if (oldState == newState) return;

        SubLevel subLevel = Sable.HELPER.getContaining(level, new BlockPos(x, y, z));
        if (subLevel != null) {
            ShipGeometryRevisionTracker.markDirty(subLevel.getUniqueId());
        }
    }
}
