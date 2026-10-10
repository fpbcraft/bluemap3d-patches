package dev.duzo.bluemap3d.create.mixin;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import dev.duzo.bluemap3d.create.ContraptionDeletionTracker;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Distinguishes real Create contraption deletion from a temporary chunk unload.
 *
 * <p>Persistent BlueMap3D objects must survive {@code UNLOADED_TO_CHUNK} and dimension
 * transfer removals, but {@code discard()} after disassembly and {@code kill()} are
 * authoritative deletion signals. Without this hook each reassembly gets a new entity UUID
 * while the old persisted UUID remains visible, producing an ever-growing trail of duplicate
 * contraptions.
 */
@Mixin(AbstractContraptionEntity.class)
public abstract class ContraptionRemovalMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Create");

    @Inject(method = "remove", at = @At("HEAD"))
    private void bluemap3d$recordDestroyedContraption(
            Entity.RemovalReason reason, CallbackInfo ci) {
        AbstractContraptionEntity entity = (AbstractContraptionEntity) (Object) this;
        LOGGER.info(
                "CONTRAPTION-REMOVAL-DIAG id={} type={} reason={} dimension={} pos={}",
                entity.getUUID(),
                entity.getClass().getName(),
                reason,
                entity.level().dimension().location(),
                entity.position());

        if (reason != Entity.RemovalReason.DISCARDED
                && reason != Entity.RemovalReason.KILLED) {
            return;
        }

        ContraptionDeletionTracker.record(entity);
    }
}
