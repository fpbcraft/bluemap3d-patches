package dev.duzo.bluemap3d.bake;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Texture-sheet layout rules shared by Fusion-compatible and Create connected textures.
 *
 * <p>The connection mask uses Fusion's clockwise bit order:
 * top, top-right, right, bottom-right, bottom, bottom-left, left, top-left.
 */
final class ConnectedTextureLayout {

    static final int TOP = 1;
    static final int TOP_RIGHT = 1 << 1;
    static final int RIGHT = 1 << 2;
    static final int BOTTOM_RIGHT = 1 << 3;
    static final int BOTTOM = 1 << 4;
    static final int BOTTOM_LEFT = 1 << 5;
    static final int LEFT = 1 << 6;
    static final int TOP_LEFT = 1 << 7;

    private static final int[] FUSION_FULL = {
        0, 24, 0, 24, 1, 12, 1, 25, 0, 24, 0, 24, 1, 12, 1, 25,
        8, 16, 8, 16, 4, 6, 4, 20, 8, 16, 8, 16, 9, 22, 9, 17,
        0, 24, 0, 24, 1, 12, 1, 25, 0, 24, 0, 24, 1, 12, 1, 25,
        8, 16, 8, 16, 4, 6, 4, 20, 8, 16, 8, 16, 9, 22, 9, 17,
        3, 13, 3, 13, 2, 14, 2, 30, 3, 13, 3, 13, 2, 14, 2, 30,
        5, 15, 5, 15, 7, 33, 7, 44, 5, 15, 5, 15, 21, 36, 21, 34,
        3, 13, 3, 13, 2, 14, 2, 30, 3, 13, 3, 13, 2, 14, 2, 30,
        11, 29, 11, 29, 23, 37, 23, 32, 11, 29, 11, 29, 10, 35, 10, 47,
        0, 24, 0, 24, 1, 12, 1, 25, 0, 24, 0, 24, 1, 12, 1, 25,
        8, 16, 8, 16, 4, 6, 4, 20, 8, 16, 8, 16, 9, 22, 9, 17,
        0, 24, 0, 24, 1, 12, 1, 25, 0, 24, 0, 24, 1, 12, 1, 25,
        8, 16, 8, 16, 4, 6, 4, 20, 8, 16, 8, 16, 9, 22, 9, 17,
        3, 27, 3, 27, 2, 28, 2, 26, 3, 27, 3, 27, 2, 28, 2, 26,
        5, 31, 5, 31, 7, 45, 7, 42, 5, 31, 5, 31, 21, 40, 21, 39,
        3, 27, 3, 27, 2, 28, 2, 26, 3, 27, 3, 27, 2, 28, 2, 26,
        11, 19, 11, 19, 23, 43, 23, 38, 11, 19, 11, 19, 10, 46, 10, 18
    };

    private ConnectedTextureLayout() {
    }

    static Grid fusionGrid(String layout) {
        return switch (normalise(layout)) {
            case "horizontal" -> new Grid(4, 1);
            case "vertical" -> new Grid(1, 4);
            case "simple" -> new Grid(4, 4);
            case "compact" -> new Grid(5, 1);
            case "pieced" -> new Grid(5, 1);
            case "overlay" -> new Grid(6, 3);
            default -> new Grid(8, 6);
        };
    }

    static boolean isMultiQuadFusionLayout(String layout) {
        String value = normalise(layout);
        return "pieced".equals(value) || "overlay".equals(value);
    }

    static int fusionTile(String layout, int mask) {
        return switch (normalise(layout)) {
            case "horizontal" -> {
                boolean left = has(mask, LEFT);
                boolean right = has(mask, RIGHT);
                yield left && right ? 2 : left ? 3 : right ? 1 : 0;
            }
            case "vertical" -> {
                boolean top = has(mask, TOP);
                boolean bottom = has(mask, BOTTOM);
                yield top && bottom ? 2 : top ? 3 : bottom ? 1 : 0;
            }
            case "simple" -> simpleTile(mask);
            case "compact" -> compactTile(mask);
            default -> FUSION_FULL[mask & 0xFF];
        };
    }

