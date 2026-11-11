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

/**
 * Tracks only loaded Coasters Simulated anchors. A chunk unload is not destruction:
 * explicit peer removal or detachAllConnections supplies deletion evidence instead.
 */
public final class SimulatedCoasterTrackRegistry {
    private static final Map<ServerLevel, Set<BlockEntity>> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<ServerLevel, Set<String>> DELETED = new ConcurrentHashMap<>();

    private SimulatedCoasterTrackRegistry() {}

    public static void observe(BlockEntity anchor) {
        if (anchor == null || anchor.isRemoved()
                || !(anchor.getLevel() instanceof ServerLevel level)) return;
        ACTIVE.computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet()).add(anchor);
    }

    public static void forget(BlockEntity anchor) {
        if (anchor == null || !(anchor.getLevel() instanceof ServerLevel level)) return;
        Set<BlockEntity> active = ACTIVE.get(level);
        if (active != null) {
            active.remove(anchor);
            if (active.isEmpty()) ACTIVE.remove(level, active);
        }
        // Do NOT publish deletion evidence here: BlockEntity.remove also occurs on unload.
    }

    public static void removedPeer(BlockEntity anchor, BlockPos peer) {
        if (anchor == null || peer == null
                || !(anchor.getLevel() instanceof ServerLevel level)) return;
        DELETED.computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet())
                .add(edgeId(anchor, peer));
    }

    public static Collection<BlockEntity> loaded(ServerLevel level) {
        Set<BlockEntity> active = ACTIVE.get(level);
        if (active == null || active.isEmpty()) return List.of();
        active.removeIf(anchor -> anchor == null || anchor.isRemoved()
                || anchor.getLevel() != level);
        if (active.isEmpty()) {
            ACTIVE.remove(level, active);
            return List.of();
        }
        return List.copyOf(active);
    }

    public static Set<String> drainDeleted(ServerLevel level) {
        Set<String> ids = DELETED.remove(level);
        return ids == null || ids.isEmpty() ? Set.of() : Set.copyOf(ids);
    }

    /** Stable, order-independent edge identity shared by both endpoints. */
    public static String edgeId(BlockEntity anchor, BlockPos peer) {
        SubLevel sub = Sable.HELPER.getContaining(anchor);
        UUID subId = sub == null ? null : sub.getUniqueId();
        BlockPos a = anchor.getBlockPos();
        BlockPos b = peer;
        String left = coords(a.asLong() <= b.asLong() ? a : b);
        String right = coords(a.asLong() <= b.asLong() ? b : a);
        return (subId == null ? "world" : subId.toString()) + "/" + left + "--" + right;
    }

    private static String coords(BlockPos pos) {
        return pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
    }

    public static void clear() {
        ACTIVE.clear();
        DELETED.clear();
    }
}
