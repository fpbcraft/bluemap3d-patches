package dev.duzo.bluemapctm;

import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.world.BlockEntity;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import de.bluecolored.bluemap.core.world.block.ExtendedBlock;

import java.util.Map;
import dev.duzo.bluemapcopycats.CopycatsTerrainBlockEntity;
import dev.duzo.bluemapcopycats.CopycatsTrace;

/**
 * Applies the same pre-baked Fusion/Create CT resources to materials carried by
 * procedural copycats as the ordinary CT renderer applies to vanilla block models.
 * No allowlist of material mods or copycat shapes is required.
 */
public final class CopiedMaterialConnectedTextures {
    private static final boolean TRACE_NEIGHBORS =
            Boolean.getBoolean("bluemap.copycats.trace.neighbors");
    private final ResourcePack resources;
    private final ConnectedTextureResourceExtension extension;

    public CopiedMaterialConnectedTextures(ResourcePack resources) {
        this.resources = resources;
        this.extension = resources.getResourcePackExtension(ConnectedTextureResourceExtension.TYPE);
    }

    public Appearance resolve(
            ResourcePath<Texture> source, BlockState material,
            Direction face, BlockNeighborhood owner) {
        return resolve(source, material, face, owner, null);
    }

    /**
     * Sample CT at the actual 8px byte-panel cell occupied by this quad.
     * A single world block can contain four independently textured panel
     * quadrants; comparing only the enclosing block cannot reproduce Create.
     */
    public Appearance resolve(
            ResourcePath<Texture> source, BlockState material,
            Direction face, BlockNeighborhood owner, float[] quadPositions) {
        Appearance plain = new Appearance(source, 0f, 0f, 1f, 1f);
        if (extension == null || source == null || material == null || face == null) {
            if (CopycatsTrace.enabled(owner)) CopycatsTrace.log(owner, "CT", "material=" + material + " face=" + face
                    + " source=" + source + " reason="
                    + (extension == null ? "missing-extension" : "missing-input"));
            return plain;
        }
        String id = source.getFormatted();
        var fusion = extension.fusionSpec(id);
        var create = extension.createSpec(id, material.getFormatted(), material.getProperties(), face);
        if (fusion == null && create == null) {
            if (CopycatsTrace.enabled(owner)) CopycatsTrace.log(owner, "CT", "material=" + material.getFormatted()
                    + " face=" + face + " base=" + id + " mode=none reason=no-ct-spec");
            return plain;
        }

        int mask = "copycats:copycat_byte_panel".equals(owner.getBlockState().getFormatted())
                && quadPositions != null && quadPositions.length == 12
                ? bytePanelMask(material, face, owner, fusion, create, quadPositions)
                : mask(material, face, owner, fusion, create);
        if (fusion != null) {
            ResourcePath<Texture> path = extension.fusionMaterial(id, fusion, mask);
            int tile = ConnectedTextureLayout.fusionTile(fusion.layout(), mask);
            boolean found = available(path);
            if (CopycatsTrace.enabled(owner)) CopycatsTrace.log(owner, "CT", "material=" + material.getFormatted()
                    + " face=" + face + " mode=fusion layout=" + fusion.layout()
                    + " base=" + id + " mask=0x" + Integer.toHexString(mask)
                    + " tile=" + tile + " destination=" + path + " available=" + found);
            if (!found) return plain;
            if (ConnectedTextureLayout.isMultiQuadFusionLayout(fusion.layout()))
                return new Appearance(path, 0f, 0f, 1f, 1f);
            return tiled(path, fusion.grid(), tile);
        }

        mask = constrainCorners(mask);
        String sheet = create.sheetTexture(owner.getX(), owner.getY(), owner.getZ());
        int tile = ConnectedTextureLayout.createTile(create.type(), mask);
        ResourcePath<Texture> path = extension.createMaterial(sheet, create.type(), tile);
        boolean found = available(path);
        if (CopycatsTrace.enabled(owner)) CopycatsTrace.log(owner, "CT", "material=" + material.getFormatted()
                + " face=" + face + " mode=create type=" + create.type()
                + " base=" + id + " sheet=" + sheet
                + " mask=0x" + Integer.toHexString(mask)
                + " tile=" + tile + " destination=" + path + " available=" + found);
        return found ? tiled(path, ConnectedTextureLayout.createGrid(create.type()), tile) : plain;
    }

