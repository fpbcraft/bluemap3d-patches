package dev.duzo.bluemap3d.sable.mixin;

import dev.duzo.bluemap3d.sable.ShipGeometryRevisionTracker;
import dev.ryanhcode.sable.SableCommonEvents;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the owning Sable ship dirty before Sable mutates the plot for a block change.
 *
 * <p>Looking the ship up at TAIL is too late for removals: Sable may already have shrunk
 * the plot/bounds enough that a removed edge block is no longer considered contained by
 * the sub-level. Create child-contraption assembly is exactly such a bulk-removal path,
 * which left the parent BlueMap3D hull using its previous mesh.
 */
@Mixin(value = SableCommonEvents.class, remap = false)
public abstract class SableBlockChangeMixin {

    @Inject(method = "handleBlockChange", at = @At("HEAD"))
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

        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        // Resolve ownership from the plot chunk before Sable updates its bounding box.
        // getContaining(position) is deliberately avoided here because removals can make
        // that lookup false by the time handleBlockChange reaches TAIL.
        PlotChunkHolder holder = container.getChunkHolder(chunk.getPos());
        if (holder == null) return;

        LevelPlot plot = container.getPlot(chunk.getPos());
        if (plot == null) return;

        SubLevel subLevel = plot.getSubLevel();
        if (subLevel != null) {
            ShipGeometryRevisionTracker.markDirty(subLevel.getUniqueId());
        }
    }
}
