package dev.duzo.bluemapctm;

/**
 * The two-by-two-by-two virtual CT lattice used by Copycats+'s byte panels.
 * One part occupies one 8px cell along each face axis and only the outer
 * depth cell. Coordinates outside 0..1 represent adjacent full blocks.
 */
public final class BytePanelCells {
    private BytePanelCells() {}

    public static String partAt(String facing, int x, int y, int z) {
        if (x < 0 || x > 1 || y < 0 || y > 1 || z < 0 || z > 1) return null;
        int horizontal;
        int vertical;
        switch (facing) {
            case "south" -> { if (z != 1) return null; horizontal = x; vertical = y; }
            case "north" -> { if (z != 0) return null; horizontal = 1-x; vertical = y; }
            case "east" -> { if (x != 1) return null; horizontal = 1-z; vertical = y; }
            case "west" -> { if (x != 0) return null; horizontal = z; vertical = y; }
            case "up" -> { if (y != 1) return null; horizontal = x; vertical = 1-z; }
            case "down" -> { if (y != 0) return null; horizontal = x; vertical = z; }
            default -> { return null; }
        }
        return (vertical == 0 ? "bottom_" : "top_")
                + (horizontal == 1 ? "left" : "right");
    }

    public static int cellOfPixel(float pixel) {
        return pixel >= 8f ? 1 : 0;
    }

    public static int worldBlockOffset(int localCell) {
        return Math.floorDiv(localCell, 2);
    }

    public static int localCell(int cell) {
        return Math.floorMod(cell, 2);
    }
}
