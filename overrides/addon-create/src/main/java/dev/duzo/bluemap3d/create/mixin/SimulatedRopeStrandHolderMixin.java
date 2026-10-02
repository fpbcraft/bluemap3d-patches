package dev.duzo.bluemap3d.create.mixin;

import dev.duzo.bluemap3d.create.SimulatedRopeRegistry;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures real rope destruction without confusing it with chunk unload.
 *
 * <p>Simulated's manager removal method is used by both paths, while destroyRope() is
 * called only when the rope itself is intentionally/breakage-destroyed.
 */
@Mixin(
        targets = "dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior",
        remap = false)
public abstract class SimulatedRopeStrandHolderMixin {

    @Inject(method = "destroyRope", at = @At("HEAD"))
    private void bluemap3d$markRopeDestroyed(
            ServerPlayer player,
            Vec3 ropeDropPos,
            boolean returnItem,
            CallbackInfo ci) {
        SimulatedRopeRegistry.markDestroyed(this);
    }
}
