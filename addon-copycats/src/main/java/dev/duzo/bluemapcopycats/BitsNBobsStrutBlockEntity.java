package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity;
import de.bluecolored.bluenbt.NBTName;

/** BlueMap-side DTO for Bits and Bobs / Strut Your Stuff girder endpoints. */
public final class BitsNBobsStrutBlockEntity extends MCABlockEntity {

    @NBTName("Connections")
    private Object connections;

    public BitsNBobsStrutBlockEntity() {
    }

    public Object connections() {
        return connections;
    }
}
