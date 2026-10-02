package dev.duzo.bluemap3d.sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exact-but-lazy structural fingerprints for Sable ships.
 *
 * <p>Sable's authoritative block-change path only marks a ship dirty. The expensive exact
 * walk happens once when BlueMap next asks for that ship's geometry version, then the hash
 * is reused for every normal publish while the ship merely moves.
 */
public final class ShipGeometryRevisionTracker {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private static final Set<UUID> DIRTY = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> STRUCTURE_HASHES = new ConcurrentHashMap<>();

    private ShipGeometryRevisionTracker() {}

    public static void markDirty(UUID shipId) {
        if (shipId != null) DIRTY.add(shipId);
    }

    public static long structureHash(ServerSubLevel ship) {
        UUID shipId = ship == null ? null : ship.getUniqueId();
        if (shipId == null) return 0L;

        Long cached = STRUCTURE_HASHES.get(shipId);
        if (cached != null && !DIRTY.remove(shipId)) {
            return cached;
        }

        long exact = exactStructureHash(ship);
        STRUCTURE_HASHES.put(shipId, exact);
        DIRTY.remove(shipId);
        return exact;
    }

    public static void clear(UUID shipId) {
        if (shipId == null) return;
        DIRTY.remove(shipId);
        STRUCTURE_HASHES.remove(shipId);
    }

    public static void clearAll() {
        DIRTY.clear();
        STRUCTURE_HASHES.clear();
    }

    /**
     * Deterministic hash of every non-air block's plot-space position and full BlockState.
     *
     * <p>This is intentionally comparable in cost to the geometry snapshot itself and is
     * never run on ordinary movement publishes. Sorting chunk holders makes it stable
     * across chunk load order and server restarts.
     */
    private static long exactStructureHash(ServerSubLevel ship) {
        var plot = ship.getPlot();
        if (plot == null) return 0L;

        ArrayList<PlotChunkHolder> holders = new ArrayList<>(plot.getLoadedChunks());
        holders.sort(Comparator.comparingLong(holder -> holder.getPos().toLong()));

        long hash = FNV_OFFSET;
        long blockCount = 0L;

        for (PlotChunkHolder holder : holders) {
            LevelChunk chunk = holder.getChunk();
            if (chunk == null) continue;

            int chunkBaseX = holder.getPos().x << 4;
            int chunkBaseZ = holder.getPos().z << 4;
            LevelChunkSection[] sections = chunk.getSections();

            for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
                LevelChunkSection section = sections[sectionIndex];
                if (section == null || section.hasOnlyAir()) continue;

                int sectionBaseY = chunk.getSectionYFromSectionIndex(sectionIndex) << 4;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState state = section.getBlockState(x, y, z);
                            if (state.isAir()) continue;

                            long packedPos = BlockPos.asLong(
                                    chunkBaseX + x,
                                    sectionBaseY + y,
                                    chunkBaseZ + z);
                            hash = mix(hash, packedPos);
                            hash = mix(hash, Block.getId(state));
                            blockCount++;
                        }
                    }
                }
            }
        }

        return mix(hash, blockCount);
    }

    private static long mix(long hash, long value) {
        return (hash ^ value) * FNV_PRIME;
    }
}
