package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.map.hires.TileModel;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

/** Writes resolved Copycats quads into BlueMap's tile-model triangle buffer. */
final class CopycatsQuadEmitter {

    private final CopycatsAppearanceResolver appearanceResolver;

    CopycatsQuadEmitter(CopycatsAppearanceResolver appearanceResolver) {
        this.appearanceResolver = appearanceResolver;
    }

    boolean emit(
            float[] positions,
            Direction face,
            CopycatsMaterial material,
            BlockNeighborhood block,
            TileModelView tileModel) {
        CopycatsAppearanceResolver.Appearance appearance =
                appearanceResolver.resolve(material, face, block);
        if (appearance == null) return false;

        float[] p = positions;
        tileModel.initialize();
        tileModel.add(2);
        TileModel target = tileModel.getTileModel();
        int f1 = tileModel.getStart();
        int f2 = f1 + 1;

        target.setPositions(f1,
                p[0]/16f,p[1]/16f,p[2]/16f,
                p[3]/16f,p[4]/16f,p[5]/16f,
                p[6]/16f,p[7]/16f,p[8]/16f);
        target.setPositions(f2,
                p[0]/16f,p[1]/16f,p[2]/16f,
                p[6]/16f,p[7]/16f,p[8]/16f,
                p[9]/16f,p[10]/16f,p[11]/16f);

        // A copycat quad can occupy only a fraction of the 16x16 block face.
        // Mapping the *entire* connected tile onto every 8x8 byte repeats its
        // border pattern at internal seams. Project vertex UVs in block space so
        // adjoining parts sample adjoining portions of the same CT tile.
        float[] uv = projectedUvs(p, appearance.u0(), appearance.v0(),
                appearance.u1(), appearance.v1());
        target.setUvs(f1, uv[0],uv[1], uv[2],uv[3], uv[4],uv[5]);
        target.setUvs(f2, uv[0],uv[1], uv[4],uv[5], uv[6],uv[7]);
        target.setMaterialIndex(f1, appearance.textureIndex());
        target.setMaterialIndex(f2, appearance.textureIndex());

        Color tint = appearance.tint();
        target.setColor(f1, tint.r, tint.g, tint.b);
        target.setColor(f2, tint.r, tint.g, tint.b);

        LightData light = block.getLightData();
        target.setSunlight(f1, light.getSkyLight());
        target.setSunlight(f2, light.getSkyLight());
        target.setBlocklight(f1, light.getBlockLight());
        target.setBlocklight(f2, light.getBlockLight());
        target.setAOs(f1, 1f,1f,1f);
        target.setAOs(f2, 1f,1f,1f);
        return true;
    }

    /** A transformed copycat quad must use its actual world-facing normal
     * when sampling the material model and evaluating its CT neighbours.
     * The original unrotated face label is wrong for any rotated panel/step. */
    static Direction physicalFace(float[] p, Direction fallback) {
        if (p == null || p.length < 9) return fallback;
        float ax = p[3]-p[0], ay=p[4]-p[1], az=p[5]-p[2];
        float bx = p[6]-p[0], by=p[7]-p[1], bz=p[8]-p[2];
        float x=ay*bz-az*by, y=az*bx-ax*bz, z=ax*by-ay*bx;
        float magnitude=Math.max(Math.abs(x),Math.max(Math.abs(y),Math.abs(z)));
        if (magnitude<0.0001f) return fallback;
        if (Math.abs(x)==magnitude) return x>0 ? Direction.EAST : Direction.WEST;
        if (Math.abs(y)==magnitude) return y>0 ? Direction.UP : Direction.DOWN;
        return z>0 ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * Map a quad's vertices to their actual positions on the full 16-pixel
     * material face. The CT atlas tile remains the same, but a half-panel
     * samples only the corresponding half of that tile.
     *
     * For non-axis-aligned quads (e.g. slopes), retain the previous UV
     * behavior until surface-local coordinates can be derived reliably.
     */
    static float[] projectedUvs(float[] positions,
            float left, float top, float right, float bottom) {
        float minX=Float.POSITIVE_INFINITY, maxX=Float.NEGATIVE_INFINITY;
        float minY=Float.POSITIVE_INFINITY, maxY=Float.NEGATIVE_INFINITY;
        float minZ=Float.POSITIVE_INFINITY, maxZ=Float.NEGATIVE_INFINITY;
        for (int i=0; i<4; i++) {
            minX=Math.min(minX,positions[i*3]); maxX=Math.max(maxX,positions[i*3]);
            minY=Math.min(minY,positions[i*3+1]); maxY=Math.max(maxY,positions[i*3+1]);
            minZ=Math.min(minZ,positions[i*3+2]); maxZ=Math.max(maxZ,positions[i*3+2]);
        }
        boolean xFixed=maxX-minX<0.001f;
        boolean yFixed=maxY-minY<0.001f;
        boolean zFixed=maxZ-minZ<0.001f;
        if (!xFixed && !yFixed && !zFixed) {
            return new float[]{left,bottom, right,bottom, right,top, left,top};
        }

        float ax=positions[3]-positions[0];
        float ay=positions[4]-positions[1];
        float az=positions[5]-positions[2];
        float bx=positions[6]-positions[0];
        float by=positions[7]-positions[1];
        float bz=positions[8]-positions[2];
        float nx=ay*bz-az*by;
        float ny=az*bx-ax*bz;
        float nz=ax*by-ay*bx;
        float[] uv = new float[8];
        for (int i=0; i<4; i++) {
            float x=positions[i*3]/16f;
            float y=positions[i*3+1]/16f;
            float z=positions[i*3+2]/16f;
            float u,v;
            if (xFixed) {
                u=nx>0f?1f-z:z;
                v=1f-y;
            } else if (yFixed) {
                u=x;
                v=ny>0f?z:1f-z;
            } else {
                u=nz>0f?x:1f-x;
                v=1f-y;
            }
            uv[i*2]=left+(right-left)*u;
            uv[i*2+1]=top+(bottom-top)*v;
        }
        return uv;
    }
}
