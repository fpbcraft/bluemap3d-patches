package dev.duzo.bluemap3d.create;

import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import dev.duzo.bluemap3d.api.BlockVolume;
import dev.duzo.bluemap3d.api.ModelAttachment;
import dev.duzo.bluemap3d.api.SceneObject;
import dev.duzo.bluemap3d.api.SceneObjectProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live Create mechanical-belt surface animation.
 *
 * <p>The ordinary BlueMap terrain renderer remains the static fallback. This provider
 * publishes only moving belts, using Create's own partial-model selection, transform,
 * dye texture and signed UV-scroll formula. When RPM reaches zero the overlay disappears
 * on the next publish and the normal static belt is left underneath.
 */
public final class BeltProvider implements SceneObjectProvider {

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final int GEOMETRY_REVISION = 1;
    private static final int SLICE_COUNT = 40;
    private static final int MAX_CACHE_ENTRIES = 8192;

    // AnimationTickHolder render time is in ticks. BeltVisual/BeltRenderer use:
    //   scroll = speed * renderTicks / (31.5 * 16)
    // so convert to cycles per wall-clock second with 20 ticks/s.
    private static final float CYCLES_PER_SECOND_PER_RPM = 20f / (31.5f * 16f);
    private static final float OVERLAY_SCALE = 1.002f;

    private final ChunkTracker chunks;
    private final Map<ServerLevel, AtomicInteger> sliceIndex = new ConcurrentHashMap<>();
    private final Map<ServerLevel, Map<BlockPos, BeltPose>> cache = new ConcurrentHashMap<>();

    public BeltProvider(ChunkTracker chunks) {
        this.chunks = chunks;
    }

    @Override
    public String id() {
        return "create_belts";
    }

    @Override
    public Collection<? extends SceneObject> objects(ServerLevel level) {
        Map<BlockPos, BeltPose> levelCache = cache.computeIfAbsent(
                level,
                ignored -> new LinkedHashMap<>(64, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<BlockPos, BeltPose> eldest) {
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

        for (Map.Entry<BlockPos, BeltPose> entry : new ArrayList<>(levelCache.entrySet())) {
            BeltSnapshot snapshot = entry.getValue().snapshot();
            if (snapshot.speedTenths == 0) {
                continue;
            }
            out.add(toSceneObject(dimension, dimId, entry.getKey(), snapshot));
        }

        return out;
    }

    public void clear() {
        sliceIndex.clear();
        cache.clear();
    }

    private void scanSlice(ServerLevel level, Map<BlockPos, BeltPose> levelCache) {
        List<ChunkPos> loaded = new ArrayList<>(chunks.loadedChunks(level));
        if (loaded.isEmpty()) return;

        int slice = sliceIndex.computeIfAbsent(level, ignored -> new AtomicInteger())
                .getAndUpdate(value -> (value + 1) % SLICE_COUNT);

        for (int i = slice; i < loaded.size(); i += SLICE_COUNT) {
            ChunkPos chunkPos = loaded.get(i);
            LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z);
            if (chunk == null) continue;

            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
                if (!(entry.getValue() instanceof BeltBlockEntity belt)) continue;

                BeltPose pose = levelCache.computeIfAbsent(
                        entry.getKey().immutable(), ignored -> new BeltPose());
                recordPose(belt, pose);
            }
        }
    }

    private void refreshKnown(ServerLevel level, Map<BlockPos, BeltPose> levelCache) {
        for (BlockPos pos : new ArrayList<>(levelCache.keySet())) {
            LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
            BeltPose pose = levelCache.get(pos);

            if (chunk == null) {
                pose.speedTenths = 0;
                continue;
            }

            BlockEntity blockEntity = chunk.getBlockEntity(pos);
            if (!(blockEntity instanceof BeltBlockEntity belt)) {
                levelCache.remove(pos);
                continue;
            }

            recordPose(belt, pose);
        }
    }

    private static void recordPose(BeltBlockEntity belt, BeltPose pose) {
        pose.state = belt.getBlockState();
        pose.color = belt.color.orElse(null);
        pose.speedTenths = Math.round(belt.getSpeed() * 10f);
    }

    private SceneObject toSceneObject(
            ResourceKey<Level> dimension,
            ResourceLocation dimId,
            BlockPos pos,
            BeltSnapshot snapshot) {
        BlockState state = snapshot.state;
        BeltSlope slope = state.getValue(BeltBlock.SLOPE);
        BeltPart part = state.getValue(BeltBlock.PART);
        Direction facing = state.getValue(BeltBlock.HORIZONTAL_FACING);

        boolean diagonal = slope.isDiagonal();
        boolean start = part == BeltPart.START;
        boolean end = part == BeltPart.END;

        float signedSpeed = visualSpeed(snapshot.speedTenths / 10f, slope, facing);
        float cyclesPerSecond = signedSpeed * CYCLES_PER_SECOND_PER_RPM;

        List<ModelAttachment> attachments = new ArrayList<>(diagonal ? 1 : 2);
        for (boolean bottom : new boolean[] {true, false}) {
            ResourceLocation model = partial(diagonal, start, end, bottom);
            String texture = scrollTexture(snapshot.color, diagonal);

            Matrix4f transform = transform(slope, facing);
            ModelAttachment.UvScroll motion = new ModelAttachment.UvScroll(
                    new Vector2f(0f, 1f),
                    cyclesPerSecond,
                    bottom ? 0.5f : 0f);

            attachments.add(new ModelAttachment(
                    BlockPos.ZERO,
                    model,
                    Map.of("1", texture),
                    transform,
                    motion));

            if (diagonal) {
                break;
            }
        }

        BlockVolume volume = BlockVolume.attachments(
                BlockPos.ZERO,
                BlockPos.ZERO,
                Vec3.ZERO,
                attachments);

        long geometryVersion = geometryVersion(snapshot);
        String objectId = dimId.getNamespace() + "_" + dimId.getPath().replace('/', '_')
                + "_" + pos.getX() + "_" + pos.getY() + "_" + pos.getZ();
        Vec3 worldPosition = Vec3.atLowerCornerOf(pos);

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
                return "Mechanical Belt";
            }

