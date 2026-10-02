package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.world.BlockState;

import java.util.Map;

record CopycatsMaterial(String id, Map<String, String> properties) {

    BlockState asBlockState() {
        return new BlockState(id, properties);
    }
}
