package dev.duzo.bluemapctm;

import java.util.Map;

final class MetalGirderConnectedTexturePolicy {

    private MetalGirderConnectedTexturePolicy() {
    }

    static boolean useConnectedRenderer(String blockId, Map<String, String> properties) {
        if (!"create:metal_girder".equals(blockId)) return true;

        // Create's GirderCTBehaviour only supplies a sprite shift for the vertical pole:
        // both horizontal axes must be disabled. Other girder forms must use the stock
        // resource-model renderer.
        return !"true".equals(properties.get("x"))
                && !"true".equals(properties.get("z"));
    }
}
