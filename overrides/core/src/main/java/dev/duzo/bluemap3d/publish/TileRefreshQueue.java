package dev.duzo.bluemap3d.publish;

import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3i;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import dev.duzo.bluemap3d.api.BlueMap3D;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Coalesces block-change positions into BlueMap tile updates.
 *
 * <p>Objects that edit the world - a turtle mining, a train assembling - leave the terrain
 * tile stale until it is re-rendered. BlueMap will get there on its own eventually, but
 * "eventually" is not much use when you are watching a quarry work.
 *
 * <p>The coalescing is the point. A quarry turtle clears thousands of blocks, almost all of
 * them inside the same handful of tiles, and one {@code scheduleMapUpdateTask} per block
 * would bury BlueMap's render queue. Positions collapse to tile coordinates in a set, and
 * the set is flushed on an interval, so the cost is bounded by the number of tiles actually
 * touched however many blocks changed.
 */
public final class TileRefreshQueue implements BlueMap3D.TileRefresher {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Refresh");

    /** Ticks between flushes. Long enough to batch a burst of mining into one update. */
    private static final int FLUSH_INTERVAL_TICKS = 40;

    private final BlueMapAPI api;
    /**
     * Pending MCA world-regions per map id. BlueMap's scheduleMapUpdateTask(map, regions, ...)
     * API expects region coordinates, not tile coordinates.
     */
    private final Map<String, Set<Vector2i>> pendingRegions = new HashMap<>();
    /** Browser-visible map tiles corresponding to pending region refreshes. Guarded with pendingRegions. */
    private final Map<String, Set<Vector2i>> pendingTiles = new HashMap<>();
    /** Browser tiles whose forced BlueMap render has been scheduled but not yet completed. */
    private final Map<String, Set<Vector2i>> renderingTiles = new HashMap<>();
    /** Latest completed refresh snapshot, repeated across feeds for slow clients. */
    private final Map<String, Set<Vector2i>> undelivered = new HashMap<>();
    private int ticks;
    private volatile int version;
    /** Full browser refresh is required when a world-region render completes. */
    private static final int FULL_MAP_REFRESH_SENTINEL = Integer.MIN_VALUE;

    /**
     * Hard cap on how many tile coordinates go into one feed.
     *
     * <p>A quarry can dirty more tiles than are worth listing, and the feed is republished
     * twice a second. Past this the browser is better off reloading what it has on screen
     * than being handed a list longer than its viewport.
     */
    private static final int MAX_PUBLISHED_TILES = 64;

    /**
     * Returns the latest completed change for every feed poll. A one-shot drain can
     * be lost when the browser misses a poll and later observes the new version with no
     * dirty coordinates. The set is replaced on the next completed refresh.
     */
    public Map<String, List<int[]>> drainUndelivered() {
        synchronized (undelivered) {
            if (undelivered.isEmpty()) {
                return Map.of();
            }
            Map<String, List<int[]>> out = new HashMap<>();
            undelivered.forEach((mapId, tiles) -> {
                List<int[]> coords = new java.util.ArrayList<>(Math.min(tiles.size(), MAX_PUBLISHED_TILES));
                for (Vector2i tile : tiles) {
                    if (coords.size() >= MAX_PUBLISHED_TILES) {
                        break;
                    }
                    coords.add(new int[]{tile.getX(), tile.getY()});
                }
                out.put(mapId, coords);
            });
            return out;
        }
    }

    /**
     * How many times tiles have been queued for re-render.
     *
     * <p>Published in the live feed so the browser can tell that terrain it already
     * downloaded is stale. It has to be told: BlueMap's webapp never revalidates tiles on
     * its own - its one-second update loop only follows the player marker, and tile urls
     * carry a cache hash fixed for the session - so a viewer who does not reload the page
     * keeps looking at the terrain as it was when they opened it.
     */
    public int version() {
        return version;
    }

    public TileRefreshQueue(BlueMapAPI api) {
        this.api = api;
    }

