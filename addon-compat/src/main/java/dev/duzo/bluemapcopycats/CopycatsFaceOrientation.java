package dev.duzo.bluemapcopycats;

/** Pure normal calculation shared by BlueMap's static panel and step renderer. */
final class CopycatsFaceOrientation {
    private CopycatsFaceOrientation() {}

    static String of(float[] p, String fallback) {
        if (p == null || p.length < 9) return fallback;
        float ax = p[3]-p[0], ay=p[4]-p[1], az=p[5]-p[2];
        float bx = p[6]-p[0], by=p[7]-p[1], bz=p[8]-p[2];
        float x=ay*bz-az*by, y=az*bx-ax*bz, z=ax*by-ay*bx;
        float magnitude=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));
        if (magnitude<0.0001f) return fallback;
        if (Math.abs(x)==magnitude) return x>0 ? "EAST" : "WEST";
        if (Math.abs(y)==magnitude) return y>0 ? "UP" : "DOWN";
        return z>0 ? "SOUTH" : "NORTH";
    }
}
