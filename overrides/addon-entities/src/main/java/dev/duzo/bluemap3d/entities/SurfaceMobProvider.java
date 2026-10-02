package dev.duzo.bluemap3d.entities;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectLifecycle;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Publishes every loaded surface mob. Model resolution is intentionally not done here:
 * core resolves the entity's real resource/model assets, including mod namespaces.
 */
public final class SurfaceMobProvider implements SceneObjectProvider {
    @Override
    public String id() {
        return "surface_mobs";
    }

    @Override
    public SceneObjectLifecycle lifecycle() {
        return SceneObjectLifecycle.LIVE_ONLY;
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        List<SceneObject> out = new ArrayList<>();

        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof Mob mob) || !mob.isAlive() || mob.isRemoved()) continue;

            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
            if (typeId == null) continue;

            int surfaceY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(mob.getX()),
                    Mth.floor(mob.getZ()));
            if (!SurfaceMobPolicy.isAtOrAboveSurface(mob.getY(), surfaceY)) continue;

            ResourceLocation model = modelLocation(typeId);
            float width = Math.max(0.1F, mob.getBbWidth());
            float height = Math.max(0.1F, mob.getBbHeight());

            // Reserved values are consumed only by EntityModelSource's final fallback.
            // A normal resource-pack or Geo model ignores them.
            Map<String, String> metadata = Map.of(
                    "__bm3d_width", Float.toString(width),
                    "__bm3d_height", Float.toString(height));

            BlockVolume geometry = BlockVolume.attachments(
                    BlockPos.ZERO,
                    BlockPos.ZERO,
                    Vec3.ZERO,
                    List.of(new ModelAttachment(BlockPos.ZERO, model, metadata)));

            Quaternionf rotation =
                    new Quaternionf().rotationY((float) Math.toRadians(-mob.getYRot()));
            String label = mob.hasCustomName() && mob.getCustomName() != null
                    ? mob.getCustomName().getString()
                    : typeId.toString();

            int width16 = Math.max(1, Math.round(width * 16F));
            int height16 = Math.max(1, Math.round(height * 16F));

            out.add(new MobSceneObject(
                    mob.getUUID().toString(),
                    label,
                    level.dimension(),
                    "surface-mob:" + typeId + ":" + width16 + "x" + height16,
                    geometry,
                    mob.position(),
                    rotation,
                    new Vector3f(1F, 1F, 1F)));
        }

        return List.copyOf(out);
    }

    static ResourceLocation modelLocation(ResourceLocation typeId) {
        return ResourceLocation.fromNamespaceAndPath(
                typeId.getNamespace(),
                "entity/" + typeId.getPath() + "/main");
    }

    private record MobSceneObject(
            String id,
            String label,
            ResourceKey<Level> dimension,
            String geometryKey,
            BlockVolume geometry,
            Vec3 position,
            Quaternionf rotation,
            Vector3f scale) implements SceneObject {

        private MobSceneObject {
            position = new Vec3(position.x, position.y, position.z);
            rotation = new Quaternionf(rotation);
            scale = new Vector3f(scale);
        }

        @Override
        public long geometryVersion() {
            return 2L;
        }
    }
}
