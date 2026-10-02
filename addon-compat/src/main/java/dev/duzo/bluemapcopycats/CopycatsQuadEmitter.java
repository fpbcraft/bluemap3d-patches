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

        target.setUvs(f1, 0f,1f, 1f,1f, 1f,0f);
        target.setUvs(f2, 0f,1f, 1f,0f, 0f,0f);
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
}