            @Override
            public ResourceKey<Level> dimension() {
                return dimension;
            }
        };
    }

    /**
     * Exact sign rules from Create's BeltVisual.setup().
     */
    private static float visualSpeed(float speed, BeltSlope slope, Direction facing) {
        boolean diagonal = slope.isDiagonal();
        boolean sideways = slope == BeltSlope.SIDEWAYS;
        boolean vertical = slope == BeltSlope.VERTICAL;
        boolean upward = slope == BeltSlope.UPWARD;
        boolean alongX = facing.getAxis() == Direction.Axis.X;
        boolean alongZ = facing.getAxis() == Direction.Axis.Z;

        if (((facing.getAxisDirection() == Direction.AxisDirection.NEGATIVE) ^ upward)
                ^ ((alongX && !diagonal) || (alongZ && diagonal))) {
            speed = -speed;
        }
        if ((sideways && (facing == Direction.SOUTH || facing == Direction.WEST))
                || (vertical && facing == Direction.EAST)) {
            speed = -speed;
        }
        return speed;
    }

    /**
     * Exact local transform from Create's BeltVisual.setup(), with a tiny expansion so
     * the animated overlay sits just above the already-baked BlueMap belt instead of
     * depth-fighting it.
     */
    private static Matrix4f transform(BeltSlope slope, Direction facing) {
        boolean diagonal = slope.isDiagonal();
        boolean sideways = slope == BeltSlope.SIDEWAYS;
        boolean vertical = slope == BeltSlope.VERTICAL;
        boolean downward = slope == BeltSlope.DOWNWARD;
        boolean alongX = facing.getAxis() == Direction.Axis.X;
        boolean alongZ = facing.getAxis() == Direction.Axis.Z;

        float rotX = (!diagonal && slope != BeltSlope.HORIZONTAL ? 90f : 0f)
                + (downward ? 180f : 0f)
                + (sideways ? 90f : 0f)
                + (vertical && alongZ ? 180f : 0f);
        float rotY = facing.toYRot()
                + ((diagonal ^ alongX) && !downward ? 180f : 0f)
                + (sideways && alongZ ? 180f : 0f)
                + (vertical && alongX ? 90f : 0f);
        float rotZ = (sideways ? 90f : 0f)
                + (vertical && alongX ? 90f : 0f);

        Quaternionf rotation = new Quaternionf().rotationXYZ(
                (float) Math.toRadians(rotX),
                (float) Math.toRadians(rotY),
                (float) Math.toRadians(rotZ));

        return new Matrix4f()
                .translation(0.5f, 0.5f, 0.5f)
                .rotate(rotation)
                .scale(OVERLAY_SCALE)
                .translate(-0.5f, -0.5f, -0.5f);
    }

    private static ResourceLocation partial(
            boolean diagonal,
            boolean start,
            boolean end,
            boolean bottom) {
        String path;
        if (diagonal) {
            path = start ? "belt/diagonal_start"
                    : end ? "belt/diagonal_end"
                    : "belt/diagonal_middle";
        } else if (bottom) {
            path = start ? "belt/start_bottom"
                    : end ? "belt/end_bottom"
                    : "belt/middle_bottom";
        } else {
            path = start ? "belt/start"
                    : end ? "belt/end"
                    : "belt/middle";
        }
        return ResourceLocation.fromNamespaceAndPath("create", "block/" + path);
    }

    private static String scrollTexture(DyeColor color, boolean diagonal) {
        if (color == null) {
            return diagonal
                    ? "create:block/belt_diagonal_scroll"
                    : "create:block/belt_scroll";
        }

        String name = color.getSerializedName();
        return diagonal
                ? "create:block/belt/" + name + "_diagonal_scroll"
                : "create:block/belt/" + name + "_scroll";
    }

    private static long geometryVersion(BeltSnapshot snapshot) {
        long hash = mix(FNV_OFFSET, GEOMETRY_REVISION);
        hash = mix(hash, snapshot.state.toString().hashCode());
        hash = mix(hash, snapshot.speedTenths);
        hash = mix(hash, snapshot.color == null ? -1 : snapshot.color.ordinal());
        return hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }

    private static final class BeltPose {
        private BlockState state;
        private DyeColor color;
        private int speedTenths;

        BeltSnapshot snapshot() {
            return new BeltSnapshot(state, color, speedTenths);
        }
    }

    private record BeltSnapshot(BlockState state, DyeColor color, int speedTenths) {
    }
}
