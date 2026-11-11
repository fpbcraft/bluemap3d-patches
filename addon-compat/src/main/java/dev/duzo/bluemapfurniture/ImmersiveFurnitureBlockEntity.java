package dev.duzo.bluemapfurniture;

import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity;
import de.bluecolored.bluenbt.NBTName;

/** BlueMap-side view of Immersive Furniture's two furniture block-entity types. */
public final class ImmersiveFurnitureBlockEntity extends MCABlockEntity {

    @NBTName("Furniture")
    private Object furniture;

    @NBTName("FurnitureHash")
    private String furnitureHash;

    @NBTName("SubOffsetX")
    private int subOffsetX = 8;

    @NBTName("SubOffsetY")
    private int subOffsetY = 8;

    @NBTName("SubOffsetZ")
    private int subOffsetZ = 8;

    public ImmersiveFurnitureBlockEntity() {
    }

    Object furniture() {
        return furniture;
    }

    String furnitureHash() {
        return furnitureHash;
    }

    int subOffsetX() {
        return subOffsetX;
    }

    int subOffsetY() {
        return subOffsetY;
    }

    int subOffsetZ() {
        return subOffsetZ;
    }
}
