package dev.duzo.bluemapdynamictrees;

import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModel;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import de.bluecolored.bluemap.core.world.block.ExtendedBlock;

import java.util.EnumSet;
import java.util.Map;

/**
 * Reconstructs Dynamic Trees' branch/surface-root geometry directly from BlueMap's
 * block snapshot.
 *
 * <p>The real client model is dynamic for a good reason: the center cube uses this
 * block's radius, while up to six sleeves use the neighboring connection radii. BlueMap
 * has that same neighborhood available during terrain rendering, so reproducing the
 * geometry here is both more accurate and cheaper than trying to boot Minecraft's client
 * model pipeline on a server.
 */
public final class DynamicTreesTerrainRenderer implements BlockRenderer {

    private final ResourcePack resourcePack;
    private final TextureGallery textureGallery;

    private BlockNeighborhood block;
    private TileModelView tileModel;
    private int emitted;

    public DynamicTreesTerrainRenderer(
            ResourcePack resourcePack,
            TextureGallery textureGallery,
            RenderSettings ignoredSettings) {
        this.resourcePack = resourcePack;
        this.textureGallery = textureGallery;
    }

    @Override
    public void render(
            BlockNeighborhood block,
            Variant ignoredVariant,
            TileModelView tileModel,
            Color blockColor) {
        this.block = block;
        this.tileModel = tileModel;
        this.emitted = 0;

        String id = block.getBlockState().getFormatted();
        DynamicTreesTerrainDispatch.Info info = DynamicTreesTerrainDispatch.info(id);
        if (info == null) return;

        int start = tileModel.getStart();
        if (info.kind() == DynamicTreesTerrainDispatch.Kind.BRANCH) {
            renderBranch(info);
        } else {
            renderSurfaceRoot(info);
        }
        tileModel.initialize(start);

        if (emitted > 0) {
            Texture texture = info.bark().getResource(resourcePack::getTexture);
            if (texture != null) {
                blockColor.set(texture.getColorPremultiplied());
                if (blockColor.a > 0f) blockColor.straight();
            } else {
                blockColor.set(1f, 1f, 1f, 1f, true);
            }
        }
    }

    private void renderBranch(DynamicTreesTerrainDispatch.Info info) {
        int radius = clamp(radius(block.getBlockState()), 1, 24);
        int[] connections = new int[Direction.values().length];

        int numConnections = 0;
        int largest = 0;
        Direction source = null;
        boolean horizontal = false;

        for (Direction direction : Direction.values()) {
            int connection = branchConnection(direction, info, radius);
            connections[direction.ordinal()] = connection;
            if (connection > 0) {
                numConnections++;
                if (direction.getAxis() != de.bluecolored.bluemap.core.util.math.Axis.Y) {
                    horizontal = true;
                }
                if (connection > largest) {
                    largest = connection;
                    source = direction;
                }
            }
        }

        if (radius > 8) {
            renderThickTrunk(info, radius, connections, horizontal);
            return;
        }

        if (largest < radius) source = null;
        Direction ringFace =
                numConnections == 1 && source != null ? source.opposite() : null;

        float min = 8f - radius;
        float max = 8f + radius;

        // Dynamic Trees omits the core face when a same-sized sleeve continues through it.
        for (Direction face : Direction.values()) {
            if (connections[face.ordinal()] == radius) continue;
            ResourcePath<Texture> texture =
                    face == ringFace && info.rings() != null ? info.rings() : info.bark();
            emitCuboidFace(face, min, min, min, max, max, max, texture);
        }

        if (radius == 8) return;

        for (Direction direction : Direction.values()) {
            int connection = connections[direction.ordinal()];
            if (connection <= 0) continue;
            renderSleeve(direction, connection, info.bark());
        }
    }

