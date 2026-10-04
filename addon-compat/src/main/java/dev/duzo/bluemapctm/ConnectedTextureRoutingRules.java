package dev.duzo.bluemapctm;

final class ConnectedTextureRoutingRules {

    private ConnectedTextureRoutingRules() {
    }

    static boolean shouldRoute(String blockId) {
        return !"create:metal_girder".equals(blockId);
    }
}
