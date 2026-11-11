package dev.duzo.bluemap3d.create.mixin;

import dev.duzo.bluemap3d.create.SimulatedCoasterTrackRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional Coasters Simulated hook; does not load the mod when it is absent. */
@Mixin(
        targets = "dev.silvergold.simulatedcoasters.track.anchor.CoasterAnchorpointBlockEntity",
        remap = false)
public abstract class SimulatedCoasterAnchorMixin {

    @Inject(method = "onLoad", at = @At("TAIL"))
    private void bluemap3d$loaded(CallbackInfo ci) {
        SimulatedCoasterTrackRegistry.observe((BlockEntity) (Object) this);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void bluemap3d$unloaded(CallbackInfo ci) {
        SimulatedCoasterTrackRegistry.forget((BlockEntity) (Object) this);
    }

    @Inject(method = "removeAnchorPeerCurve", at = @At("HEAD"))
    private void bluemap3d$removePeer(ServerLevel level, BlockPos peer, CallbackInfo ci) {
        SimulatedCoasterTrackRegistry.removedPeer((BlockEntity) (Object) this, peer);
    }

    @Inject(method = "detachAllConnections", at = @At("HEAD"))
    private void bluemap3d$detachAll(CallbackInfo ci) {
        BlockEntity self = (BlockEntity) (Object) this;
        try {
            Object value = self.getClass().getMethod("getAnchorPeerCurvesView").invoke(self);
            if (value instanceof java.util.Map<?, ?> peers) {
                for (Object peer : peers.keySet()) {
                    if (peer instanceof BlockPos position) {
                        SimulatedCoasterTrackRegistry.removedPeer(self, position);
                    }
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // The provider remains fail-closed if a future Coasters release changes its API.
        }
    }
}