    static List<Integer> fusionOverlayTiles(int mask) {
        boolean top = has(mask, TOP);
        boolean topRight = has(mask, TOP_RIGHT);
        boolean right = has(mask, RIGHT);
        boolean bottomRight = has(mask, BOTTOM_RIGHT);
        boolean bottom = has(mask, BOTTOM);
        boolean bottomLeft = has(mask, BOTTOM_LEFT);
        boolean left = has(mask, LEFT);
        boolean topLeft = has(mask, TOP_LEFT);

        if (top && right && bottom && left) {
            return List.of(tile(4, 1, 6));
        }
        if (!(top || topRight || right || bottomRight || bottom || bottomLeft || left || topLeft)) {
            return List.of();
        }

        List<Integer> out = new ArrayList<>(4);
        if (!left) {
            if (!top) {
                if (topLeft) out.add(tile(2, 2, 6));
            } else if (!right) out.add(tile(1, 2, 6));
            else if (!bottom) out.add(tile(3, 2, 6));
            else out.add(tile(3, 1, 6));
        }
        if (!top) {
            if (!right) {
                if (topRight) out.add(tile(0, 2, 6));
            } else if (!bottom) out.add(tile(0, 1, 6));
            else if (!left) out.add(tile(3, 0, 6));
            else out.add(tile(4, 0, 6));
        }
        if (!right) {
            if (!bottom) {
                if (bottomRight) out.add(tile(0, 0, 6));
            } else if (!left) out.add(tile(1, 0, 6));
            else if (!top) out.add(tile(5, 0, 6));
            else out.add(tile(5, 1, 6));
        }
        if (!bottom) {
            if (!left) {
                if (bottomLeft) out.add(tile(2, 0, 6));
            } else if (!top) out.add(tile(2, 1, 6));
            else if (!right) out.add(tile(5, 2, 6));
            else out.add(tile(4, 2, 6));
        }
        return List.copyOf(out);
    }

    /**
     * Returns the Fusion pieced-layout tile for one texture-space corner.
     */
    static int fusionPiecedCornerTile(boolean top, boolean left, int mask) {
        boolean vertical = top ? has(mask, TOP) : has(mask, BOTTOM);
        boolean horizontal = left ? has(mask, LEFT) : has(mask, RIGHT);
        boolean diagonal;
        if (top && left) diagonal = has(mask, TOP_LEFT);
        else if (top) diagonal = has(mask, TOP_RIGHT);
        else if (left) diagonal = has(mask, BOTTOM_LEFT);
        else diagonal = has(mask, BOTTOM_RIGHT);

        int code = (horizontal ? 1 : 0) | (vertical ? 2 : 0) | (diagonal ? 4 : 0);
        return switch (code) {
            case 0, 4 -> 0;
            case 1, 5 -> 3;
            case 2, 6 -> 2;
            case 3 -> 4;
            case 7 -> 1;
            default -> 0;
        };
    }

    static int fusionPiecedWholeTile(int mask) {
        boolean top = has(mask, TOP);
        boolean right = has(mask, RIGHT);
        boolean bottom = has(mask, BOTTOM);
        boolean left = has(mask, LEFT);
        if (!(top || right || bottom || left)) return 0;

        boolean allCorners = has(mask, TOP_RIGHT) && has(mask, BOTTOM_RIGHT)
                && has(mask, BOTTOM_LEFT) && has(mask, TOP_LEFT);
        if (top && right && bottom && left && allCorners) return 1;
        if (top && bottom && !right && !left) return 2;
        if (!top && !bottom && right && left) return 3;
        if (top && right && bottom && left
                && !has(mask, TOP_RIGHT) && !has(mask, BOTTOM_RIGHT)
                && !has(mask, BOTTOM_LEFT) && !has(mask, TOP_LEFT)) return 4;
        return -1;
    }

    static Grid createGrid(String type) {
        return switch (normalise(type)) {
            case "horizontal", "horizontal_kryppers", "vertical" -> new Grid(2, 2);
            case "omnidirectional" -> new Grid(8, 8);
            default -> new Grid(4, 4);
        };
    }

