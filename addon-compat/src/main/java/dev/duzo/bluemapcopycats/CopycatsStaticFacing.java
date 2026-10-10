package dev.duzo.bluemapcopycats;

/**
 * Pure, static-terrain-only Create/Copycats orientation corrections.
 * Moving contraptions use Minecraft's actual voxel shapes instead.
 */
final class CopycatsStaticFacing {
    private CopycatsStaticFacing() {}

    static CopycatsTransform panel(String facing) {
        CopycatsTransform transform = new CopycatsTransform();
        switch (facing) {
            case "down" -> transform.flipY(true);
            case "north" -> transform.rotateX(90).rotateY(180);
            case "south" -> transform.rotateX(270).rotateY(180);
            case "west" -> transform.rotateZ(270).rotateY(180);
            case "east" -> transform.rotateZ(90).rotateY(180);
            default -> { /* up: base slab at y=0 */ }
        }
        return transform;
    }

    static CopycatsTransform verticalStep(String facing) {
        return new CopycatsTransform().rotateY(yRotation(facing) + 180);
    }

    private static int yRotation(String facing) {
        return switch (facing) {
            case "south" -> 0;
            case "west" -> 90;
            case "north" -> 180;
            case "east" -> 270;
            default -> 0;
        };
    }
}
