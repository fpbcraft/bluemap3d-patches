package dev.duzo.bluemapfurniture;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Optional bridge to Immersive Furniture's server-side registry/cache.
 *
 * <p>It lets low-memory furniture blocks (which only store an identifier) resolve the
 * same FurnitureData that the mod itself uses without taking a compile-time dependency.
 */
final class ImmersiveFurnitureRuntime {

    private static final Map<String, ImmersiveFurnitureData.Definition> BY_HASH =
            new ConcurrentHashMap<>();
    private static volatile Api api;
    private static volatile boolean unavailableLogged;

    private ImmersiveFurnitureRuntime() {
    }

    static ImmersiveFurnitureData.Definition resolve(
            BlockNeighborhood block, ImmersiveFurnitureBlockEntity entity) {
        ImmersiveFurnitureData.Definition inline =
                ImmersiveFurnitureData.decodeNbt(entity.furniture());
        if (inline != null && !inline.isEmpty()) return inline;

        String hash = entity.furnitureHash();
        if (hash == null || hash.isBlank()) {
            hash = hashForIdentifier(block);
        }
        if (hash == null || hash.isBlank()) return inline;

        ImmersiveFurnitureData.Definition cached = BY_HASH.get(hash);
        if (cached != null) return cached;

        Api runtime = api();
        if (runtime == null) return inline;

        try {
            Object data = runtime.getData().invoke(null, hash);
            ImmersiveFurnitureData.Definition decoded =
                    ImmersiveFurnitureData.decodeRuntime(data);
            if (decoded != null && !decoded.isEmpty()) {
                BY_HASH.put(hash, decoded);
                return decoded;
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            logUnavailable(error);
        }
        return inline;
    }

    private static String hashForIdentifier(BlockNeighborhood block) {
        String value = block.getBlockState().getProperties().get("identifier");
        if (value == null) return null;

        int identifier;
        try {
            identifier = Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }

        Api runtime = api();
        if (runtime == null) return null;
        try {
            Object resolved = runtime.resolveIdentifier().invoke(null, identifier);
            return resolved instanceof String hash ? hash : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            logUnavailable(error);
            return null;
        }
    }

    private static Api api() {
        Api current = api;
        if (current != null) return current;
        try {
            Class<?> registry =
                    Class.forName("net.conczin.immersive_furniture.data.FurnitureRegistry");
            Class<?> manager =
                    Class.forName("net.conczin.immersive_furniture.data.FurnitureDataManager");
            current = new Api(
                    registry.getMethod("resolve", int.class),
                    manager.getMethod("getData", String.class));
            api = current;
            return current;
        } catch (ReflectiveOperationException | RuntimeException error) {
            logUnavailable(error);
            return null;
        }
    }

    private static void logUnavailable(Throwable error) {
        if (unavailableLogged) return;
        unavailableLogged = true;
        Logger.global.logWarning(String.format(
                "Immersive Furniture runtime bridge unavailable; inline furniture NBT can still render: %s",
                error));
    }

    private record Api(Method resolveIdentifier, Method getData) {
    }
}
