package dev.duzo.bluemapfurniture;

/** Pure transform helpers shared by the BlueMap furniture renderer and its tests. */
final class ImmersiveFurnitureTransform {

    private ImmersiveFurnitureTransform() {
    }

    static void rotateFacing(float[] point, String facing) {
        // Immersive Furniture bakes block models with
        // facing.getOpposite().toYRot(): north=0, east=90, south=180, west=270.
        double degrees = switch (facing) {
            case "east" -> 90D;
            case "south" -> 180D;
            case "west" -> 270D;
            default -> 0D;
        };
        if (degrees == 0D) return;

        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double x = point[0] - 8D;
        double z = point[2] - 8D;
        point[0] = (float) (x * cos - z * sin + 8D);
        point[2] = (float) (x * sin + z * cos + 8D);
    }
}
