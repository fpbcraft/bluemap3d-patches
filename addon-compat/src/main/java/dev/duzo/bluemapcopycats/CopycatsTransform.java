package dev.duzo.bluemapcopycats;

import java.util.ArrayList;
import java.util.List;

/**
 * Copycats block-space transform in 0..16 model coordinates.
 *
 * <p>Mirrored transforms reverse quad winding after applying coordinate operations so
 * generated faces retain the same orientation.
 */
final class CopycatsTransform {

    private static final int RX = 1;
    private static final int RY = 2;
    private static final int RZ = 3;
    private static final int FX = 4;
    private static final int FY = 5;
    private static final int FZ = 6;

    private final List<Integer> ops = new ArrayList<>();
    private boolean mirrored;

    CopycatsTransform rotateX(int degrees) {
        addRotation(RX, degrees);
        return this;
    }

    CopycatsTransform rotateY(int degrees) {
        addRotation(RY, degrees);
        return this;
    }

    CopycatsTransform rotateZ(int degrees) {
        addRotation(RZ, degrees);
        return this;
    }

    CopycatsTransform flipX(boolean enabled) {
        if (enabled) {
            ops.add(FX);
            mirrored = !mirrored;
        }
        return this;
    }

    CopycatsTransform flipY(boolean enabled) {
        if (enabled) {
            ops.add(FY);
            mirrored = !mirrored;
        }
        return this;
    }

    CopycatsTransform flipZ(boolean enabled) {
        if (enabled) {
            ops.add(FZ);
            mirrored = !mirrored;
        }
        return this;
    }

    boolean mirrored() {
        return mirrored;
    }

    void applyQuad(float[] positions) {
        apply(positions);
        if (mirrored) reverseWinding(positions);
    }

    void apply(float[] positions) {
        for (int i = 0; i < positions.length; i += 3) {
            float x = positions[i];
            float y = positions[i + 1];
            float z = positions[i + 2];

            for (int op : ops) {
                float nx = x;
                float ny = y;
                float nz = z;
                switch (op) {
                    case FX -> nx = 16 - x;
                    case FY -> ny = 16 - y;
                    case FZ -> nz = 16 - z;
                    case RX -> {
                        ny = 16 - z;
                        nz = y;
                    }
                    case RY -> {
                        nx = 16 - z;
                        nz = x;
                    }
                    case RZ -> {
                        nx = 16 - y;
                        ny = x;
                    }
                    default -> { }
                }
                x = nx;
                y = ny;
                z = nz;
            }

            positions[i] = x;
            positions[i + 1] = y;
            positions[i + 2] = z;
        }
    }

    private void addRotation(int op, int degrees) {
        int turns = Math.floorMod(degrees / 90, 4);
        for (int i = 0; i < turns; i++) ops.add(op);
    }

    private static void reverseWinding(float[] positions) {
        for (int i = 0; i < 3; i++) {
            float value = positions[3 + i];
            positions[3 + i] = positions[9 + i];
            positions[9 + i] = value;
        }
    }
}
