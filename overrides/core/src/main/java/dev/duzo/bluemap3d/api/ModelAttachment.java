package dev.duzo.bluemap3d.api;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

import java.util.Map;
import java.util.Objects;

/**
 * An extra model to draw on top of a {@link BlockVolume}'s blocks.
 *
 * <p>Block states do not describe everything you can see. A turtle's modem and pickaxe, an
 * item frame's contents, the text on a sign - all of those are drawn by a block-entity
 * renderer from state that lives in the block entity, not in the block state. A volume made
 * only of block states cannot show any of it.
 *
 * <p>An attachment fills that gap by naming a model directly. It is deliberately not a
 * general-purpose transform: the models involved are authored in the same 0..16 space as the
 * block they belong to - CC's {@code turtle_modem_normal_off_left} is positioned against the
 * turtle's left face already - so an attachment just says which model, at which block.
 *
 * <p>Attachments live on {@link BlockVolume} rather than {@link SceneObject} because they
 * are geometry, which means they are baked and cached against
 * {@link SceneObject#geometryVersion()} like everything else. Bump that version when an
 * attachment appears, disappears or changes.
 *
 * @param at       which block of the volume this hangs off, in the volume's local
 *                 coordinates
 * @param model    the model to draw, e.g.
 *                 {@code computercraft:block/turtle_modem_normal_off_left}
 * @param textures overrides for the model's {@code #ref} texture variables, highest
 *                 priority. CC's upgrade mount leaves {@code #texture} for the caller to
 *                 fill in, which is how one mount model serves every tool.
 * @param transform an optional transform in <em>block units</em>, applied after the model's
 *                  0..16 coordinates are scaled down. Identity for models already positioned
 *                  where they belong, which is most of them. It exists for item models,
 *                  which are authored facing the viewer and have to be rotated into place -
 *                  CC positions a turtle's tool with exactly such a matrix.
 * @param motion    set when the part moves as the object travels, null when it is baked in
 *                  place. See {@link Motion}
 */
