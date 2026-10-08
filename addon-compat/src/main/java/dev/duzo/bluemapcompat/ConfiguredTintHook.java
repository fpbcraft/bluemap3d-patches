package dev.duzo.bluemapcompat;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import dev.duzo.bluemapseasons.SeasonalTintBridge;
import dev.duzo.bluemapseasons.SeasonalSnowSurface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One generic BlueMap 5.7 tint hook for all config-driven compatibility.
 *
 * <p>The proxy is installed for every loaded block ID once. When no compatibility rule
 * matches, it delegates to the block's original BlueMap color callback, then BlueMap's
 * default callback, then the normal blended-foliage fallback. Rule evaluation itself is
 * backed by {@link CompatManager}, so external JSON edits hot-reload without replacing
 * the proxy.
 */
final class ConfiguredTintHook {

    private final Map<String, Object> originals = new LinkedHashMap<>();

    private Object defaultOriginal;
    private Class<?> colorFunctionType;
    private Method colorFunctionInvoke;
    private Method blendedFoliage;

    private ConfiguredTintHook() {
    }

    static ConfiguredTintHook install(BlueMapAPI api) {
        ConfiguredTintHook hook = new ConfiguredTintHook();
        hook.installInternal(api);
        return hook;
    }

    @SuppressWarnings("unchecked")
    private void installInternal(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            Logger.global.logWarning(String.format(
                    "Config-driven compatibility requires BlueMap 5.7 common API implementation; got %s",
                    api.getClass().getName()));
            return;
        }

        ResourcePack resourcePack = impl.blueMapService().getResourcePack();
        if (resourcePack == null) {
            Logger.global.logWarning("Config-driven compatibility could not access BlueMap resource pack");
            return;
        }

        try {
            Object factory = resourcePack.getColorCalculatorFactory();

            Field colorMapField = factory.getClass().getDeclaredField("blockColorMap");
            colorMapField.setAccessible(true);
            Map<String, Object> colorMap = (Map<String, Object>) colorMapField.get(factory);

            Field blockStatePathsField = ResourcePack.class.getDeclaredField("blockStatePaths");
            blockStatePathsField.setAccessible(true);
            Map<String, ?> blockStatePaths = (Map<String, ?>) blockStatePathsField.get(resourcePack);

            colorFunctionType = Class.forName(
                    "de.bluecolored.bluemap.core.resources.BlockColorCalculatorFactory$ColorFunction");
            Class<?> calculatorType = Class.forName(
                    "de.bluecolored.bluemap.core.resources.BlockColorCalculatorFactory$BlockColorCalculator");
            colorFunctionInvoke = colorFunctionType.getDeclaredMethod(
                    "invoke", calculatorType, BlockNeighborhood.class, Color.class);
            colorFunctionInvoke.setAccessible(true);
            blendedFoliage = calculatorType.getMethod(
                    "getBlendedFoliageColor", BlockNeighborhood.class, Color.class);

            defaultOriginal = colorMap.get("default");
            for (String blockId : blockStatePaths.keySet()) {
                originals.put(blockId, colorMap.get(blockId));
            }

            Object proxy = Proxy.newProxyInstance(
                    colorFunctionType.getClassLoader(),
                    new Class<?>[] {colorFunctionType},
                    this::invoke);

            for (String blockId : blockStatePaths.keySet()) {
                colorMap.put(blockId, proxy);
            }

            Logger.global.logInfo(String.format(
                    "Config-driven tint proxy installed for %s loaded block id(s)",
                    blockStatePaths.size()));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError("Failed to install config-driven tint compatibility", error);
        }
    }

    private Object invoke(Object proxy, Method method, Object[] args) throws Exception {
        if (!"invoke".equals(method.getName()) || args == null || args.length != 3) {
            return switch (method.getName()) {
                case "toString" -> "ConfiguredBlueMapColorFunction";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
                default -> null;
            };
        }

        Object calculator = args[0];
        BlockNeighborhood block = (BlockNeighborhood) args[1];
        Color target = (Color) args[2];

        String blockId = block.getBlockState().getFormatted();
        SharedCompatRules.TintMatch match = CompatManager.rules().tint(
                blockId,
                block.getBlockState().getProperties(),
                "terrain");

        if (match != null) {
            Integer color = resolveTint(match.tint(), blockId, block);
            if (color != null) {
                return target.set(0xFF000000 | color, true);
            }
        }

        Object original = originals.get(blockId);
        if (original != null) {
            colorFunctionInvoke.invoke(original, calculator, block, target);
        } else if (defaultOriginal != null) {
            colorFunctionInvoke.invoke(defaultOriginal, calculator, block, target);
        } else {
            blendedFoliage.invoke(calculator, block, target);
        }
        // Explicit configured tints retain precedence; Ecliptic colors apply only to
        // the normal block-color path and only where the server reports a biome palette.
        return SeasonalSnowSurface.apply(blockId, block, SeasonalTintBridge.tint(blockId, block, target));
    }

    private Integer resolveTint(
            SharedCompatRules.Tint tint,
            String blockId,
            BlockNeighborhood block) {
        if (tint == null || tint.type == null) return null;

        return switch (tint.type) {
            case "none" -> 0xFFFFFF;
            case "fixed" -> parseColor(tint.color);
            case "palette" -> paletteColor(tint, blockId, block);
            default -> {
                Logger.global.logWarning("Unknown compatibility tint type: " + tint.type);
                yield null;
            }
        };
    }

    private Integer paletteColor(
            SharedCompatRules.Tint tint,
            String blockId,
            BlockNeighborhood block) {
        int value = -1;
        SharedCompatRules.ValueSource source =
                tint.value == null ? null : tint.value.terrain;

        if (source != null
                && "block_entity_method".equals(source.type)
                && source.method != null
                && block.getBlockEntity() != null) {
            try {
                Method method = block.getBlockEntity().getClass().getMethod(source.method);
                Object result = method.invoke(block.getBlockEntity());
                if (result instanceof Number number) {
                    value = number.intValue();
                }
            } catch (ReflectiveOperationException ignored) {
                // Adapter-backed NBT is optional. Fall back to the configured default.
            }
        }

        if (value >= 0 && tint.palette != null && value < tint.palette.size()) {
            return parseColor(tint.palette.get(value));
        }

        if (tint.defaultByBlock != null) {
            for (SharedCompatRules.DefaultColor entry : tint.defaultByBlock) {
                if (entry != null && entry.matches(blockId)) {
                    return parseColor(entry.color);
                }
            }
        }

        return parseColor(tint.defaultColor);
    }

    private static int parseColor(String value) {
        if (value == null || value.isBlank()) return 0xFFFFFF;
        String normalized = value.startsWith("#") ? value.substring(1) : value;
        return Integer.parseInt(normalized, 16) & 0xFFFFFF;
    }
}
