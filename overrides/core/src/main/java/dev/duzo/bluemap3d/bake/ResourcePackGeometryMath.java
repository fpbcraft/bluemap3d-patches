package dev.duzo.bluemap3d.bake;

/** Pure numeric implementation of Minecraft block-model geometry semantics. */
final class ResourcePackGeometryMath {

    static final int DOWN = 0;
    static final int UP = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;
    static final int WEST = 4;
    static final int EAST = 5;

    private ResourcePackGeometryMath() {
    }

    static float[] autoUv(float[] from, float[] to, int face) {
        float x0 = from[0], y0 = from[1], z0 = from[2];
        float x1 = to[0], y1 = to[1], z1 = to[2];
        return switch (face) {
            case DOWN -> new float[]{x0, z0, x1, z1};
            case UP -> new float[]{x0, 16 - z1, x1, 16 - z0};
            case NORTH -> new float[]{16 - x1, 16 - y1, 16 - x0, 16 - y0};
            case SOUTH -> new float[]{x0, 16 - y1, x1, 16 - y0};
            case WEST -> new float[]{z0, 16 - y1, z1, 16 - y0};
            case EAST -> new float[]{16 - z1, 16 - y1, 16 - z0, 16 - y0};
            default -> throw new IllegalArgumentException("Unknown face code: " + face);
        };
    }

    static float[] faceCorners(float[] from, float[] to, int face) {
        float x0 = from[0], y0 = from[1], z0 = from[2];
        float x1 = to[0], y1 = to[1], z1 = to[2];
        return switch (face) {
            case UP -> new float[]{x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1};
            case DOWN -> new float[]{x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0};
            case NORTH -> new float[]{x1, y1, z0, x0, y1, z0, x0, y0, z0, x1, y0, z0};
            case SOUTH -> new float[]{x0, y1, z1, x1, y1, z1, x1, y0, z1, x0, y0, z1};
            case WEST -> new float[]{x0, y1, z0, x0, y1, z1, x0, y0, z1, x0, y0, z0};
            case EAST -> new float[]{x1, y1, z1, x1, y1, z0, x1, y0, z0, x1, y0, z1};
            default -> throw new IllegalArgumentException("Unknown face code: " + face);
        };
    }

    static float[] uvCorners(float[] uv, int rotation) {
        float u0 = uv[0], v0 = uv[1], u1 = uv[2], v1 = uv[3];
        float[][] corners = {{u0, v0}, {u1, v0}, {u1, v1}, {u0, v1}};

        int steps = normaliseSteps(rotation);
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            float[] corner = corners[(i + steps) % 4];
            out[i * 2] = corner[0];
            out[i * 2 + 1] = corner[1];
        }
        return out;
    }

    static void applyElementRotation(
            float[] corners,
            float[] origin,
            String axis,
            float angle) {
        if (Math.abs(angle) < 1e-6f) {
            return;
        }

        double radians = Math.toRadians(angle);
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);

        for (int i = 0; i < 4; i++) {
            float x = corners[i * 3] - origin[0];
            float y = corners[i * 3 + 1] - origin[1];
            float z = corners[i * 3 + 2] - origin[2];
            float nx = x, ny = y, nz = z;
            switch (axis) {
                case "x" -> {
                    ny = y * cos - z * sin;
                    nz = y * sin + z * cos;
                }
                case "z" -> {
                    nx = x * cos - y * sin;
                    ny = x * sin + y * cos;
                }
                default -> {
                    nx = x * cos + z * sin;
                    nz = -x * sin + z * cos;
                }
            }
            corners[i * 3] = nx + origin[0];
            corners[i * 3 + 1] = ny + origin[1];
            corners[i * 3 + 2] = nz + origin[2];
        }
    }

    static void applyVariantRotation(float[] corners, int rotX, int rotY) {
        int stepsX = normaliseSteps(rotX);
        double radiansY = Math.toRadians(rotY);
        double cosY = Math.cos(radiansY);
        double sinY = Math.sin(radiansY);

        for (int i = 0; i < 4; i++) {
            float x = corners[i * 3] - 8f;
            float y = corners[i * 3 + 1] - 8f;
            float z = corners[i * 3 + 2] - 8f;

            for (int step = 0; step < stepsX; step++) {
                float ny = z, nz = -y;
                y = ny;
                z = nz;
            }

            double nx = x * cosY - z * sinY;
            double nz = x * sinY + z * cosY;
            corners[i * 3] = (float) nx + 8f;
            corners[i * 3 + 1] = y + 8f;
            corners[i * 3 + 2] = (float) nz + 8f;
        }
    }

    static int rotateDirection(int direction, int rotX, int rotY) {
        int out = direction;
        for (int step = 0; step < normaliseSteps(rotX); step++) {
            out = rotateAroundX(out);
        }
        for (int step = 0; step < normaliseSteps(rotY); step++) {
            out = rotateClockwiseY(out);
        }
        return out;
    }

    private static int rotateAroundX(int direction) {
        return switch (direction) {
            case NORTH -> DOWN;
            case DOWN -> SOUTH;
            case SOUTH -> UP;
            case UP -> NORTH;
            default -> direction;
        };
    }

    private static int rotateClockwiseY(int direction) {
        return switch (direction) {
            case NORTH -> EAST;
            case EAST -> SOUTH;
            case SOUTH -> WEST;
            case WEST -> NORTH;
            default -> direction;
        };
    }

    private static int normaliseSteps(int degrees) {
        return ((degrees % 360) + 360) % 360 / 90;
    }
}
