package dev.duzo.bluemaptrafficcraft;

import de.bluecolored.bluemap.core.world.mca.blockentity.MCABlockEntity;
import de.bluecolored.bluenbt.NBTName;

/**
 * Minimal BlueMap-side view of TrafficCraft's paintable block entities.
 *
 * <p>TrafficCraft stores PaintColor#getIndex() in the integer field "color".
 * -1 means "use the block's default client color"; 0..15 are the paint palette.
 */
public final class TrafficCraftColorBlockEntity extends MCABlockEntity {

    @NBTName("color")
    private int color = -1;

    @NBTName("SignTexture")
    private String signTexture;

    public TrafficCraftColorBlockEntity() {
    }

    public int color() {
        return color;
    }

    public String signTexture() {
        return signTexture;
    }
}