    @Override
    public void refresh(ServerLevel level, BlockPos pos) {
        try {
            var world = api.getWorld(level).orElse(null);
            if (world == null) {
                return;
            }
            Vector3i position = new Vector3i(pos.getX(), pos.getY(), pos.getZ());
            Vector2i region = worldRegionFor(pos);
            synchronized (pendingRegions) {
                for (BlueMapMap map : world.getMaps()) {
                    // RenderManager.scheduleMapUpdateTask(map, Collection<Vector2i>, ...)
                    // takes MCA world-region coordinates. Keep those separate from the
                    // map tile coordinates the browser needs to evict after rendering.
                    pendingRegions.computeIfAbsent(map.getId(), key -> new HashSet<>())
                            .add(region);
                    // A world-region render can rewrite many hires and lowres tiles.
                    // A sampled block's single posToTile result is not an adequate
                    // browser invalidation footprint. Signal a full visible-map refresh
                    // after completion instead of advertising an incomplete tile list.
                    pendingTiles.computeIfAbsent(map.getId(), key -> new HashSet<>())
                            .add(new Vector2i(FULL_MAP_REFRESH_SENTINEL, FULL_MAP_REFRESH_SENTINEL));
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Could not queue a tile refresh at {}: {}", pos, e.toString());
        }
    }

    /** Called every server tick; flushes on its own interval. */
    public void tick() {
        deliverCompletedRenders();

        if (++ticks < FLUSH_INTERVAL_TICKS) {
            return;
        }
        ticks = 0;

        Map<String, Set<Vector2i>> regionBatch;
        Map<String, Set<Vector2i>> tileBatch;
        synchronized (pendingRegions) {
            if (pendingRegions.isEmpty()) {
                return;
            }
            regionBatch = new HashMap<>(pendingRegions);
            tileBatch = new HashMap<>(pendingTiles);
            pendingRegions.clear();
            pendingTiles.clear();
        }

        regionBatch.forEach((mapId, regions) -> {
            Set<Vector2i> tiles = tileBatch.getOrDefault(mapId, Set.of());
            try {
                api.getMap(mapId).ifPresentOrElse(map -> {
                    // An explicit BlueMap3D refresh means the caller knows the terrain is
                    // stale. BlueMap expects MCA region coordinates here and will render
                    // every affected hires tile inside those regions.
                    boolean scheduled = api.getRenderManager()
                            .scheduleMapUpdateTask(map, regions, true);
                    if (scheduled) {
                        synchronized (renderingTiles) {
                            renderingTiles.computeIfAbsent(mapId, key -> new HashSet<>())
                                    .addAll(tiles);
                        }
                    } else {
                        requeue(mapId, regions, tiles);
                    }
                    LOGGER.info("Queued {} world-region(s) / {} browser tile(s) on map '{}' "
                                    + "for forced re-render (accepted={}, renderQueue={})",
                            regions.size(), tiles.size(), mapId, scheduled,
                            api.getRenderManager().renderQueueSize());
                }, () -> {
                    requeue(mapId, regions, tiles);
                    LOGGER.warn("No BlueMap map called '{}'; {} world-region(s) not re-rendered",
                            mapId, regions.size());
                });
            } catch (RuntimeException e) {
                requeue(mapId, regions, tiles);
                LOGGER.warn("Could not schedule a map update for '{}': {}", mapId, e.toString());
            }
        });
    }

    private static Vector2i worldRegionFor(BlockPos pos) {
        // Minecraft/BlueMap MCA regions are 32x32 chunks = 512x512 blocks. floorDiv is
        // intentional: negative block coordinates belong to negative region coordinates.
        return new Vector2i(
                Math.floorDiv(pos.getX(), 512),
                Math.floorDiv(pos.getZ(), 512));
    }

    private void requeue(String mapId, Set<Vector2i> regions, Set<Vector2i> tiles) {
        synchronized (pendingRegions) {
            pendingRegions.computeIfAbsent(mapId, key -> new HashSet<>()).addAll(regions);
            pendingTiles.computeIfAbsent(mapId, key -> new HashSet<>()).addAll(tiles);
        }
    }

    /**
     * Exposes dirty tiles to the browser only after BlueMap has finished its render queue.
     *
     * <p>Publishing them at schedule time races the renderer: the browser can evict and
     * immediately re-fetch the old tile file before the replacement is written, then keep
     * that stale response cached. Waiting for an empty render queue makes the reload a
     * post-render action instead of a best-effort guess.
     */
    private void deliverCompletedRenders() {
        if (api.getRenderManager().renderQueueSize() != 0) return;

        Map<String, Set<Vector2i>> completed;
        synchronized (renderingTiles) {
            if (renderingTiles.isEmpty()) return;
            completed = new HashMap<>(renderingTiles);
            renderingTiles.clear();
        }

        synchronized (undelivered) {
            undelivered.clear();
            completed.forEach((mapId, tiles) ->
                    undelivered.computeIfAbsent(mapId, key -> new HashSet<>()).addAll(tiles));
        }
        version++;
        LOGGER.info("BlueMap tile re-render completed; publishing {} changed map set(s) "
                        + "to browsers (version={})",
                completed.size(), version);
    }
}