    private boolean available(ResourcePath<Texture> path) {
        return path != null && resources.getTextures().containsKey(path);
    }

    static Appearance tiled(
            ResourcePath<Texture> path, ConnectedTextureLayout.Grid grid, int tile) {
        float[] uv = tileUvs(grid, tile);
        return new Appearance(path, uv[0], uv[1], uv[2], uv[3]);
    }

    // Keep pure coordinate calculation testable without loading BlueMap runtime classes.
    static float[] tileUvs(ConnectedTextureLayout.Grid grid, int tile) {
        int x = Math.floorMod(tile, grid.width());
        int y = Math.floorDiv(tile, grid.width());
        return new float[]{
                x / (float) grid.width(), y / (float) grid.height(),
                (x + 1f) / grid.width(), (y + 1f) / grid.height()
        };
    }

    static int constrainCorners(int mask) {
        for (int c = 1; c < 8; c += 2) {
            int previous = (c + 7) % 8;
            int next = (c + 1) % 8;
            if ((mask & (1 << previous)) == 0 || (mask & (1 << next)) == 0)
                mask &= ~(1 << c);
        }
        return mask;
    }

    private int bytePanelMask(
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
                    cx+dx+faceOffset(face,0),
                    cy+dy+faceOffset(face,1),
                    cz+dz+faceOffset(face,2));
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
    private CellNeighbor bytePanelCell(BlockNeighborhood owner, int x, int y, int z) {
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
            var material = dev.duzo.bluemapcopycats.CopycatsMaterialResolver
                    .materialFor(data, part);
            BlockState selected = material == null ? null : material.asBlockState();
            return new CellNeighbor(selected, selected != null
                    && selected.getProperties().isOccluding());
        }
        // Non-byte-panel materials retain their normal full-block CT behavior.
        // A copied shape is resolved through its existing material-NBT path.
        BlockState effective = effectiveMaterial(neighbour, state);
        return new CellNeighbor(effective,
                neighbour.getProperties().isOccluding());
    }

    private record CellNeighbor(BlockState state, boolean occludes) {}

    private int mask(BlockState material, Direction face, BlockNeighborhood block,
            ConnectedTextureResourceExtension.FusionSpec fusion,
            CreateConnectedTextures.Spec create) {
        // The axes are stable in the world (UV rotation remains the responsibility of
        // the shape renderer). CT is evaluated using the *copied material*, not the
        // copycat wrapper's block ID.
        int[] up = switch (face) {
            case UP, DOWN -> new int[]{0, 0, -1};
            default -> new int[]{0, 1, 0};
        };
        int[] right = switch (face) {
            case NORTH -> new int[]{-1, 0, 0};
            case SOUTH -> new int[]{1, 0, 0};
            case UP -> new int[]{1, 0, 0};
            case DOWN -> new int[]{-1, 0, 0};
            case EAST -> new int[]{0, 0, -1};
            case WEST -> new int[]{0, 0, 1};
        };
        int mask = 0;
        String[] dirs = {"top", "top_right", "right", "bottom_right",
                         "bottom", "bottom_left", "left", "top_left"};
        for (int i = 0; i < 8; i++) {
            int vertical = i == 0 || i == 1 || i == 7 ? 1
                    : i >= 3 && i <= 5 ? -1 : 0;
            int horizontal = i >= 1 && i <= 3 ? 1
                    : i >= 5 && i <= 7 ? -1 : 0;
            int dx = up[0] * vertical + right[0] * horizontal;
            int dy = up[1] * vertical + right[1] * horizontal;
            int dz = up[2] * vertical + right[2] * horizontal;
            ExtendedBlock neighbour = block.getNeighborBlock(dx, dy, dz);
            BlockState other = effectiveMaterial(neighbour, material);
            ExtendedBlock front = block.getNeighborBlock(
                    dx + faceOffset(face, 0),
                    dy + faceOffset(face, 1),
                    dz + faceOffset(face, 2));
            BlockState inFront = effectiveMaterial(front, material);

            boolean connects;
            if (fusion != null) {
                connects = fusion.predicate().test(
                        material, other, inFront, front.getProperties().isOccluding(),
                        face, dirs[i]);
            } else {
                // Semantic material equality also connects different copycat shapes
                // and ordinary blocks copied from the same Railways/Create material.
                connects = !front.getProperties().isOccluding()
                        && material.getFormatted().equals(other.getFormatted())
                        && material.getProperties().equals(other.getProperties());
            }
            // For persistent static seams, report the four cardinal
            // directions that *failed* to join; the existing CT trace reports
            // only the resulting mask, not why an adjacent copied panel missed.
            // Keep this opt-in because BlueMap calls the shader per tiny quad.
            if (!connects && create != null && TRACE_NEIGHBORS
                    && (i % 2 == 0) && CopycatsTrace.enabled(block)) {
                CopycatsTrace.log(block, "CT-EDGE",
                        "face=" + face + " dir=" + dirs[i]
                        + " copied=" + material.getFormatted()
                        + " sourceNeighbor=" + neighbour.getBlockState().getFormatted()
                        + " resolvedNeighbor=" + (other == null ? "<null>"
                                : other.getFormatted())
                        + " hasNeighborEntity=" + (neighbour.getBlockEntity() != null)
                        + " frontOccludes=" + front.getProperties().isOccluding()
                        + " frontBlock=" + front.getBlockState().getFormatted());
            }
            if (connects) mask |= 1 << i;
        }
        return mask;
    }

    private static int faceOffset(Direction d, int axis) {
        return switch (axis) {
            case 0 -> d == Direction.EAST ? 1 : d == Direction.WEST ? -1 : 0;
            case 1 -> d == Direction.UP ? 1 : d == Direction.DOWN ? -1 : 0;
            default -> d == Direction.SOUTH ? 1 : d == Direction.NORTH ? -1 : 0;
        };
    }

    /**
     * Resolve neighbor copycats through their copied NBT. For multipart wrappers,
     * prefer the part using the requested material; this allows all Copycats+
     * variants with shared material_data to participate in a CT neighbourhood.
     * A material not carried by the neighbour never connects.
     */
    private static BlockState effectiveMaterial(ExtendedBlock block, BlockState requested) {
        BlockState original = block.getBlockState();
        String id = original.getFormatted();
        if (!(id.startsWith("copycats:")
                || id.startsWith("create:copycat")
                || id.startsWith("create_connected:copycat")
                || id.startsWith("railways:copycat"))) {
            return original;
        }

        CopycatsTerrainBlockEntity data = CopycatsTerrainBlockEntity.from(block.getBlockEntity());
        if (data == null) return original;
        BlockState direct = materialState(data.material());
        if (requested.equals(direct)) return direct;
        if (data.materialData() instanceof Map<?, ?> parts) {
            for (Object value : parts.values()) {
                if (!(value instanceof Map<?, ?> storage)) continue;
                Object raw = storage.containsKey("material")
                        ? storage.get("material") : storage.get("Material");
                BlockState part = materialState(raw);
                if (requested.equals(part)) return part;
            }
        }
        return direct != null ? direct : original;
    }

    private static BlockState materialState(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) return null;
        Object name = value.containsKey("Name") ? value.get("Name") : value.get("name");
        if (!(name instanceof String id) || id.isBlank()) return null;
        Object fields = value.containsKey("Properties")
                ? value.get("Properties") : value.get("properties");
        Map<String, String> properties = fields instanceof Map<?, ?> props
                ? props.entrySet().stream()
                  .filter(e -> e.getKey() instanceof String && e.getValue() instanceof String)
                  .collect(java.util.stream.Collectors.toMap(
                      e -> (String) e.getKey(), e -> (String) e.getValue()))
                : Map.of();
        return new BlockState(id, properties);
    }

    public record Appearance(ResourcePath<Texture> texture, float u0, float v0, float u1, float v1) {}
}
