package dev.duzo.bluemap3d.entities;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectLifecycle;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
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

/**
 * Publishes loaded vanilla mobs whose feet are at or above the visible world surface.
 *
 * <p>One SceneObject is retained per mob UUID so interpolation never reassigns one mob's
 * transform to another when entities spawn or despawn. Geometry is still shared by
 * appearance through geometryKey(), so a herd only bakes one cow mesh.
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
            MobAppearance appearance = MobAppearanceRegistry.resolve(typeId);
            if (appearance == null) continue;

            int surfaceY = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    Mth.floor(mob.getX()),
                    Mth.floor(mob.getZ()));
            if (!SurfaceMobPolicy.isAtOrAboveSurface(mob.getY(), surfaceY)) continue;

            float width = Math.max(0.1f, mob.getBbWidth());
            float height = Math.max(0.1f, mob.getBbHeight());
            Quaternionf rotation =
                    new Quaternionf().rotationY((float) Math.toRadians(-mob.getYRot()));
            Vector3f scale = new Vector3f(width, height, width);

            String label = mob.hasCustomName() && mob.getCustomName() != null
                    ? mob.getCustomName().getString()
                    : typeId.toString();

            out.add(new MobSceneObject(
                    mob.getUUID().toString(),
                    label,
                    level.dimension(),
                    "surface-mob-" + appearance.id(),
                    appearance.geometry(),
                    mob.position(),
                    rotation,
                    scale));
        }

        return List.copyOf(out);
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
            return 1L;
        }
    }
}
