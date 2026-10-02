package dev.duzo.bluemap3d.sable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dirty-bit bridge between Sable's authoritative server block-change path and BlueMap3D.
 *
 * <p>ShipProvider consumes the bit and recomputes a deterministic exact structure hash
 * only after a real plot block change. Normal movement therefore remains O(1).
 */
public final class ShipGeometryRevisionTracker {

    private static final Set<UUID> DIRTY = ConcurrentHashMap.newKeySet();

    private ShipGeometryRevisionTracker() {}

    public static void markDirty(UUID shipId) {
        if (shipId != null) DIRTY.add(shipId);
    }

    public static boolean consumeDirty(UUID shipId) {
        return shipId != null && DIRTY.remove(shipId);
    }

    public static void clear(UUID shipId) {
        if (shipId != null) DIRTY.remove(shipId);
    }

    public static void clearAll() {
        DIRTY.clear();
    }
}
