package dev.duzo.bluemapctm;

import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import de.bluecolored.bluemap.core.world.block.ExtendedBlock;
import dev.duzo.bluemapcopycats.CopycatsTerrainBlockEntity;
import java.util.Map;

/**
 * Runtime-only subblock CT policy for Copycats+ byte panels. This lives
 * outside CopiedMaterialConnectedTextures so its coordinate-only test helpers
 * do not require BlueMap runtime classes on the JUnit classpath.
 */
final class BytePanelConnectedTextureMask {
    private BytePanelConnectedTextureMask() {}

    static int mask(
            BlockState material, Direction face, BlockNeighborhood owner,
            ConnectedTextureResourceExtension.FusionSpec fusion,
            CreateConnectedTextures.Spec create, float[] quad) {
        // The quad is already transformed into the owner block's local pixel
        // coordinates. Its center identifies the occupied 8px part.
        float x = 0, y = 0, z = 0;
        for (int i = 0; i < 4; i++) {
            x += quad[3*i];
            y += quad[3*i+1];
            z += quad[3*i+2];
        }
        int cx = BytePanelCells.cellOfPixel(x / 4f);
        int cy = BytePanelCells.cellOfPixel(y / 4f);
        int cz = BytePanelCells.cellOfPixel(z / 4f);

        int[] up = switch (face) {
            case UP, DOWN -> new int[]{0,0,-1};
            default -> new int[]{0,1,0};
        };
        int[] right = switch (face) {
            case NORTH -> new int[]{-1,0,0};
            case SOUTH, UP -> new int[]{1,0,0};
            case DOWN -> new int[]{-1,0,0};
            case EAST -> new int[]{0,0,-1};
            case WEST -> new int[]{0,0,1};
        };
        String[] dirs = {"top", "top_right", "right", "bottom_right",
                         "bottom", "bottom_left", "left", "top_left"};
        int mask = 0;
        for (int i = 0; i < 8; i++) {
            int v = i == 0 || i == 1 || i == 7 ? 1
                    : i >= 3 && i <= 5 ? -1 : 0;
            int h = i >= 1 && i <= 3 ? 1 : i >= 5 && i <= 7 ? -1 : 0;
            int dx = up[0]*v + right[0]*h;
            int dy = up[1]*v + right[1]*h;
            int dz = up[2]*v + right[2]*h;

            CellNeighbor other = bytePanelCell(owner, cx+dx, cy+dy, cz+dz);
            CellNeighbor front = bytePanelCell(owner,
                    cx+dx+CopiedMaterialConnectedTextures.faceOffset(face,0),
                    cy+dy+CopiedMaterialConnectedTextures.faceOffset(face,1),
                    cz+dz+CopiedMaterialConnectedTextures.faceOffset(face,2));
            boolean connects;
            if (fusion != null) {
                connects = fusion.predicate().test(
                        material, other.state(), front.state(),
                        front.occludes(), face, dirs[i]);
            } else {
                connects = !front.occludes()
                        && material.equals(other.state());
            }
            if (connects) mask |= (1 << i);
        }
        return mask;
    }

    /**
     * Match the *same half* of an adjacent byte panel, rather than finding
     * the requested material somewhere in its whole-block material_data map.
     */
    private static CellNeighbor bytePanelCell(BlockNeighborhood owner, int x, int y, int z) {
        int bx = BytePanelCells.worldBlockOffset(x);
        int by = BytePanelCells.worldBlockOffset(y);
        int bz = BytePanelCells.worldBlockOffset(z);
        ExtendedBlock neighbour = (bx == 0 && by == 0 && bz == 0)
                ? owner : owner.getNeighborBlock(bx, by, bz);
        BlockState state = neighbour.getBlockState();
        String id = state.getFormatted();
        if ("copycats:copycat_byte_panel".equals(id)) {
            String facing = state.getProperties().getOrDefault("facing", "");
            String part = BytePanelCells.partAt(facing,
                    BytePanelCells.localCell(x),
                    BytePanelCells.localCell(y),
                    BytePanelCells.localCell(z));
            if (part == null || !"true".equals(
                    state.getProperties().get(part))) return new CellNeighbor(null, false);
            CopycatsTerrainBlockEntity data = CopycatsTerrainBlockEntity.from(
                    neighbour.getBlockEntity());
            if (data == null) return new CellNeighbor(null, false);
            BlockState selected = null;
            if (data.materialData() instanceof Map<?, ?> storage
                    && storage.get(part) instanceof Map<?, ?> partData) {
                Object raw = partData.containsKey("material")
                        ? partData.get("material") : partData.get("Material");
                selected = CopiedMaterialConnectedTextures.materialState(raw);
            } else {
                selected = CopiedMaterialConnectedTextures.materialState(data.material());
            }
            // One 8x8x3 byte-panel part cannot occlude a whole block face.
            return new CellNeighbor(selected, false);
        }
        // Non-byte-panel materials retain their normal full-block CT behavior.
        // A copied shape is resolved through its existing material-NBT path.
        BlockState effective = CopiedMaterialConnectedTextures.effectiveMaterial(neighbour, state);
        return new CellNeighbor(effective,
                neighbour.getProperties().isOccluding());
    }

    private record CellNeighbor(BlockState state, boolean occludes) {}

}
