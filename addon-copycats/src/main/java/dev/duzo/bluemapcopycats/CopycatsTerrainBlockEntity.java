package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity;
import de.bluecolored.bluenbt.NBTName;

/**
 * BlueMap-side DTO for Copycats+/Create: Connected copycat block entities.
 *
 * <p>BlueNBT decodes nested compounds into Map-like objects when retained as Object, which
 * lets this compatibility layer support both Create's single Material compound and
 * Copycats+' material_data map without linking to either mod's implementation classes.
 */
public final class CopycatsTerrainBlockEntity extends MCABlockEntity {

    @NBTName("Material")
    private Object material;

    @NBTName("material_data")
    private Object materialData;

    public CopycatsTerrainBlockEntity() {
    }

    public Object material() {
        return material;
    }

    public Object materialData() {
        return materialData;
    }
}
