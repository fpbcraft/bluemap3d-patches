package dev.duzo.bluemap3d.create;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Lifecycle registry for loaded Create: Simulated spring block entities. */
public final class SimulatedSpringRegistry {

    private static final Map<ServerLevel, Map<BlockEntity, String>> ACTIVE =
            new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Set<String>> REMOVED =
            new ConcurrentHashMap<>();

    private SimulatedSpringRegistry() {}

    public static void observe(BlockEntity blockEntity) {
        if (blockEntity == null || blockEntity.isRemoved()
                || !(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ACTIVE.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>())
                .put(blockEntity, logicalId(blockEntity));
    }

    public static void forget(BlockEntity blockEntity) {
        if (blockEntity == null || !(blockEntity.getLevel() instanceof ServerLevel level)) {
            return;
        }

        Map<BlockEntity, String> active = ACTIVE.get(level);
        String id = active == null ? null : active.remove(blockEntity);
        if (active != null && active.isEmpty()) ACTIVE.remove(level, active);

        if (id == null) id = logicalId(blockEntity);
        if (id != null && !id.isBlank()) {
            REMOVED.computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet()).add(id);
        }
    }

    public static Collection<BlockEntity> loaded(ServerLevel level) {
        Map<BlockEntity, String> active = ACTIVE.get(level);
        if (active == null || active.isEmpty()) return List.of();

        // Missing stale entries can be ordinary unloads. Only SpringBlockEntity.remove()
        // supplies positive destruction evidence via forget().
        active.keySet().removeIf(blockEntity ->
                blockEntity == null
                        || blockEntity.isRemoved()
                        || blockEntity.getLevel() != level);
        if (active.isEmpty()) {
            ACTIVE.remove(level, active);
            return List.of();
        }
        return List.copyOf(active.keySet());
    }

    public static Set<String> drainRemoved(ServerLevel level) {
        Set<String> removed = REMOVED.remove(level);
        return removed == null || removed.isEmpty() ? Set.of() : Set.copyOf(removed);
    }

    public static void clear() {
        ACTIVE.clear();
        REMOVED.clear();
    }

    private static String logicalId(BlockEntity blockEntity) {
        SubLevel subLevel = Sable.HELPER.getContaining(blockEntity);
        UUID subLevelId = subLevel == null ? null : subLevel.getUniqueId();
        BlockPos pos = blockEntity.getBlockPos();
        return (subLevelId == null ? "world" : subLevelId.toString())
                + "/" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }
}
