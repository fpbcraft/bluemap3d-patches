package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Non-blocking, opt-in snow eligibility sampling.
 *
 * The BlueMap renderer must never access Minecraft's live world or Ecliptic's API.
 * It requests locations; the server thread evaluates those requests in bounded batches.
 */
public final class SeasonalSnowSurface {
    private static final int MAX_PENDING = 2048;
    private static final int MAX_CACHED = 8192;
    private static final int PER_TICK = 128;
    private static final Queue<Position> pending = new ConcurrentLinkedQueue<>();
    private static final Set<Position> queued = ConcurrentHashMap.newKeySet();
    private static final Map<Position, Boolean> cache = new ConcurrentHashMap<>();

    private SeasonalSnowSurface() {}

    record Position(int x, int y, int z) {}

    public static void reset() {
        pending.clear();
        queued.clear();
        cache.clear();
    }

    public static Color apply(String blockId, BlockNeighborhood block, Color color) {
        if (!Boolean.getBoolean("bluemap.compat.ecliptic.snow")) return color;
        if (!snowCandidate(blockId)) return color;
        // This prevents an expensive cross-thread world lookup for most ordinary blocks.
        if (block.getNeighborBlock(0, 1, 0).getBlockState() == null) return color;
        String above = block.getNeighborBlock(0, 1, 0).getBlockState().getFormatted();
        if (!("minecraft:air".equals(above) || "minecraft:cave_air".equals(above))) return color;

        Position pos = new Position(block.getX(), block.getY(), block.getZ());
        Boolean snowy = cache.get(pos);
        if (snowy == null) {
            if (queued.size() < MAX_PENDING && queued.add(pos)) pending.offer(pos);
            return color;
        }
        return snowy ? snowTint(color) : color;
    }

    static boolean snowCandidate(String id) {
        // Refuse foliage, fluids, transparent models and explicitly snowy blockstates:
        // those require geometry/texture-specific handling.
        return id.equals("minecraft:grass_block") || id.equals("minecraft:dirt")
                || id.equals("minecraft:coarse_dirt") || id.equals("minecraft:podzol")
                || id.equals("minecraft:mycelium") || id.equals("minecraft:stone")
                || id.equals("minecraft:gravel");
    }

    static Color snowTint(Color color) {
        color.straight();
        final float mix = 0.90f;
        color.r = color.r * (1 - mix) + mix;
        color.g = color.g * (1 - mix) + mix;
        color.b = color.b * (1 - mix) + mix;
        return color;
    }

    /** Called on the server thread by the existing calendar poll. */
    static void drain(Object server) throws ReflectiveOperationException {
        if (!Boolean.getBoolean("bluemap.compat.ecliptic.snow")) return;
        if (cache.size() >= MAX_CACHED) {
            reset(); // Bounded memory; old positions will be sampled again on demand.
        }

        Object level = server.getClass().getMethod("overworld").invoke(server);
        Class<?> levelClass = Class.forName("net.minecraft.world.level.Level");
        Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
        Class<?> stateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
        Class<?> apiClass = Class.forName("com.teamtea.eclipticseasons.api.EclipticSeasonsApi");
        Constructor<?> positionCtor = blockPosClass.getConstructor(int.class, int.class, int.class);
        Method isLoaded = level.getClass().getMethod("hasChunkAt", blockPosClass);
        Method getBlockState = level.getClass().getMethod("getBlockState", blockPosClass);
        Method isSnowy = apiClass.getMethod("isSnowyBlock", levelClass, stateClass, blockPosClass);
        Object api = apiClass.getMethod("getInstance").invoke(null);

        for (int i = 0; i < PER_TICK; i++) {
            Position request = pending.poll();
            if (request == null) break;
            queued.remove(request);
            Object pos = positionCtor.newInstance(request.x(), request.y(), request.z());
            if (!(boolean) isLoaded.invoke(level, pos)) continue; // Never generate/load chunks.
            Object state = getBlockState.invoke(level, pos);
            cache.put(request, (boolean) isSnowy.invoke(api, level, state, pos));
        }
    }
}
