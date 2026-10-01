package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

/** Presents a copied material state to BlueMap's biome/tint calculator at the real position. */
final class MaterialStateBlock extends BlockNeighborhood {

    private final BlockState materialState;

    MaterialStateBlock(BlockNeighborhood source, BlockState materialState) {
        super(source, source.getResourcePack(), source.getRenderSettings(), source.getDimensionType());
        this.materialState = materialState;
        copyFrom(source);
    }

    @Override
    public BlockState getBlockState() {
        return materialState;
    }

    @Override
    public void set(int x, int y, int z) {
        throw new UnsupportedOperationException("MaterialStateBlock is pinned");
    }
}
