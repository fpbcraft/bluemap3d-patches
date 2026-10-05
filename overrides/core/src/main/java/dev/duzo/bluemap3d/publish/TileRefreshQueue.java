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
    /** Pending tiles per map id. Guarded by itself. */
    private final Map<String, Set<Vector2i>> pending = new HashMap<>();
    /** Tiles whose forced BlueMap render has been scheduled but not yet completed. */
    private final Map<String, Set<Vector2i>> rendering = new HashMap<>();
    /** Tiles queued since the browser was last told, per map id. Guarded by itself. */
    private final Map<String, Set<Vector2i>> undelivered = new HashMap<>();
    private int ticks;
    private volatile int version;

    /**
     * Hard cap on how many tile coordinates go into one feed.
     *
     * <p>A quarry can dirty more tiles than are worth listing, and the feed is republished
     * twice a second. Past this the browser is better off reloading what it has on screen
     * than being handed a list longer than its viewport.
     */
    private static final int MAX_PUBLISHED_TILES = 64;

    /**
     * Takes the tiles queued since the last call, so the browser can reload exactly those.
     *
     * <p>Draining rather than snapshotting: each tile needs to reach the browser once, and
     * a viewer who joins later gets fresh tiles anyway because nothing is cached for them
     * yet.
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
            undelivered.clear();
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
            synchronized (pending) {
                for (BlueMapMap map : world.getMaps()) {
                    // posToTile is the map's own conversion, so it accounts for that map's
                    // tile size and offset rather than assuming BlueMap's defaults.
                    pending.computeIfAbsent(map.getId(), key -> new HashSet<>())
                            .add(map.posToTile(position));
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

        Map<String, Set<Vector2i>> batch;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new HashMap<>(pending);
            pending.clear();
        }

        batch.forEach((mapId, tiles) -> {
            try {
                api.getMap(mapId).ifPresentOrElse(map -> {
                    // An explicit BlueMap3D refresh means the caller knows the tile is stale.
                    // Force rendering instead of relying on BlueMap's saved chunk hash.
                    boolean scheduled = api.getRenderManager().scheduleMapUpdateTask(map, tiles, true);
                    if (scheduled) {
                        synchronized (rendering) {
                            rendering.computeIfAbsent(mapId, key -> new HashSet<>()).addAll(tiles);
                        }
                    } else {
                        // Render manager rejected the task (for example while stopped). Keep
                        // the request so the next flush can retry rather than losing it.
                        synchronized (pending) {
                            pending.computeIfAbsent(mapId, key -> new HashSet<>()).addAll(tiles);
                        }
                    }
                    LOGGER.info("Queued {} tile(s) on map '{}' for forced re-render "
                                    + "(accepted={}, renderQueue={})",
                            tiles.size(), mapId, scheduled,
                            api.getRenderManager().renderQueueSize());
                }, () -> LOGGER.warn("No BlueMap map called '{}'; {} tile(s) not re-rendered",
                        mapId, tiles.size()));
            } catch (RuntimeException e) {
                LOGGER.warn("Could not schedule a map update for '{}': {}", mapId, e.toString());
            }
        });
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
        synchronized (rendering) {
            if (rendering.isEmpty()) return;
            completed = new HashMap<>(rendering);
            rendering.clear();
        }

        synchronized (undelivered) {
            completed.forEach((mapId, tiles) ->
                    undelivered.computeIfAbsent(mapId, key -> new HashSet<>()).addAll(tiles));
        }
        version++;
        LOGGER.info("BlueMap tile re-render completed; publishing {} changed map set(s) "
                        + "to browsers (version={})",
                completed.size(), version);
    }
}