    /**
     * Radius 9..24 is Dynamic Trees' 3x3 thick-trunk form. Its visual envelope is a
     * vertical prism centered on the owning branch block and extending into the eight
     * trunk-shell positions around it. Drawing it from the center block avoids requiring
     * the shell blocks themselves to have geometry.
     */
    private void renderThickTrunk(
            DynamicTreesTerrainDispatch.Info info,
            int radius,
            int[] connections,
            boolean hasHorizontalBranches) {
        float min = 8f - radius;
        float max = 8f + radius;

        emitTiledSide(Direction.NORTH, min, 0f, min, max, 16f, min, info.bark());
        emitTiledSide(Direction.SOUTH, min, 0f, max, max, 16f, max, info.bark());
        emitTiledSide(Direction.WEST, min, 0f, min, min, 16f, max, info.bark());
        emitTiledSide(Direction.EAST, max, 0f, min, max, 16f, max, info.bark());

        int up = connections[Direction.UP.ordinal()];
        int down = connections[Direction.DOWN.ordinal()];

        if (up < radius) {
            ResourcePath<Texture> top =
                    up == 0 && !hasHorizontalBranches && info.rings() != null
                            ? info.rings()
                            : info.bark();
            emitCuboidFace(Direction.UP, min, 0f, min, max, 16f, max, top);
        }
        if (down < radius) {
            ResourcePath<Texture> bottom =
                    down == 0 && !hasHorizontalBranches && info.rings() != null
                            ? info.rings()
                            : info.bark();
            emitCuboidFace(Direction.DOWN, min, 0f, min, max, 16f, max, bottom);
        }
    }

    private void renderSleeve(
            Direction direction,
            int radius,
            ResourcePath<Texture> texture) {
        float lo = 8f - radius;
        float hi = 8f + radius;

        float minX = lo, minY = lo, minZ = lo;
        float maxX = hi, maxY = hi, maxZ = hi;

        switch (direction) {
            case EAST -> {
                minX = hi;
                maxX = 16f;
            }
            case WEST -> {
                minX = 0f;
                maxX = lo;
            }
            case UP -> {
                minY = hi;
                maxY = 16f;
            }
            case DOWN -> {
                minY = 0f;
                maxY = lo;
            }
            case SOUTH -> {
                minZ = hi;
                maxZ = 16f;
            }
            case NORTH -> {
                minZ = 0f;
                maxZ = lo;
            }
        }

        EnumSet<Direction> faces = EnumSet.allOf(Direction.class);
        faces.remove(direction.opposite()); // inside the core
        if (radius > 1) {
            // A branch-to-branch sleeve remains open at the block boundary; its neighbor
            // supplies the continuation. Radius-one twigs retain the end face, matching DT.
            faces.remove(direction);
        }

        emitCuboid(minX, minY, minZ, maxX, maxY, maxZ, texture, faces);
    }

    private void renderSurfaceRoot(DynamicTreesTerrainDispatch.Info info) {
        int ownRadius = clamp(radius(block.getBlockState()), 1, 8);
        boolean any = false;

        for (Direction direction : new Direction[]{
                Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST}) {
            RootConnection connection = rootConnection(direction, info, ownRadius);
            if (connection == null) continue;
            any = true;

            int radius = connection.radius();
            float lo = 8f - radius;
            float hi = 8f + radius;
            float height = radius * 2f;

            float minX = lo, maxX = hi;
            float minZ = lo, maxZ = hi;
            switch (direction) {
                case EAST -> {
                    minX = hi;
                    maxX = 16f;
                }
                case WEST -> {
                    minX = 0f;
                    maxX = lo;
                }
                case SOUTH -> {
                    minZ = hi;
                    maxZ = 16f;
                }
                case NORTH -> {
                    minZ = 0f;
                    maxZ = lo;
                }
                default -> {
                }
            }

            float yOffset = connection.level() * 16f;
            // Same-level roots match DT's normal sleeve exactly. For a one-block step up
            // or down, extend the sleeve vertically to bridge the two levels; this keeps
            // the connection continuous without introducing a diagonal prism.
            float minY = Math.min(0f, yOffset);
            float maxY = Math.max(height, yOffset + height);
            emitCuboid(
                    minX, minY, minZ,
                    maxX, maxY, maxZ,
                    info.bark(),
                    EnumSet.allOf(Direction.class));
        }

        if (!any) {
            float lo = 8f - ownRadius;
            float hi = 8f + ownRadius;
            emitCuboid(
                    lo, 0f, lo,
                    hi, ownRadius * 2f, hi,
                    info.bark(),
                    EnumSet.allOf(Direction.class));
        }
    }

