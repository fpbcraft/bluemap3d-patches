package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModel;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import de.bluecolored.bluemap.core.logger.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Static BlueMap renderer for Bits and Bobs girder struts.
 *
 * <p>The endpoint attachment is drawn locally and each endpoint renders only its half
 * of every connection, matching Strut Your Stuff's ownership rule and avoiding doubled
 * full-length spans.
 */
public final class BitsNBobsStrutTerrainRenderer implements BlockRenderer {

    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();
    private static final double SURFACE_OFFSET = 6.0 / 16.0;

    private final ResourcePack resourcePack;
    private final TextureGallery textureGallery;

    private BlockNeighborhood block;
    private TileModelView tileModel;

    public BitsNBobsStrutTerrainRenderer(
            ResourcePack resourcePack,
            TextureGallery textureGallery,
            RenderSettings renderSettings) {
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

        String id = block.getBlockState().getFormatted();
        int texture = textureFor(id);
        if (texture < 0) return;

        int start = tileModel.getStart();

        String facing = block.getBlockState().getProperties().getOrDefault("facing", "up");
        Vec normal = facingVector(facing);
        Vec center = new Vec(0.5, 0.5, 0.5);
        Vec anchor = center.sub(normal.mul(SURFACE_OFFSET));

        // Simple endpoint attachment plate. The dynamic span is the important part here;
        // this keeps the endpoint visible without depending on client-only StrutModelBuilder.
        appendEndpoint(anchor, normal, texture);

        int connections = 0;
        if (block.getBlockEntity() instanceof BitsNBobsStrutBlockEntity entity
                && entity.connections() instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> c)) continue;
                int dx = intValue(c.get("X"));
                int dy = intValue(c.get("Y"));
                int dz = intValue(c.get("Z"));
                if (dx == 0 && dy == 0 && dz == 0) continue;

                Vec peerNormal = normal.mul(-1);
                Object rawFacing = c.get("Facing");
                if (rawFacing instanceof Number n) {
                    peerNormal = facingVector(n.intValue());
                }

