package dev.duzo.bluemap3d.create;

import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Positive destruction channel for Create: Simulated ropes.
 *
 * <p>ServerLevelRopeManager.removeStrand() is also used for ordinary holder/chunk unload,
 * so it is not a deletion signal. RopeStrandHolderBehavior.destroyRope() is the
 * authoritative destructive path; its mixin records the UUID and level here before
 * Simulated removes the strand from its manager.
 */
public final class SimulatedRopeRegistry {

    private static final ConcurrentHashMap<ServerLevel, Set<UUID>> REMOVED =
            new ConcurrentHashMap<>();

    private SimulatedRopeRegistry() {}

    public static void markDestroyed(Object holder) {
        if (holder == null) return;

        try {
            Method getOwnedStrand = holder.getClass().getMethod("getOwnedStrand");
            Object strand = SimulatedReflection.invoke(getOwnedStrand, holder);
            if (strand == null) return;

            Method getUuid = strand.getClass().getMethod("getUUID");
            Object id = SimulatedReflection.invoke(getUuid, strand);
            if (!(id instanceof UUID uuid)) return;

            Method getLevel = holder.getClass().getDeclaredMethod("getLevel");
            getLevel.setAccessible(true);
            Object level = SimulatedReflection.invoke(getLevel, holder);
            if (!(level instanceof ServerLevel serverLevel)) return;

            REMOVED.computeIfAbsent(serverLevel, ignored -> ConcurrentHashMap.newKeySet())
                    .add(uuid);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional compatibility hook. If Simulated changes shape, provider discovery
            // will log the unsupported API instead of making the Create addon fail to load.
        }
    }

    public static Set<UUID> drainRemoved(ServerLevel level) {
        Set<UUID> removed = REMOVED.get(level);
        if (removed == null || removed.isEmpty()) return Set.of();

        Set<UUID> result = Set.copyOf(removed);
        removed.removeAll(result);
        if (removed.isEmpty()) REMOVED.remove(level, removed);
        return result;
    }

    public static void clear() {
        REMOVED.clear();
    }
}
