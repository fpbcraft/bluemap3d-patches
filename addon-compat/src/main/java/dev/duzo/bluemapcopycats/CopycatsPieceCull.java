package dev.duzo.bluemapcopycats;

/**
 * Pure internal-face rules used by the eight-piece Copycats+ byte and
 * horizontal byte-panel models. The order is DOWN, UP, NORTH, SOUTH, WEST,
 * EAST, matching both Minecraft Direction and the static cuboid builder.
 */
final class CopycatsPieceCull {
    private CopycatsPieceCull() {}

    static boolean internalBytePieceFace(int face, int subX, int subY, int subZ) {
        return switch (face) {
            case 0 -> subY == 1; // DOWN
            case 1 -> subY == 0; // UP
            case 2 -> subZ == 1; // NORTH
            case 3 -> subZ == 0; // SOUTH
            case 4 -> subX == 1; // WEST
            case 5 -> subX == 0; // EAST
            default -> false;
        };
    }
}