    private int branchConnection(
            Direction direction,
            DynamicTreesTerrainDispatch.Info own,
            int ownRadius) {
        var v = direction.toVector();
        ExtendedBlock neighborBlock =
                block.getNeighborBlock(v.getX(), v.getY(), v.getZ());
        BlockState neighbor = neighborBlock.getBlockState();
        String neighborId = neighbor.getFormatted();

        if (DynamicTreesTerrainDispatch.sameFamily(
                block.getBlockState().getFormatted(), neighborId)) {
            return Math.min(ownRadius, clamp(radius(neighbor), 1, 24));
        }

        // Dynamic leaves connect to their family's twig. The common/addon naming scheme is
        // <family>_leaves, so preserve the visible terminal sleeve without any mod classes.
        if (ownRadius <= 2
                && leafFamily(neighborId).equals(own.family())) {
            return 1;
        }

        return 0;
    }

    private RootConnection rootConnection(
            Direction direction,
            DynamicTreesTerrainDispatch.Info own,
            int ownRadius) {
        var v = direction.toVector();

        BlockState same =
                block.getNeighborBlock(v.getX(), 0, v.getZ()).getBlockState();
        DynamicTreesTerrainDispatch.Info sameInfo =
                DynamicTreesTerrainDispatch.info(same.getFormatted());

        if (sameInfo != null
                && sameInfo.kind() == DynamicTreesTerrainDispatch.Kind.BRANCH
                && sameInfo.family().equals(own.family())
                && radius(same) >= 8) {
            return new RootConnection(Math.min(8, ownRadius), 0);
        }
        if (sameInfo != null
                && sameInfo.kind() == DynamicTreesTerrainDispatch.Kind.SURFACE_ROOT
                && sameInfo.family().equals(own.family())) {
            return new RootConnection(Math.min(ownRadius, radius(same)), 0);
        }

        BlockState high =
                block.getNeighborBlock(v.getX(), 1, v.getZ()).getBlockState();
        DynamicTreesTerrainDispatch.Info highInfo =
                DynamicTreesTerrainDispatch.info(high.getFormatted());
        if (highInfo != null
                && highInfo.kind() == DynamicTreesTerrainDispatch.Kind.SURFACE_ROOT
                && highInfo.family().equals(own.family())) {
            return new RootConnection(Math.min(ownRadius, radius(high)), 1);
        }

        BlockState low =
                block.getNeighborBlock(v.getX(), -1, v.getZ()).getBlockState();
        DynamicTreesTerrainDispatch.Info lowInfo =
                DynamicTreesTerrainDispatch.info(low.getFormatted());
        if (lowInfo != null
                && lowInfo.kind() == DynamicTreesTerrainDispatch.Kind.SURFACE_ROOT
                && lowInfo.family().equals(own.family())) {
            return new RootConnection(Math.min(ownRadius, radius(low)), -1);
        }

        return null;
    }

    private void emitTiledSide(
            Direction face,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            ResourcePath<Texture> texture) {
        if (face == Direction.NORTH || face == Direction.SOUTH) {
            for (float x = minX; x < maxX; x += 16f) {
                float next = Math.min(maxX, x + 16f);
                emitCuboidFace(face, x, minY, minZ, next, maxY, maxZ, texture);
            }
        } else {
            for (float z = minZ; z < maxZ; z += 16f) {
                float next = Math.min(maxZ, z + 16f);
                emitCuboidFace(face, minX, minY, z, maxX, maxY, next, texture);
            }
        }
    }

    private void emitCuboid(
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            ResourcePath<Texture> texture,
            EnumSet<Direction> faces) {
        for (Direction face : faces) {
            emitCuboidFace(face, minX, minY, minZ, maxX, maxY, maxZ, texture);
        }
    }

