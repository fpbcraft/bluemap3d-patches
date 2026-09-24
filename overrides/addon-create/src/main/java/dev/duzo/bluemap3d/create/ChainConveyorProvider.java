package dev.duzo.bluemap3d.create;

import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity;
import com.simibubi.create.content.kinetics.chainConveyor.ChainConveyorBlockEntity.ConnectionStats;
import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import dev.duzo.bluemap3d.compat.CompatRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Adds a live animated overlay for Create chain-conveyor connections.
 *
 * <p>BlueMapCreateEntityAddon already renders the long connections into static BlueMap
 * terrain. That is a good fallback silhouette but it can never know the server's live
 * kinetic speed. This provider reads the real {@link ChainConveyorBlockEntity#getSpeed()}
 * and draws the same repeated chain geometry in BlueMap3D on top of it.
 *
 * <p>Create advances chain travel by {@code abs(speed) / 360} blocks per tick, therefore
 * the browser loop rate is {@code abs(speed) / 18} blocks per second. A zero-speed
 * conveyor carries no motion node at all. Speed is bucketed to one tenth of an RPM so a
 * genuine network speed change re-bakes once, while normal float noise does not churn
 * meshes.
 */
public final class ChainConveyorProvider implements SceneObjectProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/CreateChain");

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final int GEOMETRY_REVISION = 1;
    private static final int SLICE_COUNT = 40;
    private static final int MAX_CACHE_ENTRIES = 4096;

    private static final ResourceLocation CHAIN_MODEL_PREFIX =
            ResourceLocation.fromNamespaceAndPath("bluemap3d", "chain_conveyor/1");

    private final ChunkTracker chunks;
    private final Map<ServerLevel, AtomicInteger> sliceIndex = new ConcurrentHashMap<>();
    private final Map<ServerLevel, Map<BlockPos, ConveyorPose>> cache = new ConcurrentHashMap<>();

    public ChainConveyorProvider(ChunkTracker chunks) {
        this.chunks = chunks;
    }

    @Override
    public String id() {
        return "create_chain_conveyors";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        if (!CompatRegistry.get().featureEnabled("create.chainConveyorAnimation", false)) {
            return List.of();
        }

        Map<BlockPos, ConveyorPose> levelCache = cache.computeIfAbsent(
                level,
                ignored -> new LinkedHashMap<>(32, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<BlockPos, ConveyorPose> eldest) {
                        return size() > MAX_CACHE_ENTRIES;
                    }
                });

        scanSlice(level, levelCache);
        refreshKnown(level, levelCache);

        if (levelCache.isEmpty()) {
            return List.of();
        }

        ResourceKey<Level> dimension = level.dimension();
        ResourceLocation dimId = dimension.location();
        List<SceneObject> out = new ArrayList<>();

        for (Map.Entry<BlockPos, ConveyorPose> entry : new ArrayList<>(levelCache.entrySet())) {
            ConveyorPose pose = entry.getValue();
            if (pose.connections.isEmpty()) {
                continue;
            }
            out.add(toSceneObject(dimension, dimId, entry.getKey(), pose.snapshot()));
        }

        return out;
    }

    public void clear() {
        sliceIndex.clear();
        cache.clear();
    }

    private void scanSlice(ServerLevel level, Map<BlockPos, ConveyorPose> levelCache) {
        List<ChunkPos> loaded = new ArrayList<>(chunks.loadedChunks(level));
        if (loaded.isEmpty()) return;

        int slice = sliceIndex.computeIfAbsent(level, ignored -> new AtomicInteger())
                .getAndUpdate(value -> (value + 1) % SLICE_COUNT);

        for (int i = slice; i < loaded.size(); i += SLICE_COUNT) {
            ChunkPos chunkPos = loaded.get(i);
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) continue;

            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                if (!(entry.getValue() instanceof ChainConveyorBlockEntity conveyor)) continue;

                ConveyorPose pose = levelCache.computeIfAbsent(
                        entry.getKey().immutable(), ignored -> new ConveyorPose());
                recordPose(entry.getKey(), conveyor, pose);
            }
        }
    }

    private void refreshKnown(ServerLevel level, Map<BlockPos, ConveyorPose> levelCache) {
        for (BlockPos pos : new ArrayList<>(levelCache.keySet())) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            ConveyorPose pose = levelCache.get(pos);

            if (chunk == null) {
                // The server is no longer ticking this kinetic network. Keep the known
                // geometry visible but stop its browser-side loop.
                pose.speedTenths = 0;
                continue;
            }

            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (!(blockEntity instanceof ChainConveyorBlockEntity conveyor)) {
                levelCache.remove(pos);
                continue;
            }

            recordPose(pos, conveyor, pose);
        }
    }

    private static void recordPose(
            BlockPos sourcePos,
            ChainConveyorBlockEntity conveyor,
            ConveyorPose pose) {
        conveyor.prepareStats();

        List<ConnectionPose> connections = new ArrayList<>();
        for (BlockPos relative : conveyor.connections) {
            BlockPos target = sourcePos.offset(relative);
            if (compare(sourcePos, target) >= 0) {
                // Both endpoints know the same connection. Let only the lexicographically
                // first endpoint publish it so the animated overlay is never doubled.
                continue;
            }

            ConnectionStats stats = conveyor.connectionStats.get(relative);
            if (stats == null) continue;

            Vec3 origin = Vec3.atLowerCornerOf(sourcePos);
            connections.add(new ConnectionPose(
                    stats.start().subtract(origin),
                    stats.end().subtract(origin)));
        }

        connections.sort(Comparator
                .comparingDouble((ConnectionPose connection) -> connection.end.x)
                .thenComparingDouble(connection -> connection.end.y)
                .thenComparingDouble(connection -> connection.end.z));

        pose.connections = List.copyOf(connections);
        pose.speedTenths = Math.round(Math.abs(conveyor.getSpeed()) * 10f);
    }

    private SceneObject toSceneObject(
            ResourceKey<Level> dimension,
            ResourceLocation dimId,
            BlockPos sourcePos,
            ConveyorSnapshot snapshot) {
        List<ModelAttachment> attachments = new ArrayList<>(snapshot.connections.size() * 2);

        int minX = 0, minY = 0, minZ = 0;
        int maxX = 0, maxY = 0, maxZ = 0;

        float speed = snapshot.speedTenths / 10f;
        float blocksPerSecond = speed / 18f;

        for (ConnectionPose connection : snapshot.connections) {
            Vec3 diff = connection.end.subtract(connection.start);
            double length = diff.length();
            if (!(length > 1.0e-6)) continue;

            Vec3 direction = diff.scale(1.0 / length);
            // Half a block of lead-in plus one extra whole repeated link beyond the far
            // endpoint keeps the one-block loop from opening a visible gap at either port.
            Vec3 modelStart = connection.start.subtract(direction.scale(0.5));
            int segments = Math.max(1, (int) Math.ceil(length) + 2);

            Vector3f axis = new Vector3f(
                    (float) direction.x,
                    (float) direction.y,
                    (float) direction.z);
            Quaternionf rotation = new Quaternionf().rotationTo(
                    new Vector3f(0f, 1f, 0f),
                    axis);

            ModelAttachment.Motion motion = blocksPerSecond > 0
                    ? new ModelAttachment.Loop(
                            new Vector3f(0f, 1f, 0f),
                            blocksPerSecond,
                            16f)
                    : null;

            ResourceLocation model = ResourceLocation.fromNamespaceAndPath(
                    CHAIN_MODEL_PREFIX.getNamespace(),
                    "chain_conveyor/" + segments);

            // BlueMapCreateEntityAddon renders the connection from both endpoint block
            // entities. Its "left" offset therefore becomes two visible parallel chain
            // runs: one from each end, mirrored horizontally. We intentionally de-duplicate
            // the endpoints above, so reproduce both runs explicitly here instead of only
            // animating one side over the static fallback.
            Vec3 side = parallelOffset(diff);
            for (int sign : new int[] {-1, 1}) {
                Vec3 offset = new Vec3(side.x * sign, -0.125, side.z * sign);
                Vec3 sideStart = modelStart.add(offset);

                Matrix4f transform = new Matrix4f()
                        .translation(
                                (float) sideStart.x,
                                (float) sideStart.y,
                                (float) sideStart.z)
                        .rotate(rotation)
                        // The vanilla chain model's vertical centre line is x=z=0.5.
                        .translate(-0.5f, 0f, -0.5f);

                attachments.add(new ModelAttachment(
                        BlockPos.ZERO,
                        model,
                        Map.of(),
                        transform,
                        motion));
            }

            minX = Math.min(minX, (int) Math.floor(Math.min(connection.start.x, connection.end.x) - 2));
            minY = Math.min(minY, (int) Math.floor(Math.min(connection.start.y, connection.end.y) - 2));
            minZ = Math.min(minZ, (int) Math.floor(Math.min(connection.start.z, connection.end.z) - 2));
            maxX = Math.max(maxX, (int) Math.ceil(Math.max(connection.start.x, connection.end.x) + 2));
            maxY = Math.max(maxY, (int) Math.ceil(Math.max(connection.start.y, connection.end.y) + 2));
            maxZ = Math.max(maxZ, (int) Math.ceil(Math.max(connection.start.z, connection.end.z) + 2));
        }

        BlockVolume volume = attachments.isEmpty()
                ? BlockVolume.EMPTY
                : BlockVolume.attachments(
                        new BlockPos(minX, minY, minZ),
                        new BlockPos(maxX, maxY, maxZ),
                        Vec3.ZERO,
                        attachments);

        long geometryVersion = geometryVersion(snapshot);
        String objectId = dimId.getNamespace() + "_" + dimId.getPath().replace('/', '_')
                + "_" + sourcePos.getX() + "_" + sourcePos.getY() + "_" + sourcePos.getZ();
        Vec3 worldPosition = Vec3.atLowerCornerOf(sourcePos);

        return new SceneObject() {
            @Override
            public String id() {
                return objectId;
            }

            @Override
            public BlockVolume geometry() {
                return volume;
            }

            @Override
            public long geometryVersion() {
                return geometryVersion;
            }

            @Override
            public Vec3 position() {
                return worldPosition;
            }

            @Override
            public Quaternionf rotation() {
                return new Quaternionf();
            }

            @Override
            public String label() {
                return "Chain Conveyor";
            }

            @Override
            public ResourceKey<Level> dimension() {
                return dimension;
            }
        };
    }

    private static long geometryVersion(ConveyorSnapshot snapshot) {
        long hash = mix(FNV_OFFSET, GEOMETRY_REVISION);
        hash = mix(hash, snapshot.speedTenths);
        for (ConnectionPose connection : snapshot.connections) {
            hash = mixVec(hash, connection.start);
            hash = mixVec(hash, connection.end);
        }
        return hash;
    }

    private static long mixVec(long hash, Vec3 value) {
        hash = mix(hash, Math.round(value.x * 4096.0));
        hash = mix(hash, Math.round(value.y * 4096.0));
        return mix(hash, Math.round(value.z * 4096.0));
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static Vec3 parallelOffset(Vec3 direction) {
        double horizontal = Math.sqrt(direction.x * direction.x + direction.z * direction.z);
        if (horizontal < 1.0e-9) {
            return Vec3.ZERO;
        }

        // Match BlueMapCreateEntityAddon's computeLeftOffset(): 0.7 blocks sideways.
        return new Vec3(
                -direction.z / horizontal * 0.7,
                0,
                direction.x / horizontal * 0.7);
    }

    private static int compare(BlockPos a, BlockPos b) {
        int x = Integer.compare(a.getX(), b.getX());
        if (x != 0) return x;
        int y = Integer.compare(a.getY(), b.getY());
        return y != 0 ? y : Integer.compare(a.getZ(), b.getZ());
    }

    private static final class ConveyorPose {
        private List<ConnectionPose> connections = List.of();
        private int speedTenths;

        ConveyorSnapshot snapshot() {
            return new ConveyorSnapshot(connections, speedTenths);
        }
    }

    private record ConveyorSnapshot(List<ConnectionPose> connections, int speedTenths) {
    }

    private record ConnectionPose(Vec3 start, Vec3 end) {
    }
}
