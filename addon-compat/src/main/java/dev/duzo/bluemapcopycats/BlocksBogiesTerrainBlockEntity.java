package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity;
import de.bluecolored.bluenbt.NBTName;
import java.util.Map;

/** Reads Blocks and Bogies' Create BogeyData.BogeyStyle for stationary model selection. */
public final class BlocksBogiesTerrainBlockEntity extends MCABlockEntity {
    @NBTName("BogeyData")
    private Object bogeyData;

    public BlocksBogiesTerrainBlockEntity() {}

    public String style() {
        if (!(bogeyData instanceof Map<?, ?> values)) return null;
        Object raw = values.get("BogeyStyle");
        return raw instanceof String style && !style.isBlank() ? style : null;
    }
}
