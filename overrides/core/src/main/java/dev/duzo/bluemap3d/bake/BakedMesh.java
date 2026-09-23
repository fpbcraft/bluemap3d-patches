package dev.duzo.bluemap3d.bake;

import java.awt.image.BufferedImage;
import java.util.List;

/**
 * A meshed block volume: one vertex buffer, one index buffer, one texture atlas.
 *
 * <p>Positions are in block units relative to the volume's
 * {@linkplain dev.duzo.bluemap3d.api.BlockVolume#pivot() pivot}, so the browser can
 * place the mesh by writing a position and a quaternion and nothing else.
 *
 * <p>Colours are the block's directional face shading multiplied by any biome or
 * redstone tint, baked per vertex. That is what lets the mesh render with an unlit
 * material: BlueMap's marker scene contains no lights, so a lit material would come
 * out black.
 *
 * @param positions   3 floats per vertex, in block units relative to the pivot
 * @param uvs         2 floats per vertex, normalised 0..1 into {@code atlas}
 * @param colors      3 bytes per vertex, unsigned RGB
 * @param indices     3 per triangle
 * @param atlas       the texture every uv refers to
 * @param sourceBlocks how many blocks went in, for logging and limits
 * @param staticIndexCount indices before any animated attachment was emitted. The browser
 *                         clamps the parent mesh's draw range to this, so a node's geometry
 *                         is drawn once, by the node, and never a second time by the parent
 * @param nodes            the parts of the mesh that the browser animates independently
 */
public record BakedMesh(
        float[] positions,
        float[] uvs,
        byte[] colors,
        int[] indices,
        BufferedImage atlas,
        int sourceBlocks,
        int staticIndexCount,
        List<Node> nodes
) {
    /** {@link Node#kind()}: turns about {@code axis}, angle {@code travel / radius}. */
    public static final int KIND_SPIN = 0;
    /** {@link Node#kind()}: slides along {@code axis}, offset {@code radius * sin(travel / period)}. */
    public static final int KIND_OSCILLATE = 1;
    /**
     * {@link Node#kind()}: displaced, never turned, by {@code pivot} turned about
     * {@code axis} through {@code travel / period} minus {@code pivot} itself. For this
     * kind alone {@code pivot} is a displacement rather than a point - the vector from
     * the orbit's centre to the part's baked rest position - so the rest pose needs no
     * zero-angle reference agreed separately with the browser.
     */
    public static final int KIND_ORBIT = 2;
    /** {@link Node#kind()}: turns about {@code axis} at a constant {@code rate}, independent of travel. */
    public static final int KIND_RATE = 3;
    /** {@link Node#kind()}: translates along {@code axis} at {@code rate}, wrapping every {@code period}. */
    public static final int KIND_LOOP = 4;

    /**
     * A part of the mesh that the browser animates, rather than one baked in place.
     *
     * <p>An index range rather than a vertex range because that is what three.js's
     * {@code setDrawRange} takes. {@code pivot}, {@code period} and {@code rate} are
     * unused by some kinds - see the {@code KIND_*} constants and
     * {@link dev.duzo.bluemap3d.api.ModelAttachment.Motion} for what each kind actually
     * reads.
     */
    public record Node(int kind, int indexStart, int indexCount, float[] pivot, float[] axis,
                       float radius, float period, float rate) {
    }

    /** Number of vertices. */
    public int vertexCount() {
        return positions.length / 3;
    }

    /** Number of triangles. */
    public int triangleCount() {
        return indices.length / 3;
    }

    /** Whether this mesh has nothing to draw. */
    public boolean isEmpty() {
        return indices.length == 0;
    }
}
