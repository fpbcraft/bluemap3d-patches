package dev.duzo.bluemap3d.create.mixin;

import dev.duzo.bluemap3d.create.SimulatedSpringRegistry;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Optional lifecycle hook for Create: Simulated springs. */
@Mixin(
        targets = "dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity",
        remap = false)
public abstract class SimulatedSpringBlockEntityMixin {

    @Inject(method = "tick", at = @At("HEAD"))
    private void bluemap3d$observeSpring(CallbackInfo ci) {
        SimulatedSpringRegistry.observe((BlockEntity) (Object) this);
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void bluemap3d$forgetSpring(CallbackInfo ci) {
        SimulatedSpringRegistry.forget((BlockEntity) (Object) this);
    }
}