    private void emitCuboidFace(
            Direction face,
            float minX, float minY, float minZ,
            float maxX, float maxY, float maxZ,
            ResourcePath<Texture> texture) {
        float[] p = switch (face) {
            case DOWN -> q(
                    minX,minY,minZ, maxX,minY,minZ,
                    maxX,minY,maxZ, minX,minY,maxZ);
            case UP -> q(
                    minX,maxY,maxZ, maxX,maxY,maxZ,
                    maxX,maxY,minZ, minX,maxY,minZ);
            case NORTH -> q(
                    maxX,minY,minZ, minX,minY,minZ,
                    minX,maxY,minZ, maxX,maxY,minZ);
            case SOUTH -> q(
                    minX,minY,maxZ, maxX,minY,maxZ,
                    maxX,maxY,maxZ, minX,maxY,maxZ);
            case WEST -> q(
                    minX,minY,minZ, minX,minY,maxZ,
                    minX,maxY,maxZ, minX,maxY,minZ);
            case EAST -> q(
                    maxX,minY,maxZ, maxX,minY,minZ,
                    maxX,maxY,minZ, maxX,maxY,maxZ);
        };
        emit(face, p, texture);
    }

    private void emit(
            Direction face,
            float[] positions,
            ResourcePath<Texture> texture) {
        tileModel.initialize();
        tileModel.add(2);

        TileModel target = tileModel.getTileModel();
        int f1 = tileModel.getStart();
        int f2 = f1 + 1;

        target.setPositions(f1,
                positions[0] / 16f, positions[1] / 16f, positions[2] / 16f,
                positions[3] / 16f, positions[4] / 16f, positions[5] / 16f,
                positions[6] / 16f, positions[7] / 16f, positions[8] / 16f);
        target.setPositions(f2,
                positions[0] / 16f, positions[1] / 16f, positions[2] / 16f,
                positions[6] / 16f, positions[7] / 16f, positions[8] / 16f,
                positions[9] / 16f, positions[10] / 16f, positions[11] / 16f);

        // Each emitted face gets a local 0..1 texture frame. Thick side faces are split
        // into <=16-pixel strips before reaching this method, so bark keeps its normal
        // density instead of stretching across a 2-3 block trunk.
        target.setUvs(f1, 0f,1f, 1f,1f, 1f,0f);
        target.setUvs(f2, 0f,1f, 1f,0f, 0f,0f);

        int textureId = textureGallery.get(texture == null ? ResourcePack.MISSING_TEXTURE : texture);
        target.setMaterialIndex(f1, textureId);
        target.setMaterialIndex(f2, textureId);

        float shade = shade(face);
        target.setColor(f1, shade, shade, shade);
        target.setColor(f2, shade, shade, shade);

        LightData here = block.getLightData();
        var v = face.toVector();
        LightData there =
                block.getNeighborBlock(v.getX(), v.getY(), v.getZ()).getLightData();
        int sky = Math.max(here.getSkyLight(), there.getSkyLight());
        int light = Math.max(here.getBlockLight(), there.getBlockLight());
        target.setSunlight(f1, sky);
        target.setSunlight(f2, sky);
        target.setBlocklight(f1, light);
        target.setBlocklight(f2, light);
        target.setAOs(f1, 1f, 1f, 1f);
        target.setAOs(f2, 1f, 1f, 1f);

        emitted++;
    }

    private static float shade(Direction face) {
        return switch (face) {
            case UP -> 1.0f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            case WEST, EAST -> 0.6f;
        };
    }

    private static int radius(BlockState state) {
        try {
            return Integer.parseInt(state.getProperties().getOrDefault("radius", "0"));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String leafFamily(String id) {
        int colon = id.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : id.substring(0, colon);
        String path = colon < 0 ? id : id.substring(colon + 1);
        if (!path.endsWith("_leaves")) return "";
        path = path.substring(0, path.length() - "_leaves".length());
        return namespace + ":" + path;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float[] q(
            float ax, float ay, float az,
            float bx, float by, float bz,
            float cx, float cy, float cz,
            float dx, float dy, float dz) {
        return new float[]{
                ax,ay,az,
                bx,by,bz,
                cx,cy,cz,
                dx,dy,dz
        };
    }

    private record RootConnection(int radius, int level) {
    }
}
