package dev.duzo.bluemap3d.bake;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

/**
 * Position and local-block access for model features whose appearance depends on neighbours.
 *
 * <p>The lookup is intentionally limited to block states. It works for moving contraption
 * snapshots as well as ordinary volumes without exposing a Minecraft Level to the baker.
 */
public record BlockRenderContext(
        BlockState state,
        CompoundTag blockEntityData,
        int x,
        int y,
        int z,
        BlockLookup blocks) {

    public BlockRenderContext {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(blocks, "blocks");
    }

    public BlockState stateAtOffset(int dx, int dy, int dz) {
        return blocks.stateAt(x + dx, y + dy, z + dz);
    }

    public BlockRenderContext withState(BlockState replacement) {
        return new BlockRenderContext(replacement, blockEntityData, x, y, z, blocks);
    }

    @FunctionalInterface
    public interface BlockLookup {
        BlockState stateAt(int x, int y, int z);
    }
}