public record ModelAttachment(BlockPos at, ResourceLocation model, Map<String, String> textures,
                              org.joml.Matrix4f transform, Motion motion) {

    public ModelAttachment {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(model, "model");
        textures = textures == null ? Map.of() : Map.copyOf(textures);
        transform = transform == null ? new org.joml.Matrix4f() : new org.joml.Matrix4f(transform);
    }

    /** No transform: the model is already positioned where it belongs. */
    public ModelAttachment(BlockPos at, ResourceLocation model, Map<String, String> textures) {
        this(at, model, textures, null, null);
    }

    /** A static attachment: no motion. */
    public ModelAttachment(BlockPos at, ResourceLocation model, Map<String, String> textures,
                           org.joml.Matrix4f transform) {
        this(at, model, textures, transform, null);
    }

    /**
     * How a part moves as its object travels, rather than sitting baked in place.
     *
     * <p>Every kind is driven by the same value: how far the object itself has moved,
     * accumulated in the browser as a per-node odometer. Never an angle or a pose sampled
     * at {@code publishIntervalTicks} - that would alias hopelessly, because the default
     * publish rate is a couple of samples a second and a wheel turns several times a
     * second. A distance is immune to that, because it is integrated rather than sampled.
     *
     * <p>A sealed interface rather than one record with a kind field and a pile of
     * kind-specific parameters, because the kinds do not share a parameter shape: a spin
     * needs no period, an oscillation needs no pivot. Each kind states only what it uses.
     */
    public sealed interface Motion permits Spin, Oscillate, Orbit, Rate, Loop {
    }

    /**
     * A part that turns about a fixed axis as its object travels - a wheel.
     *
     * @param pivot  the point the part turns about, in the model's own 0..16 space
     * @param axis   the axle direction, in the model's own 0..16 space. Normalised on
     *               construction
     * @param radius the part's visual radius, in the model's own 0..16 space. The browser
     *               turns the part by {@code travel / radius} radians, so a radius that
     *               does not match what is drawn makes the part slip against the ground
     */
    public record Spin(Vector3f pivot, Vector3f axis, float radius) implements Motion {
        public Spin {
            Objects.requireNonNull(pivot, "pivot");
            Objects.requireNonNull(axis, "axis");
            // JOML's normalize() has no zero guard - it multiplies by invsqrt(0) - so a
            // zero axis would return (NaN, NaN, NaN) rather than throwing. A NaN axis
            // makes the whole child vanish in the browser, which is a far worse failure
            // than an exception here.
            if (axis.lengthSquared() < 1.0e-20f) {
                throw new IllegalArgumentException("axis must be non-zero");
            }
            // Copied for the same reason the enclosing record copies its transform: JOML
            // types are mutable and the volume is handed to a background baker.
            pivot = new Vector3f(pivot);
            axis = new Vector3f(axis).normalize();
            // Phrased as a negated comparison so NaN is rejected too.
            if (!(radius > 0)) {
                throw new IllegalArgumentException("radius must be positive, was " + radius);
            }
        }
    }

    /**
     * A part that slides back and forth along a fixed axis as its object travels - a
     * piston rod.
     *
     * <p>No pivot: the offset is added straight to the part's baked position, in the
     * declared direction, so there is nothing to turn about.
     *
     * @param axis      the direction the part slides in, in the model's own 0..16 space.
     *                  Normalised on construction
     * @param amplitude how far the part slides from its baked position, in the model's
     *                  own 0..16 space. The browser offsets it by
     *                  {@code amplitude * sin(travel / period)}
     * @param period    the divisor in that sine, in the model's own 0..16 space (converted
     *                  the same way amplitude is). A full back-and-forth cycle takes
     *                  {@code 2 * PI * period} of travel, not {@code period} itself.
     *                  A short period means a fast piston
     */
    public record Oscillate(Vector3f axis, float amplitude, float period) implements Motion {
        public Oscillate {
            Objects.requireNonNull(axis, "axis");
            // Same zero-axis guard as Spin, and for the same reason.
            if (axis.lengthSquared() < 1.0e-20f) {
                throw new IllegalArgumentException("axis must be non-zero");
            }
            axis = new Vector3f(axis).normalize();
            if (!(amplitude > 0)) {
                throw new IllegalArgumentException("amplitude must be positive, was " + amplitude);
            }
            if (!(period > 0)) {
                throw new IllegalArgumentException("period must be positive, was " + period);
            }
        }
    }

    /**
     * A part whose centre travels a circle about a pivot while its own orientation stays
     * fixed - a bogey pin. Unlike {@link Spin}, the geometry never turns; only its
     * position moves.
     *
     * <p>Bake the part where it should sit at rest, and give {@code pivot} as the centre
     * of the circle relative to that rest position. Those two together fix the orbit
     * completely: the vector from the pivot back to the part is both the radius and the
     * direction the part sits in at angle zero, and the browser gets its displacement by
     * turning that one vector about {@code axis}. Which is why there is no radius here -
     * the pivot already carries it, and a separate figure could only ever disagree with
     * it.
     *
     * <p>That is not a tidying-up. The earlier shape of this record left the zero-angle
     * direction unstated, so the browser had to derive one from {@code axis} alone; the
     * derived direction is only correct for whichever axis direction it happens to agree
     * with, and the same crank pin then orbited the axle on a bogey laid one way and some
     * point beside the axle on a bogey laid the other.
     *
     * @param pivot  the centre of the circle, relative to the part's own model origin, in
     *               the model's own 0..16 space. Must be non-zero. A component along
     *               {@code axis} is harmless - it simply never moves
     * @param axis   the orbit's axis, in the model's own 0..16 space. Normalised on
     *               construction
     * @param period the divisor in {@code travel / period}, in the model's own 0..16
     *               space. A full orbit takes {@code 2 * PI * period} of travel, not
     *               {@code period} itself.
     */
    public record Orbit(Vector3f pivot, Vector3f axis, float period) implements Motion {
        public Orbit {
            Objects.requireNonNull(pivot, "pivot");
            Objects.requireNonNull(axis, "axis");
            if (axis.lengthSquared() < 1.0e-20f) {
                throw new IllegalArgumentException("axis must be non-zero");
            }
            // A zero pivot is not a degenerate orbit, it is a missing one: with the part
            // sitting on its own orbit centre there is no rest direction to turn and
            // nothing would ever move.
            if (pivot.lengthSquared() < 1.0e-20f) {
                throw new IllegalArgumentException("pivot must be non-zero");
            }
            pivot = new Vector3f(pivot);
            axis = new Vector3f(axis).normalize();
            if (!(period > 0)) {
                throw new IllegalArgumentException("period must be positive, was " + period);
            }
        }
    }

    /**
     * A part that turns about a fixed axis at a constant rate, independent of whether
     * its object is moving at all - a cogwheel driven by something else, rather than
     * rolling under its own travel.
     *
     * <p>Every other {@link Motion} is driven by the object's own travel, which is why
     * they need no data in the live feed: the browser already tracks travel as a
     * per-node odometer. A driven part has no travel to read - its object can be
     * standing perfectly still - so the rate has to come from somewhere else. It is
     * baked into the mesh instead of published per interval: the rate is wrong only
     * when it changes, and a rate change is a block state change, which re-bakes the
     * mesh anyway and picks up the new value. That keeps the live feed exactly as small
     * as it is for every other kind - one position and one rotation per object.
     *
     * <p>The browser turns the part by {@code radiansPerSecond * elapsedSeconds},
     * continuously, using wall-clock time rather than the odometer.
     *
     * @param pivot           the point the part turns about, in the model's own 0..16
     *                        space
     * @param axis            the axle direction, in the model's own 0..16 space.
     *                        Normalised on construction
     * @param radiansPerSecond how fast the part turns. Must be positive - a part that
     *                        should not move should not carry a {@link Rate} at all
     */
    public record Rate(Vector3f pivot, Vector3f axis, float radiansPerSecond) implements Motion {
        public Rate {
            Objects.requireNonNull(pivot, "pivot");
            Objects.requireNonNull(axis, "axis");
            if (axis.lengthSquared() < 1.0e-20f) {
                throw new IllegalArgumentException("axis must be non-zero");
            }
            pivot = new Vector3f(pivot);
            axis = new Vector3f(axis).normalize();
            if (!(radiansPerSecond > 0)) {
                throw new IllegalArgumentException(
                        "radiansPerSecond must be positive, was " + radiansPerSecond);
            }
        }
    }

    /**
     * A part that translates continuously along an axis and wraps after a fixed distance.
     *
     * <p>This is intended for repeated geometry such as chain-conveyor links. The browser
     * integrates {@code blocksPerSecond} against wall-clock time and applies
     * {@code distance % period}. Providers place one extra repeated segment outside each
     * end of the visible run, so wrapping is visually continuous without changing UVs in
     * the shared texture atlas.
     *
     * @param axis            direction of travel in model space; normalized on construction
     * @param blocksPerSecond positive translation speed in world block units per second
     * @param period          wrap distance in the model's own 0..16 space
     */
    public record Loop(Vector3f axis, float blocksPerSecond, float period) implements Motion {
        public Loop {
            Objects.requireNonNull(axis, "axis");
            if (axis.lengthSquared() < 1.0e-20f) {
                throw new IllegalArgumentException("axis must be non-zero");
            }
            axis = new Vector3f(axis).normalize();
            if (!(blocksPerSecond > 0)) {
                throw new IllegalArgumentException(
                        "blocksPerSecond must be positive, was " + blocksPerSecond);
            }
            if (!(period > 0)) {
                throw new IllegalArgumentException("period must be positive, was " + period);
            }
        }
    }

    /** An attachment on the block at the volume's local origin, with no texture overrides. */
    public static ModelAttachment of(ResourceLocation model) {
        return new ModelAttachment(BlockPos.ZERO, model, Map.of());
    }

    /** An attachment on the block at the volume's local origin, overriding one texture. */
    public static ModelAttachment of(ResourceLocation model, String variable, String texture) {
        return new ModelAttachment(BlockPos.ZERO, model, Map.of(variable, texture));
    }

    /** Whether {@link #transform()} is anything other than the identity. */
    public boolean hasTransform() {
        return !transform.equals(new org.joml.Matrix4f(), 1.0e-6f);
    }
}
