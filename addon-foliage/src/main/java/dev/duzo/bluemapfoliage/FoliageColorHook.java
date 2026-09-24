package dev.duzo.bluemapfoliage;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reproduces fixed client BlockColor behavior that BlueMap 5.7 cannot infer from
 * resource-pack models alone.
 *
 * <p>Quark blossom textures already contain their final blue/lavender/orange/red/yellow
 * color. Minecraft therefore leaves their tint-index faces white. BlueMap's generic
 * leaves rule applies biome foliage green instead. Dynamic Trees similarly delegates
 * each species to its LeavesProperties/primitive leaf, so cherry/azalea are untinted
 * while birch and spruce use their fixed vanilla foliage colors.
 */
final class FoliageColorHook {

    private static final int WHITE = 0xFFFFFF;
    private static final int BIRCH = 0x80A755;
    private static final int SPRUCE = 0x619961;

    private FoliageColorHook() {
    }

    @SuppressWarnings("unchecked")
    static void install(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            Logger.global.logWarning(String.format(
                    "Foliage compatibility requires BlueMap 5.7 common API implementation; got %s",
                    api.getClass().getName()));
            return;
        }

        ResourcePack resourcePack = impl.blueMapService().getResourcePack();
        if (resourcePack == null) {
            Logger.global.logWarning("Foliage compatibility could not access BlueMap resource pack");
            return;
        }

        try {
            Object factory = resourcePack.getColorCalculatorFactory();
            Field mapField = factory.getClass().getDeclaredField("blockColorMap");
            mapField.setAccessible(true);
            Map<String, Object> map = (Map<String, Object>) mapField.get(factory);

            Class<?> colorFunctionType = Class.forName(
                    "de.bluecolored.bluemap.core.resources.BlockColorCalculatorFactory$ColorFunction");

            Map<String, Integer> colors = fixedColors();
            int installed = 0;
            for (Map.Entry<String, Integer> entry : colors.entrySet()) {
                int rgb = entry.getValue();
                Object function = Proxy.newProxyInstance(
                        colorFunctionType.getClassLoader(),
                        new Class<?>[] {colorFunctionType},
                        (proxy, method, args) -> invokeColor(proxy, method, args, rgb));
                map.put(entry.getKey(), function);
                installed++;
            }

            Logger.global.logInfo(String.format(
                    "Foliage compatibility installed for %s block id(s)", installed));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError("Failed to install foliage compatibility", error);
        }
    }

    private static Object invokeColor(
            Object proxy,
            Method method,
            Object[] args,
            int rgb) {
        if ("invoke".equals(method.getName()) && args != null && args.length == 3) {
            Color target = (Color) args[2];
            return target.set(0xFF000000 | rgb, true);
        }

        return switch (method.getName()) {
            case "toString" -> "FoliageCompatColorFunction";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
    }

    private static Map<String, Integer> fixedColors() {
        Map<String, Integer> out = new LinkedHashMap<>();

        for (String color : new String[] {
                "blue", "lavender", "orange", "red", "yellow"
        }) {
            out.put("quark:" + color + "_blossom_leaves", WHITE);
            out.put("quark:" + color + "_blossom_leaf_carpet", WHITE);
            out.put("quark:" + color + "_blossom_hedge", WHITE);
        }

        out.put("dynamictrees:cherry_leaves", WHITE);
        out.put("dynamictrees:azalea_leaves", WHITE);
        out.put("dynamictrees:flowering_azalea_leaves", WHITE);
        out.put("dynamictrees:birch_leaves", BIRCH);
        out.put("dynamictrees:spruce_leaves", SPRUCE);

        return out;
    }
}
