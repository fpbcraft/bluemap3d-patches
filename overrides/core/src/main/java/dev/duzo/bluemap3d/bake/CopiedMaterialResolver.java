package dev.duzo.bluemap3d.bake;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Set;

/**
 * Resolves the material stored by Create/Copycats-style copycat block entities.
 *
 * <p>The moving renderer snapshots block-entity NBT instead of linking against every
 * copycat implementation. Keeping the NBT traversal here gives connected-texture lookup,
 * procedural Copycats models, and Railways copycat headstocks the same material semantics.
 */
final class CopiedMaterialResolver {

    private static final Set<String> MATERIAL_DATA_NAMES =
            Set.of("material_data", "MaterialData", "materials", "Materials");

    private CopiedMaterialResolver() {
    }

    static BlockState effectiveState(BlockState wrapper, CompoundTag metadata) {
        if (wrapper == null || !isMaterialWrapper(wrapper)) return wrapper;
        BlockState material = materialFor(metadata, null);
        return usable(material) ? material : wrapper;
    }

    static BlockState materialFor(CompoundTag root, String part) {
        if (root == null) return null;

        CompoundTag data = findMaterialData(root, 0);
        BlockState specific = null;
        if (part != null && data != null && data.contains(part, Tag.TAG_COMPOUND)) {
            specific = parseMaterial(findMaterialCompound(data.getCompound(part), 0));
            if (usable(specific)) return specific;
        }

        BlockState direct = parseMaterial(findMaterialCompound(root, 0));
        if (usable(direct)) return direct;

        if (data != null) {
            for (String key : data.getAllKeys()) {
                if (!data.contains(key, Tag.TAG_COMPOUND)) continue;
                BlockState parsed =
                        parseMaterial(findMaterialCompound(data.getCompound(key), 0));
                if (usable(parsed)) return parsed;
            }
        }
        return specific;
    }

    static boolean usable(BlockState material) {
        if (material == null) return false;
        String id = BuiltInRegistries.BLOCK.getKey(material.getBlock()).toString();
        return !"create:copycat_base".equals(id)
                && !"copycats:copycat_base".equals(id)
                && !"minecraft:air".equals(id);
    }

    static boolean isMaterialWrapper(BlockState state) {
        if (state == null) return false;
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        String namespace = id.getNamespace();
        String path = id.getPath();

        if ("copycats".equals(namespace)) return true;
        if (("create".equals(namespace) || "create_connected".equals(namespace))
                && path.contains("copycat")) {
            return true;
        }
        return "railways".equals(namespace) && path.startsWith("copycat_");
    }

    private static CompoundTag findMaterialData(CompoundTag tag, int depth) {
        if (tag == null || depth > 8) return null;
        for (String name : MATERIAL_DATA_NAMES) {
            if (tag.contains(name, Tag.TAG_COMPOUND)) {
                CompoundTag data = tag.getCompound(name);
                if (!data.isEmpty()) return data;
            }
        }

        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                CompoundTag found = findMaterialData(compound, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static CompoundTag findMaterialCompound(CompoundTag tag, int depth) {
        if (tag == null || depth > 8) return null;
        for (String key : List.of("material", "Material")) {
            if (tag.contains(key, Tag.TAG_COMPOUND)) {
                CompoundTag value = tag.getCompound(key);
                if (!value.isEmpty()) return value;
            }
        }

        for (String key : tag.getAllKeys()) {
            Tag child = tag.get(key);
            if (child instanceof CompoundTag compound) {
                CompoundTag found = findMaterialCompound(compound, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static BlockState parseMaterial(CompoundTag material) {
        if (material == null || material.isEmpty()) return null;
        try {
            return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), material);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
