package dev.duzo.bluemaptrafficcraft;

import de.bluecolored.bluenbt.NBTName;

/** Minimal view of TrafficCraft's compressed world/data/trafficcraft_signs/*.nbt files. */
final class TrafficCraftSignTextureFile {

    @NBTName("Data")
    private byte[] data;

    @NBTName("Width")
    private short width;

    @NBTName("Height")
    private short height;

    @NBTName("Shape")
    private int shape;

    public TrafficCraftSignTextureFile() {
    }

    byte[] data() {
        return data;
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int shape() {
        return shape;
    }
}