    static int createTile(String type, int mask) {
        return switch (normalise(type)) {
            case "horizontal" ->
                    (has(mask, RIGHT) ? 1 : 0) + (has(mask, LEFT) ? 2 : 0);
            case "horizontal_kryppers" -> {
                boolean right = has(mask, RIGHT);
                boolean left = has(mask, LEFT);
                yield !right && !left ? 0 : !right ? 3 : !left ? 2 : 1;
            }
            case "vertical" ->
                    (has(mask, TOP) ? 1 : 0) + (has(mask, BOTTOM) ? 2 : 0);
            case "omnidirectional" -> createOmnidirectional(mask);
            case "cross" ->
                    (has(mask, TOP) ? 1 : 0)
                            + (has(mask, BOTTOM) ? 2 : 0)
                            + (has(mask, LEFT) ? 4 : 0)
                            + (has(mask, RIGHT) ? 8 : 0);
            case "roof" -> createRoof(mask);
            default -> createRectangle(mask);
        };
    }

    private static int simpleTile(int mask) {
        boolean top = has(mask, TOP);
        boolean right = has(mask, RIGHT);
        boolean bottom = has(mask, BOTTOM);
        boolean left = has(mask, LEFT);

        if (!(left || top || right || bottom)) return tile(0, 0, 4);
        if (left && !top && !right && !bottom) return tile(3, 0, 4);
        if (!left && top && !right && !bottom) return tile(3, 1, 4);
        if (!left && !top && right && !bottom) return tile(2, 1, 4);
        if (!left && !top && !right) return tile(2, 0, 4);
        if (left && !top && right && !bottom) return tile(0, 1, 4);
        if (!left && top && !right && bottom) return tile(1, 1, 4);
        if (left && top && !right && !bottom) return tile(3, 3, 4);
        if (!left && top && right && !bottom) return tile(2, 3, 4);
        if (!left && !top && right) return tile(2, 2, 4);
        if (left && !top && !right) return tile(3, 2, 4);
        if (!left) return tile(0, 2, 4);
        if (!top) return tile(1, 2, 4);
        if (!right) return tile(1, 3, 4);
        if (!bottom) return tile(0, 3, 4);
        return tile(1, 0, 4);
    }

    private static int compactTile(int mask) {
        boolean top = has(mask, TOP);
        boolean right = has(mask, RIGHT);
        boolean bottom = has(mask, BOTTOM);
        boolean left = has(mask, LEFT);
        int sides = (top ? 1 : 0) + (right ? 1 : 0) + (bottom ? 1 : 0) + (left ? 1 : 0);

        if (sides <= 1) return 0;
        if (sides == 2) {
            if (left && right) return 3;
            if (top && bottom) return 2;
            return 0;
        }
        if (sides == 3) {
            if (left && right) {
                boolean topLine = top && has(mask, TOP_LEFT) && has(mask, TOP_RIGHT);
                boolean bottomLine = bottom && has(mask, BOTTOM_LEFT) && has(mask, BOTTOM_RIGHT);
                return topLine || bottomLine ? 3 : 0;
            }
            if (top && bottom) {
                boolean leftLine = left && has(mask, TOP_LEFT) && has(mask, BOTTOM_LEFT);
                boolean rightLine = right && has(mask, TOP_RIGHT) && has(mask, BOTTOM_RIGHT);
                return leftLine || rightLine ? 2 : 0;
            }
            return 0;
        }
        boolean allCorners = has(mask, TOP_LEFT) && has(mask, TOP_RIGHT)
                && has(mask, BOTTOM_LEFT) && has(mask, BOTTOM_RIGHT);
        boolean noCorners = !(has(mask, TOP_LEFT) || has(mask, TOP_RIGHT)
                || has(mask, BOTTOM_LEFT) || has(mask, BOTTOM_RIGHT));
        return allCorners ? 1 : noCorners ? 4 : 0;
    }

