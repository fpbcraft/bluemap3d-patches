package dev.duzo.bluemapcopycats;

final class DiagonalDirectionMapping {

    private DiagonalDirectionMapping() {
    }

    static String cardinalFor(String diagonalProperty) {
        // MultipartAppender creates each diagonal selector from the cardinal selector
        // whose direction.rotateClockWise() equals that diagonal:
        // N->NE, E->SE, S->SW, W->NW.
        return switch (diagonalProperty) {
            case "north_east" -> "north";
            case "south_east" -> "east";
            case "south_west" -> "south";
            case "north_west" -> "west";
            default -> throw new IllegalArgumentException(
                    "Unknown diagonal direction: " + diagonalProperty);
        };
    }

    static float rotationDegrees() {
        // QuadUtils.ROTATION_ANGLE in Diagonal Blocks 1.21.1 is exactly -45 degrees.
        return -45f;
    }
}
