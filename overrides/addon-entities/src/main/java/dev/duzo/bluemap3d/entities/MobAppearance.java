package dev.duzo.bluemap3d.entities;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable shared geometry description for one simplified vanilla mob appearance. */
record MobAppearance(String id, BlockVolume geometry) {
    MobAppearance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(geometry, "geometry");
    }

    static MobAppearance of(String id, String modelPath, Map<String, String> textures) {
        ResourceLocation model =
                ResourceLocation.fromNamespaceAndPath(SurfaceMobAddon.MOD_ID, modelPath);
        BlockVolume volume = BlockVolume.attachments(
                BlockPos.ZERO,
                BlockPos.ZERO,
                new Vec3(0.5, 0.0, 0.5),
                List.of(new ModelAttachment(BlockPos.ZERO, model, textures)));
        return new MobAppearance(id, volume);
    }
}