    private static int createRectangle(int mask) {
        boolean left = has(mask, LEFT);
        boolean right = has(mask, RIGHT);
        boolean top = has(mask, TOP);
        boolean bottom = has(mask, BOTTOM);
        int x = left && right ? 2 : left ? 3 : right ? 1 : 0;
        int y = top && bottom ? 1 : top ? 2 : bottom ? 0 : 3;
        return x + y * 4;
    }

    private static int createOmnidirectional(int mask) {
        boolean top = has(mask, TOP);
        boolean right = has(mask, RIGHT);
        boolean bottom = has(mask, BOTTOM);
        boolean left = has(mask, LEFT);
        boolean topRight = has(mask, TOP_RIGHT);
        boolean bottomRight = has(mask, BOTTOM_RIGHT);
        boolean bottomLeft = has(mask, BOTTOM_LEFT);
        boolean topLeft = has(mask, TOP_LEFT);

        int x = (top ? 1 : 0) + (bottom ? 2 : 0);
        int y = (left ? 1 : 0) + (right ? 2 : 0);
        int borders = (!top ? 1 : 0) + (!bottom ? 1 : 0) + (!left ? 1 : 0) + (!right ? 1 : 0);

        if (borders == 0) {
            if (topRight) x++;
            if (topLeft) x += 2;
            if (bottomRight) y += 2;
            if (bottomLeft) y++;
        } else if (borders == 1) {
            if (!right && (topLeft || bottomLeft)) {
                y = 4;
                x = -1 + (bottomLeft ? 1 : 0) + (topLeft ? 2 : 0);
            } else if (!left && (topRight || bottomRight)) {
                y = 5;
                x = -1 + (bottomRight ? 1 : 0) + (topRight ? 2 : 0);
            } else if (!bottom && (topLeft || topRight)) {
                y = 6;
                x = -1 + (topLeft ? 1 : 0) + (topRight ? 2 : 0);
            } else if (!top && (bottomLeft || bottomRight)) {
                y = 7;
                x = -1 + (bottomLeft ? 1 : 0) + (bottomRight ? 2 : 0);
            }
        } else if (borders == 2) {
            boolean filledCorner = (top && left && topLeft)
                    || (bottom && left && bottomLeft)
                    || (top && right && topRight)
                    || (bottom && right && bottomRight);
            if (filledCorner) x += 3;
        }
        return x + 8 * y;
    }

    private static int createRoof(int mask) {
        boolean top = has(mask, TOP);
        boolean right = has(mask, RIGHT);
        boolean bottom = has(mask, BOTTOM);
        boolean left = has(mask, LEFT);
        boolean topRight = has(mask, TOP_RIGHT);
        boolean bottomRight = has(mask, BOTTOM_RIGHT);
        boolean bottomLeft = has(mask, BOTTOM_LEFT);
        boolean topLeft = has(mask, TOP_LEFT);

        boolean topDrops = bottom && !top && (left || right);
        boolean bottomDrops = !bottom && top && (left || right);
        boolean leftDrops = !left && right && (top || bottom);
        boolean rightDrops = left && !right && (top || bottom);

        if (topDrops) {
            if (leftDrops) return bottomRight ? 0 : 5;
            if (rightDrops) return bottomLeft ? 2 : 5;
            return 1;
        }
        if (bottomDrops) {
            if (leftDrops) return topRight ? 8 : 5;
            if (rightDrops) return topLeft ? 10 : 5;
            return 9;
        }
        if (leftDrops) return 4;
        if (rightDrops) return 6;
        if (!top || !bottom || !left || !right) return 5;

        if (bottomLeft && topRight) {
            if (topLeft && !bottomRight) return 12;
            if (bottomRight && !topLeft) return 15;
            if (!bottomRight && !topLeft) return 7;
        }
        if (bottomRight && topLeft) {
            if (topRight && !bottomLeft) return 13;
            if (bottomLeft && !topRight) return 14;
            if (!bottomLeft && !topRight) return 11;
        }
        return 5;
    }

    private static boolean has(int mask, int bit) {
        return (mask & bit) != 0;
    }

    private static int tile(int x, int y, int width) {
        return x + y * width;
    }

    private static String normalise(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    record Grid(int width, int height) {
    }
}
