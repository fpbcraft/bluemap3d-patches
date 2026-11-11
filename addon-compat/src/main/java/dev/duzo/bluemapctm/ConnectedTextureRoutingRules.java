package dev.duzo.bluemapctm;

final class ConnectedTextureRoutingRules {

    private ConnectedTextureRoutingRules() {
    }


    /**
     * Locometal windows use cutout textures on cube-column geometry. Rendering them as
     * opaque neighbor-culled cubes removes visible window faces, especially when
     * adjacent to locomotive blocks. Applies to every Railways palette/window style.
     */
    static boolean keepsTransparentWindowFaces(String blockId) {
        if (blockId == null || !blockId.startsWith("railways:")) return false;
        String path = blockId.substring("railways:".length());
        return path.endsWith("_pane_locometal_window");
    }

    static boolean shouldRoute(String blockId) {
        return !"create:metal_girder".equals(blockId);
    }
}
