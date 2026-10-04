package dev.duzo.bluemapcopycats;

final class DiagonalDirectionMapping {

    private DiagonalDirectionMapping() {
    }

    static String cardinalFor(String diagonalProperty) {
        // Mirrors EightWayDirection.rotateClockWise() + MultipartAppender in
        // Diagonal Blocks 1.21.1. The source segment is the clockwise-adjacent
        // cardinal arm, not the cardinal arm with the same first compass word.
        return switch (diagonalProperty) {
            case "north_east" -> "east";
            case "south_east" -> "south";
            case "south_west" -> "west";
            case "north_west" -> "north";
            default -> throw new IllegalArgumentException(
                    "Unknown diagonal direction: " + diagonalProperty);
        };
    }
}
