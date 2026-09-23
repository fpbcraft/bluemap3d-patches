package dev.duzo.bluemap3d.bake;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Serialises a {@link BakedMesh} to the {@code .bm3d} wire format.
 *
 * <p>Deliberately not glTF. BlueMap 5.7's webapp bundle contains no {@code GLTFLoader}
 * (checked against the deployed bundle), so glTF would mean vendoring a loader into the
 * injected script and pinning it to a three.js version. It does, however, export its
 * own three.js as {@code window.BlueMap.Three} - so a format the client can turn
 * straight into a {@code BufferGeometry} costs a few dozen lines at each end, ships no
 * third-party code, and cannot drift out of version with the renderer.
 *
 * <h2>Layout</h2>
 * Little-endian throughout. Every multi-byte array starts on a 4-byte boundary so the
 * browser can wrap the buffer in typed arrays with no copying.
 * <pre>
 *   offset  type          field
 *   0       char[4]       magic "BM3D"
 *   4       u32           format version (6)
 *   8       u32           vertex count
 *   12      u32           index count
 *   16      u32           atlas url length in bytes
 *   20      u8[]          atlas url, utf-8, zero-padded to a 4-byte boundary
 *   ...     f32[v * 3]    positions, block units relative to the pivot
 *   ...     f32[v * 2]    uvs, normalised into the atlas
 *   ...     u32[i]        indices
 *   ...     u8[v * 3]     vertex colours, RGB, zero-padded to a 4-byte boundary
 *   ...     u32           static index count: the parent's draw range is [0, this)
 *   ...     u32           node count
 *   ...     node[]        one per animated part, in draw order:
 *                           u32     kind, see {@link BakedMesh#KIND_SPIN} and siblings
 *                           u32     index start
 *                           u32     index count
 *                           f32[3]  pivot, block units relative to the object pivot -
 *                                   except for {@code KIND_ORBIT}, where it is a
 *                                   displacement rather than a point: the vector from the
 *                                   orbit's centre to the part's baked rest position
 *                           f32[3]  axis, normalised
 *                           f32     radius, block units
 *                           f32     period, the divisor in {@code sin(travel / period)} /
 *                                   {@code travel / period}, block units (a full cycle is
 *                                   {@code 2 * PI * period} of travel, not {@code period}
 *                                   itself) - unused by
 *                                   {@code KIND_SPIN}, present regardless so every node
 *                                   is the same size
 *                           f32     rate, radians per second - {@code KIND_RATE} only,
 *                                   present regardless for the same reason as period
 * </pre>
 *
 * <p>v6 adds {@code KIND_LOOP} without changing the node layout: its axis is the
 * translation direction, period is the wrap distance and rate is blocks per second.
 * Older files remain readable.
 *
 * <p>v5 changed no layout at all - it re-reads the {@code pivot} field of a
 * {@code KIND_ORBIT} node as a displacement rather than as the point being orbited. The
 * version had to move regardless, because mesh urls carry it and the browser fetches
 * them {@code cache: "force-cache"}: a returning viewer holding a cached v4 body would
 * otherwise decode its old orbit pivots under the new meaning and hang every orbiting
 * part somewhere it never was.
 *
 * <p>v2 wrote the same trailer without the {@code kind} and {@code period} fields -
 * every one of its nodes was implicitly {@code KIND_SPIN}. v3 added those two fields but
 * not {@code rate}, because {@code KIND_RATE} did not exist yet. Turtles, ships and
 * existing contraptions were baked under v1, v2 or v3 and still decode fine, because the
 * browser fills in {@code KIND_SPIN}, a zero period and a zero rate for whichever fields
 * a node's format version does not carry.
 *
 * <p>The colour block is padded to a 4-byte boundary. It is the only unpadded array in the
 * file, and in v1 nothing followed it so that never mattered. It happens to be aligned
 * today only because MeshBuilder.quad is the sole writer and always appends four
 * vertices at a time. The trailer makes that invariant load-bearing, and a
 * Float32Array cannot be wrapped around a non-multiple-of-4 offset, so pad rather than
 * relying on it.
 */
public final class Bm3dWriter {

    /** Current format version. Bumped only on an incompatible layout change. */
    public static final int VERSION = 6;

    private static final byte[] MAGIC = {'B', 'M', '3', 'D'};

    private Bm3dWriter() {
    }

    /**
     * Encodes a mesh.
     *
     * @param mesh     the mesh; must not be {@linkplain BakedMesh#isEmpty() empty}
     * @param atlasUrl the URL the client should load the texture atlas from
     * @return the encoded bytes
     */
    public static byte[] encode(BakedMesh mesh, String atlasUrl) {
        byte[] url = atlasUrl.getBytes(StandardCharsets.UTF_8);
        int urlPadded = (url.length + 3) & ~3;

        int vertices = mesh.vertexCount();
        int colorsPadded = (mesh.colors().length + 3) & ~3;
        // 3 words per node (kind, index start, index count; node count is separate),
        // plus 3 + 3 + 1 + 1 + 1 floats for pivot, axis, radius, period and rate.
        int nodesSize = mesh.nodes().size() * (4 * 3 + 4 * 9);

        int size = 20 + urlPadded
                + vertices * 3 * 4
                + vertices * 2 * 4
                + mesh.indices().length * 4
                + colorsPadded
                + 4 + 4 + nodesSize;

        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(MAGIC);
        buf.putInt(VERSION);
        buf.putInt(vertices);
        buf.putInt(mesh.indices().length);
        buf.putInt(url.length);
        buf.put(url);
        for (int i = url.length; i < urlPadded; i++) {
            buf.put((byte) 0);
        }

        for (float f : mesh.positions()) {
            buf.putFloat(f);
        }
        for (float f : mesh.uvs()) {
            buf.putFloat(f);
        }
        for (int i : mesh.indices()) {
            buf.putInt(i);
        }
        buf.put(mesh.colors());
        for (int i = mesh.colors().length; i < colorsPadded; i++) {
            buf.put((byte) 0);
        }

        buf.putInt(mesh.staticIndexCount());
        buf.putInt(mesh.nodes().size());
        for (BakedMesh.Node node : mesh.nodes()) {
            buf.putInt(node.kind());
            buf.putInt(node.indexStart());
            buf.putInt(node.indexCount());
            for (float f : node.pivot()) {
                buf.putFloat(f);
            }
            for (float f : node.axis()) {
                buf.putFloat(f);
            }
            buf.putFloat(node.radius());
            buf.putFloat(node.period());
            buf.putFloat(node.rate());
        }

        return buf.array();
    }

    /** Encodes a mesh straight to a stream. The stream is not closed. */
    public static void write(BakedMesh mesh, String atlasUrl, OutputStream out) throws IOException {
        out.write(encode(mesh, atlasUrl));
    }
}
