package dev.duzo.bluemap3d.bake;

import dev.duzo.bluemap3d.compat.CompatRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Generic moving-object compatibility source.
 *
 * <p>A matching model alias is resolved first, preserving same-named compatible
 * blockstate properties where possible. A matching tint rule is then applied to the
 * resolved model. This allows one config rule to compose model + tint behavior without
 * introducing source-order coupling.
 */
public final class ConfiguredRuleSource implements BlockModelSource {

    private final ResourcePackSource models;

    public ConfiguredRuleSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return quadsFor(state, null);
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state, CompoundTag metadata) {
        CompatRegistry registry = CompatRegistry.get();

        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Map<String, String> properties = properties(state);

        CompatRegistry.ModelMatch modelMatch = registry.model(blockId, properties);
        CompatRegistry.TintMatch tintMatch = registry.tint(blockId, properties);

        if (modelMatch == null && tintMatch == null) {
            return List.of();
        }

        BlockState renderState = state;
        if (modelMatch != null) {
            String sourceId = modelMatch.model().resolveSourceBlock(blockId);
            BlockState aliased = aliasState(sourceId, state);
            if (aliased != null) {
                renderState = aliased;
            } else if (tintMatch == null) {
                return List.of();
            }
        }

        Integer tint = tintMatch == null
                ? null
                : resolveTint(tintMatch.tint(), blockId, metadata);

        List<ModelQuad> quads = tint == null
                ? models.quadsFor(renderState)
                : models.quadsForWithTint(renderState, tint);
        return quads.isEmpty() ? List.of() : quads;
    }

    @Override
    public BufferedImage texture(String texture) {
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return models.occludes(state);
    }

    private static BlockState aliasState(String sourceIdText, BlockState target) {
        ResourceLocation sourceId = ResourceLocation.tryParse(sourceIdText);
        if (sourceId == null) return null;

        Block sourceBlock = BuiltInRegistries.BLOCK.get(sourceId);
        if (sourceBlock == null) return null;

        BlockState source = sourceBlock.defaultBlockState();
        for (Property<?> sourceProperty : source.getProperties()) {
            Property<?> targetProperty = property(target, sourceProperty.getName());
            if (targetProperty == null) continue;

            String targetValue = valueName(target, targetProperty);
            Optional<?> parsed = sourceProperty.getValue(targetValue);
            if (parsed.isPresent()) {
                source = setValue(source, sourceProperty, (Comparable<?>) parsed.get());
            }
        }
        return source;
    }

    private static Integer resolveTint(
            CompatRegistry.Tint tint,
            String blockId,
            CompoundTag metadata) {
        if (tint == null || tint.type() == null) return null;

        return switch (tint.type()) {
            case "none" -> 0xFFFFFF;
            case "fixed" -> parseColor(tint.color());
            case "palette" -> {
                int value = -1;
                String path = tint.movingNbtPath();
                if (path != null) {
                    Integer found = intAtPath(metadata, path);
                    if (found != null) value = found;
                }

                if (value >= 0 && value < tint.palette().size()) {
                    yield parseColor(tint.palette().get(value));
                }
                yield parseColor(tint.defaultColorFor(blockId));
            }
            default -> null;
        };
    }

    private static Integer intAtPath(CompoundTag root, String path) {
        if (root == null || path == null || path.isBlank()) return null;

        Tag current = root;
        String[] parts = path.split("\\.");
        for (int i = 0; i < parts.length; i++) {
            if (!(current instanceof CompoundTag compound)) return null;
            String part = parts[i];

            if (i == parts.length - 1) {
                if (compound.contains(part, Tag.TAG_INT)) return compound.getInt(part);
                if (compound.contains(part, Tag.TAG_SHORT)) return (int) compound.getShort(part);
                if (compound.contains(part, Tag.TAG_BYTE)) return (int) compound.getByte(part);
                return null;
            }

            current = compound.get(part);
            if (current == null) return null;
        }
        return null;
    }

    private static Map<String, String> properties(BlockState state) {
        Map<String, String> values = new LinkedHashMap<>();
        for (Map.Entry<Property<?>, Comparable<?>> entry : state.getValues().entrySet()) {
            values.put(entry.getKey().getName(), valueName(state, entry.getKey()));
        }
        return values;
    }

    private static Property<?> property(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (name.equals(property.getName())) return property;
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String valueName(
            BlockState state,
            Property<?> property) {
        Property raw = property;
        return raw.getName(state.getValue(raw));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState setValue(
            BlockState state,
            Property property,
            Comparable value) {
        return state.setValue(property, value);
    }

    private static int parseColor(String value) {
        if (value == null || value.isBlank()) return 0xFFFFFF;
        String normalized = value.startsWith("#") ? value.substring(1) : value;
        return Integer.parseInt(normalized, 16) & 0xFFFFFF;
    }
}
