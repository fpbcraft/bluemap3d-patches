package dev.duzo.bluemap3d.entities;

import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectLifecycle;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.horse.Llama;
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

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Publishes every loaded surface mob. Model resolution is intentionally not done here:
 * core resolves the entity's real resource/model assets, including mod namespaces.
 */
public final class SurfaceMobProvider implements SceneObjectProvider {
    private final Map<ResourceKey<Level>, Set<String>> deletedByDimension =
            new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "surface_mobs";
    }

    @Override
    public SceneObjectLifecycle lifecycle() {
        // Keep the last published transform while the backing chunk is unloaded, but do
        // not record ordinary mobs into Player History.
        return SceneObjectLifecycle.RESTORE_ONLY;
    }

    void entityLeft(Entity entity, Level level) {
        if (!(entity instanceof Mob) || level.isClientSide()) return;

        Entity.RemovalReason reason = entity.getRemovalReason();
        if (!shouldForget(reason)) return;

        deletedByDimension
                .computeIfAbsent(level.dimension(), ignored -> ConcurrentHashMap.newKeySet())
                .add(entity.getUUID().toString());
    }

    static boolean shouldForget(Entity.RemovalReason reason) {
        // Both unload reasons are temporary from the map's point of view. In particular,
        // UNLOADED_WITH_PLAYER is not marked save-to-chunk by vanilla, but the entity can
        // return with its player and must not be mistaken for a despawn.
        return reason == Entity.RemovalReason.KILLED
                || reason == Entity.RemovalReason.DISCARDED
                || reason == Entity.RemovalReason.CHANGED_DIMENSION;
    }

    @Override
    public Collection<String> deletedObjectIds(ServerLevel level) {
        Set<String> deleted = deletedByDimension.remove(level.dimension());
        return deleted == null ? List.of() : List.copyOf(deleted);
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

            // Reserved values are consumed by EntityModelSource. Appearance tokens are
            // intentionally generic so modded mobs exposing a conventional getVariant(),
            // getColor(), etc. automatically participate in asset selection.
            Map<String, String> metadata = appearanceMetadata(mob, width, height);

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
                    "surface-mob:" + typeId + ":" + width16 + "x" + height16
                            + ":" + Integer.toUnsignedString(metadata.hashCode(), 36),
                    geometry,
                    mob.position(),
                    rotation,
                    new Vector3f(1F, 1F, 1F)));
        }

        return List.copyOf(out);
    }

    private static final List<String> APPEARANCE_GETTERS = List.of(
            "getVariant",
            "getVariantString",
            "getColor",
            "getDyeColor",
            "getBreed",
            "getMarkings",
            "getPattern",
            "getStyle",
            "getSkin",
            "getTexture",
            "getPuffState");

    /**
     * Optional indexed appearance conventions that need two calls to resolve a stable
     * texture token. Kept reflective so addon-entities has no compile-time dependency on
     * the owning mod.
     */
    private static final List<IndexedAppearanceConvention> INDEXED_APPEARANCE_CONVENTIONS =
            List.of(new IndexedAppearanceConvention(
                    "bhCoat",
                    "bhCoatSet",
                    "coatId",
                    "texture"));

    /** Reflection discovery is per entity class, not per mob per publish tick. */
    private static final Map<Class<?>, List<Method>> APPEARANCE_METHODS =
            new ConcurrentHashMap<>();

    static Map<String, String> diagnosticMetadata(Mob mob) {
        return appearanceMetadata(
                mob,
                Math.max(0.1F, mob.getBbWidth()),
                Math.max(0.1F, mob.getBbHeight()));
    }

    private static Map<String, String> appearanceMetadata(Mob mob, float width, float height) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put("__bm3d_width", Float.toString(width));
        metadata.put("__bm3d_height", Float.toString(height));

        if (mob instanceof AgeableMob ageable && ageable.isBaby()) {
            metadata.put("__bm3d_visual_age", "baby");
        }

        // Sheep wool is a runtime tint over a separate model layer, not a texture variant.
        if (mob instanceof Sheep sheep) {
            int rgb = sheep.getColor().getTextureDiffuseColor() & 0xFFFFFF;
            metadata.put("__bm3d_tint", String.format(Locale.ROOT, "%06x", rgb));
            metadata.put("__bm3d_visual_color", sheep.getColor().getSerializedName());
            if (sheep.isSheared()) {
                metadata.put("__bm3d_hide_wool", "true");
            }
        }

        // Vanilla's llama renderer uses the ordinary llama coat for both llamas and
        // trader llamas, then optionally renders ModelLayers.LLAMA_DECOR on top. Generic
        // filename matching cannot safely infer that split because trader_llama.png is
        // the decor texture and otherwise looks like an exact main-texture match.
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
        if (mob instanceof Llama llama
                && typeId != null
                && "minecraft".equals(typeId.getNamespace())) {
            String variant = appearanceToken(llama.getVariant());
            if (variant != null && !variant.isBlank()) {
                metadata.put(
                        "__bm3d_texture_main",
                        "minecraft:textures/entity/llama/" + variant + ".png");
            }

            if (llama.getSwag() != null) {
                metadata.put(
                        "__bm3d_texture_decor",
                        "minecraft:textures/entity/llama/decor/"
                                + llama.getSwag().getSerializedName() + ".png");
            } else if (llama.isTraderLlama()) {
                metadata.put(
                        "__bm3d_texture_decor",
                        "minecraft:textures/entity/llama/decor/trader_llama.png");
            }
        }

        int javaType = 0;
        for (Class<?> type = mob.getClass();
                type != null
                        && type != Mob.class
                        && type != Object.class
                        && javaType < 8;
                type = type.getSuperclass()) {
            String name = type.getSimpleName();
            if (name == null || name.isBlank()) continue;
            metadata.put("__bm3d_java_type_" + javaType++, name);
        }

        int visual = 0;
        for (Method method : APPEARANCE_METHODS.computeIfAbsent(
                mob.getClass(), SurfaceMobProvider::discoverAppearanceMethods)) {
            try {
                String token = appearanceToken(method.invoke(mob));
                if (token == null || token.isBlank()) continue;
                metadata.put("__bm3d_visual_" + visual++, token);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Optional convention: a mod-specific getter failure is harmless.
            }
        }

        visual = appendIndexedAppearance(mob, metadata, visual);

        return Map.copyOf(metadata);
    }

    private static int appendIndexedAppearance(
            Object subject, Map<String, String> metadata, int visual) {
        for (IndexedAppearanceConvention convention : INDEXED_APPEARANCE_CONVENTIONS) {
            try {
                Method indexGetter = subject.getClass().getMethod(convention.indexGetter());
                Method paletteGetter = subject.getClass().getMethod(convention.paletteGetter());
                if (indexGetter.getParameterCount() != 0 || paletteGetter.getParameterCount() != 0) {
                    continue;
                }

                Object rawIndex = indexGetter.invoke(subject);
                Object palette = paletteGetter.invoke(subject);
                if (!(rawIndex instanceof Number index) || palette == null) continue;

                Method idGetter =
                        palette.getClass().getMethod(convention.idGetter(), int.class);
                String token = appearanceToken(idGetter.invoke(palette, index.intValue()));
                if (token != null && !token.isBlank()) {
                    metadata.put("__bm3d_visual_" + visual++, token);
                }

                // Some mods expose the same exact texture resource used by their renderer.
                // Prefer carrying that resource through to the model source instead of
                // asking the generic filename scorer to rediscover it. Icy's Better Horses
                // uses BreedCoatSet#texture(coat, baby) for BhHorseRenderer#getTextureLocation.
                if (convention.textureGetter() != null) {
                    Method textureGetter = palette.getClass().getMethod(
                            convention.textureGetter(), int.class, boolean.class);
                    boolean baby = subject instanceof AgeableMob ageable && ageable.isBaby();
                    Object texture = textureGetter.invoke(palette, index.intValue(), baby);
                    if (texture instanceof ResourceLocation location) {
                        metadata.put("__bm3d_texture_main", location.toString());
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Optional convention: absence/failure must never break generic mob publishing.
            }
        }
        return visual;
    }

    private static List<Method> discoverAppearanceMethods(Class<?> type) {
        List<Method> methods = new ArrayList<>();
        for (String getter : APPEARANCE_GETTERS) {
            try {
                Method method = type.getMethod(getter);
                if (method.getParameterCount() == 0
                        && !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    methods.add(method);
                }
            } catch (NoSuchMethodException | SecurityException ignored) {
                // Convention not exposed by this entity type.
            }
        }
        return List.copyOf(methods);
    }

    private static String appearanceToken(Object value) {
        if (value == null) return null;
        if (value instanceof StringRepresentable serializable) {
            return serializable.getSerializedName();
        }
        if (value instanceof ResourceLocation location) {
            return location.getPath();
        }
        if (value instanceof ResourceKey<?> key) {
            return key.location().getPath();
        }
        if (value instanceof Holder<?> holder) {
            return holder.unwrapKey()
                    .map(key -> key.location().getPath())
                    .orElse(null);
        }
        if (value instanceof Enum<?> enumeration) {
            return enumeration.name().toLowerCase(Locale.ROOT);
        }
        if (value instanceof CharSequence text) {
            String raw = text.toString();
            ResourceLocation location = ResourceLocation.tryParse(raw);
            return location == null ? raw : location.getPath();
        }
        if (value instanceof Number number) {
            return number.toString();
        }

        // Registry holders and small mod value objects often expose a useful identifier
        // only through toString(). Reject default Object.toString()-style identities.
        String text = String.valueOf(value);
        if (text.length() > 96 || text.matches(".*@[0-9a-fA-F]+$")) return null;
        return text;
    }

    private record IndexedAppearanceConvention(
            String indexGetter,
            String paletteGetter,
            String idGetter,
            String textureGetter) {
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
            return 6L;
        }
    }
}