                Vec end = new Vec(dx + 0.5, dy + 0.5, dz + 0.5)
                        .sub(peerNormal.mul(SURFACE_OFFSET));
                Vec midpoint = anchor.add(end).mul(0.5);
                appendBeam(anchor, midpoint, normal, texture,
                        id.endsWith("cable_girder_strut") ? 0.06 : 0.18);
                connections++;
            }
        }

        tileModel.initialize(start);
        if (tileModel.getStart() > start) {
            blockColor.set(1f, 1f, 1f, 1f, true);
        }

        if (TRACED.add(id)) {
            Logger.global.logDebug(String.format(
                    "STATIC GIRDER block=%s connections=%s texture=%s",
                    id, connections, texture));
        }
    }

    private int textureFor(String id) {
        String[] candidates = BitsNBobsStrutTextures.candidates(id);

        for (String candidate : candidates) {
            ResourcePath<Texture> path = new ResourcePath<>(candidate);

            // ResourcePack#getTexture() is not an existence check in BlueMap 5.7:
            // it returns the magenta/black missing texture when the requested path
            // does not exist. Check the loaded texture map directly instead.
            if (!resourcePack.getTextures().containsKey(path)) continue;

            int index = textureGallery.get(path);
            if (index > 0) {
                if (TRACED.add(id + "#texture")) {
                    Logger.global.logDebug(String.format(
                            "STATIC GIRDER texture block=%s resolved=%s atlasIndex=%s",
                            id, candidate, index));
                }
                return index;
            }
        }

        // Last-resort neutral material. Prefer a visibly approximate iron girder over
        // ever emitting BlueMap's missing-texture atlas entry (index 0).
        ResourcePath<Texture> iron = new ResourcePath<>("minecraft:block/iron_block");
        if (resourcePack.getTextures().containsKey(iron)) {
            int index = textureGallery.get(iron);
            if (index > 0) {
                Logger.global.logWarning(String.format(
                        "STATIC GIRDER block=%s has no loaded B&B girder texture; using minecraft:block/iron_block",
                        id));
                return index;
            }
        }

        Logger.global.logWarning(String.format(
                "STATIC GIRDER block=%s has no usable texture; skipping instead of rendering missing-texture cubes",
                id));
        return -1;
    }

    private void appendEndpoint(Vec anchor, Vec normal, int texture) {
        Vec forward = normal;
        Vec up = Math.abs(forward.y()) < 0.9 ? new Vec(0,1,0) : new Vec(0,0,1);
        Vec right = up.cross(forward).normalize();
        up = forward.cross(right).normalize();

        double half = 5.0 / 16.0;
        double depth = 1.0 / 32.0;
        Vec c0 = anchor.add(right.mul(-half)).add(up.mul(-half)).add(forward.mul(-depth));
        Vec c1 = anchor.add(right.mul( half)).add(up.mul(-half)).add(forward.mul(-depth));
        Vec c2 = anchor.add(right.mul( half)).add(up.mul( half)).add(forward.mul(-depth));
        Vec c3 = anchor.add(right.mul(-half)).add(up.mul( half)).add(forward.mul(-depth));
        Vec d0 = c0.add(forward.mul(depth * 2));
        Vec d1 = c1.add(forward.mul(depth * 2));
        Vec d2 = c2.add(forward.mul(depth * 2));
        Vec d3 = c3.add(forward.mul(depth * 2));
        prism(c0,c1,c2,c3,d0,d1,d2,d3,texture);
    }

    private void appendBeam(Vec start, Vec end, Vec attachmentNormal, int texture, double width) {
        Vec span = end.sub(start);
        double length = span.length();
        if (length < 1.0e-5) return;
        Vec forward = span.mul(1.0 / length);

        Vec up = attachmentNormal.sub(forward.mul(attachmentNormal.dot(forward)));
        if (up.length() < 1.0e-5) {
            Vec fallback = Math.abs(forward.y()) < 0.95 ? new Vec(0,1,0) : new Vec(1,0,0);
            up = fallback.sub(forward.mul(fallback.dot(forward)));
        }
        up = up.normalize();
        Vec right = up.cross(forward).normalize();

        double h = width / 2.0;
        Vec a0 = start.add(right.mul(-h)).add(up.mul(-h));
        Vec a1 = start.add(right.mul( h)).add(up.mul(-h));
        Vec a2 = start.add(right.mul( h)).add(up.mul( h));
        Vec a3 = start.add(right.mul(-h)).add(up.mul( h));
        Vec b0 = end.add(right.mul(-h)).add(up.mul(-h));
        Vec b1 = end.add(right.mul( h)).add(up.mul(-h));
        Vec b2 = end.add(right.mul( h)).add(up.mul( h));
        Vec b3 = end.add(right.mul(-h)).add(up.mul( h));
        prism(a0,a1,a2,a3,b0,b1,b2,b3,texture);
    }

    private void prism(
            Vec a0, Vec a1, Vec a2, Vec a3,
            Vec b0, Vec b1, Vec b2, Vec b3,
            int texture) {
        face(a0,a1,a2,a3,texture);
        face(b3,b2,b1,b0,texture);
        face(a0,b0,b1,a1,texture);
        face(a1,b1,b2,a2,texture);
        face(a2,b2,b3,a3,texture);
        face(a3,b3,b0,a0,texture);
    }

    private void face(Vec a, Vec b, Vec c, Vec d, int texture) {
        tileModel.initialize();
        tileModel.add(2);
        TileModel target = tileModel.getTileModel();
        int f1 = tileModel.getStart();
        int f2 = f1 + 1;

        target.setPositions(f1,
                (float)a.x(),(float)a.y(),(float)a.z(),
                (float)b.x(),(float)b.y(),(float)b.z(),
                (float)c.x(),(float)c.y(),(float)c.z());
        target.setPositions(f2,
                (float)a.x(),(float)a.y(),(float)a.z(),
                (float)c.x(),(float)c.y(),(float)c.z(),
                (float)d.x(),(float)d.y(),(float)d.z());

        target.setUvs(f1, 0f,1f, 1f,1f, 1f,0f);
        target.setUvs(f2, 0f,1f, 1f,0f, 0f,0f);
        target.setMaterialIndex(f1, texture);
        target.setMaterialIndex(f2, texture);
        target.setColor(f1, 1f,1f,1f);
        target.setColor(f2, 1f,1f,1f);

        LightData light = block.getLightData();
        target.setSunlight(f1, light.getSkyLight());
        target.setSunlight(f2, light.getSkyLight());
        target.setBlocklight(f1, light.getBlockLight());
        target.setBlocklight(f2, light.getBlockLight());
        target.setAOs(f1, 1f,1f,1f);
        target.setAOs(f2, 1f,1f,1f);
    }

    private static int intValue(Object value) {
        return value instanceof Number n ? n.intValue() : 0;
    }

    private static Vec facingVector(String facing) {
        return switch (facing) {
            case "down" -> new Vec(0,-1,0);
            case "north" -> new Vec(0,0,-1);
            case "south" -> new Vec(0,0,1);
            case "west" -> new Vec(-1,0,0);
            case "east" -> new Vec(1,0,0);
            default -> new Vec(0,1,0);
        };
    }

    private static Vec facingVector(int dataValue) {
        return switch (dataValue) {
            case 0 -> new Vec(0,-1,0);
            case 2 -> new Vec(0,0,-1);
            case 3 -> new Vec(0,0,1);
            case 4 -> new Vec(-1,0,0);
            case 5 -> new Vec(1,0,0);
            default -> new Vec(0,1,0);
        };
    }

    private record Vec(double x, double y, double z) {
        Vec add(Vec o) { return new Vec(x + o.x, y + o.y, z + o.z); }
        Vec sub(Vec o) { return new Vec(x - o.x, y - o.y, z - o.z); }
        Vec mul(double s) { return new Vec(x * s, y * s, z * s); }
        double dot(Vec o) { return x*o.x + y*o.y + z*o.z; }
        Vec cross(Vec o) {
            return new Vec(
                    y*o.z - z*o.y,
                    z*o.x - x*o.z,
                    x*o.y - y*o.x);
        }
        double length() { return Math.sqrt(dot(this)); }
        Vec normalize() {
            double l = length();
            return l < 1.0e-9 ? new Vec(0,1,0) : mul(1.0/l);
        }
    }
}
