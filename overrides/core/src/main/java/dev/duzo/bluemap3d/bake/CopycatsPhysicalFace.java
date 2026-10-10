package dev.duzo.bluemap3d.bake;

/**
 * Recover a cardinal normal for the geometric faces of a rotated copycat model.
 * Deliberately independent of Minecraft classes so face mappings can be tested
 * on a plain JVM; angled slopes preserve the source material's orientation.
 */
final class CopycatsPhysicalFace {
    private CopycatsPhysicalFace() {}

    static String of(float[] vertices, String fallback) {
        if (vertices == null || vertices.length < 9) return fallback;
        float ax=vertices[3]-vertices[0], ay=vertices[4]-vertices[1],
                az=vertices[5]-vertices[2];
        float bx=vertices[6]-vertices[0], by=vertices[7]-vertices[1],
                bz=vertices[8]-vertices[2];
        float x=ay*bz-az*by, y=az*bx-ax*bz, z=ax*by-ay*bx;
        float largest=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));
        if (largest<0.0001f) return fallback;
        int active = (Math.abs(x)>largest*.001f?1:0)
                + (Math.abs(y)>largest*.001f?1:0)
                + (Math.abs(z)>largest*.001f?1:0);
        if (active!=1) return fallback;
        if (Math.abs(x)==largest) return x>0 ? "EAST" : "WEST";
        if (Math.abs(y)==largest) return y>0 ? "UP" : "DOWN";
        return z>0 ? "SOUTH" : "NORTH";
    }
}
