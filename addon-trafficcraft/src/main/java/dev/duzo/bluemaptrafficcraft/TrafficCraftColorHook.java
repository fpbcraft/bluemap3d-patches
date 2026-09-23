package dev.duzo.bluemaptrafficcraft;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.common.api.BlueMapAPIImpl;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Installs TrafficCraft's client-side BlockColor behavior into BlueMap 5.7.
 *
 * <p>BlueMap 5.7 stores tint callbacks in BlockColorCalculatorFactory's private
 * blockColorMap. Its public blockColors.json format only supports fixed colors and
 * built-in biome calculators, so a per-block-entity paint color cannot be expressed
 * as data. This hook changes only the callback for TrafficCraft's paintable blocks;
 * ordinary ResourceModelRenderer geometry and variant selection remain untouched.
 */
final class TrafficCraftColorHook {

    private TrafficCraftColorHook() {
    }

    @SuppressWarnings("unchecked")
    static void install(BlueMapAPI api) {
        if (!(api instanceof BlueMapAPIImpl impl)) {
            Logger.global.logWarning(String.format(
                    "TrafficCraft tint compatibility requires BlueMap 5.7 common API implementation; got %s",
                    api.getClass().getName()));
            return;
        }

        ResourcePack resourcePack = impl.blueMapService().getResourcePack();
        if (resourcePack == null) {
            Logger.global.logWarning("TrafficCraft tint compatibility could not access BlueMap resource pack");
            return;
        }

        try {
            Object factory = resourcePack.getColorCalculatorFactory();
            Field mapField = factory.getClass().getDeclaredField("blockColorMap");
            mapField.setAccessible(true);
            Map<String, Object> map = (Map<String, Object>) mapField.get(factory);

            Class<?> colorFunctionType = Class.forName(
                    "de.bluecolored.bluemap.core.resources.BlockColorCalculatorFactory$ColorFunction");

            Object colorFunction = Proxy.newProxyInstance(
                    colorFunctionType.getClassLoader(),
                    new Class<?>[] {colorFunctionType},
                    (proxy, method, args) -> invokeColor(proxy, method, args));

            int installed = 0;
            for (String id : TrafficCraftBlocks.paintableIds()) {
                map.put(id, colorFunction);
                installed++;
            }

            Logger.global.logInfo(String.format(
                    "TrafficCraft tint compatibility installed for %s paintable block id(s)",
                    installed));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logError("Failed to install TrafficCraft tint compatibility", error);
        }
    }

    private static Object invokeColor(Object proxy, Method method, Object[] args) {
        if ("invoke".equals(method.getName()) && args != null && args.length == 3) {
            BlockNeighborhood block = (BlockNeighborhood) args[1];
            Color target = (Color) args[2];
            int rgb = TrafficCraftPalette.color(block);
            return target.set(0xFF000000 | rgb, true);
        }

        return switch (method.getName()) {
            case "toString" -> "TrafficCraftPaintColorFunction";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == (args == null || args.length == 0 ? null : args[0]);
            default -> null;
        };
    }
}
