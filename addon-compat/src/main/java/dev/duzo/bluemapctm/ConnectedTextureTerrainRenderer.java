package dev.duzo.bluemapctm;

import com.flowpowered.math.TrigMath;
import com.flowpowered.math.vector.Vector3f;
import com.flowpowered.math.vector.Vector3i;
import com.flowpowered.math.vector.Vector4f;
import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.map.hires.RenderSettings;
import de.bluecolored.bluemap.core.map.hires.TileModel;
import de.bluecolored.bluemap.core.map.hires.TileModelView;
import de.bluecolored.bluemap.core.map.hires.block.BlockRenderer;
import de.bluecolored.bluemap.core.map.hires.block.BlockRendererType;
import de.bluecolored.bluemap.core.map.hires.block.ResourceModelRenderer;
import de.bluecolored.bluemap.core.resources.BlockColorCalculatorFactory;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Element;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Face;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.util.Key;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.util.math.MatrixM4f;
import de.bluecolored.bluemap.core.util.math.VectorM2f;
import de.bluecolored.bluemap.core.util.math.VectorM3f;
import de.bluecolored.bluemap.core.world.BlockProperties;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.LightData;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import de.bluecolored.bluemap.core.world.block.ExtendedBlock;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BlueMap 5.7 resource-model renderer with neighbour-sensitive material selection.
 *
 * <p>The geometry, transforms, lighting, AO, tint and culling intentionally mirror
 * BlueMap's ResourceModelRenderer. Only texture selection is extended.
 */
@SuppressWarnings("DuplicatedCode")
public final class ConnectedTextureTerrainRenderer implements BlockRenderer {

    static final Key RENDERER_KEY = new Key("bluemap_ctm", "connected");
    private static final float BLOCK_SCALE = 1f / 16f;

    static void register() {
        if (BlockRendererType.REGISTRY.get(RENDERER_KEY) != null) return;
        BlockRendererType.REGISTRY.register(new BlockRendererType.Impl(
                RENDERER_KEY,
                ConnectedTextureTerrainRenderer::new));
    }

    private final ResourcePack resourcePack;
    private final TextureGallery textureGallery;
    private final RenderSettings renderSettings;
    private final BlockColorCalculatorFactory.BlockColorCalculator blockColorCalculator;
    private final ConnectedTextureResourceExtension connectedTextures;
    private final ResourceModelRenderer standardRenderer;

    private final VectorM3f[] corners = new VectorM3f[8];
    private final VectorM2f[] rawUvs = new VectorM2f[4];
    private final VectorM2f[] uvs = new VectorM2f[4];
    private final Color tintColor = new Color();
    private final Color mapColor = new Color();
    private final Map<BlockState, Set<String>> createSpecCache = new ConcurrentHashMap<>();

    private BlockNeighborhood block;
    private Variant variant;
    private Model modelResource;
    private TileModelView blockModel;
    private Color blockColor;
    private float blockColorOpacity;

    public ConnectedTextureTerrainRenderer(
            ResourcePack resourcePack,
            TextureGallery textureGallery,
            RenderSettings renderSettings) {
        this.resourcePack = resourcePack;
        this.textureGallery = textureGallery;
        this.renderSettings = renderSettings;
        this.blockColorCalculator = resourcePack.getColorCalculatorFactory().createCalculator();
        this.connectedTextures =
                resourcePack.getResourcePackExtension(ConnectedTextureResourceExtension.TYPE);
        this.standardRenderer = new ResourceModelRenderer(resourcePack, textureGallery, renderSettings);

        for (int i = 0; i < corners.length; i++) corners[i] = new VectorM3f(0, 0, 0);
        for (int i = 0; i < rawUvs.length; i++) rawUvs[i] = new VectorM2f(0, 0);
    }

    @Override
    public void render(
            BlockNeighborhood block,
            Variant ignoredVariant,
            TileModelView blockModel,
            Color color) {
        this.block = block;
        this.blockModel = blockModel;
        this.blockColor = color;
        this.blockColorOpacity = 0f;
        this.tintColor.set(0, 0, 0, -1, true);

        var original =
                ConnectedTextureTerrainDispatch.original(block.getBlockState().getFormatted());
        if (original == null || connectedTextures == null) return;

        // Create only applies GirderCTBehaviour to the vertical pole form. Routing the
        // horizontal/cross forms through the generic CT renderer changes how their
        // cutout girder faces are emitted and produces the transparency/culling regression
        // visible after CT compatibility is enabled. Preserve BlueMap's stock renderer
        // for those states and keep CT handling only for the actual connected pole.
        if (!MetalGirderConnectedTexturePolicy.useConnectedRenderer(
                block.getBlockState().getFormatted(),
                block.getBlockState().getProperties())) {
            int standardStart = blockModel.getStart();
            original.forEach(
                    block.getBlockState(),
                    block.getX(),
                    block.getY(),
                    block.getZ(),
                    variant -> standardRenderer.render(
                            block, variant, blockModel.initialize(), color));
            blockModel.initialize(standardStart);
            return;
        }

        int start = blockModel.getStart();
        original.forEach(
                block.getBlockState(),
                block.getX(),
                block.getY(),
                block.getZ(),
                this::renderVariant);

        blockModel.initialize(start);
        if (blockModel.getSize() > 0 && color.a > 0) {
            color.flatten().straight();
            color.a = blockColorOpacity;
        }
    }

    private void renderVariant(Variant variant) {
        this.variant = variant;
        this.modelResource = variant.getModel().getResource(resourcePack::getModel);
        if (modelResource == null) return;

        int modelStart = blockModel.getStart();

        Element[] elements = modelResource.getElements();
        if (elements != null) {
            for (Element element : elements) {
                if (element != null) buildModelElementResource(element, blockModel.initialize());
            }
        }

        blockModel.initialize(modelStart);
        if (variant.isTransformed()) {
            blockModel.transform(variant.getTransformMatrix());
        }

        if (block.getProperties().isRandomOffset()) {
            float dx = (hashToFloat(block.getX(), block.getZ(), 123984) - 0.5f) * 0.75f;
            float dz = (hashToFloat(block.getX(), block.getZ(), 345542) - 0.5f) * 0.75f;
            blockModel.translate(dx, 0, dz);
        }
    }

    private final MatrixM4f modelElementTransform = new MatrixM4f();

    private void buildModelElementResource(Element element, TileModelView blockModel) {
        Vector3f from = element.getFrom();
        Vector3f to = element.getTo();

        float minX = Math.min(from.getX(), to.getX());
        float minY = Math.min(from.getY(), to.getY());
        float minZ = Math.min(from.getZ(), to.getZ());
        float maxX = Math.max(from.getX(), to.getX());
        float maxY = Math.max(from.getY(), to.getY());
        float maxZ = Math.max(from.getZ(), to.getZ());

        VectorM3f[] c = corners;
        c[0].x = minX; c[0].y = minY; c[0].z = minZ;
        c[1].x = minX; c[1].y = minY; c[1].z = maxZ;
        c[2].x = maxX; c[2].y = minY; c[2].z = minZ;
        c[3].x = maxX; c[3].y = minY; c[3].z = maxZ;
        c[4].x = minX; c[4].y = maxY; c[4].z = minZ;
        c[5].x = minX; c[5].y = maxY; c[5].z = maxZ;
        c[6].x = maxX; c[6].y = maxY; c[6].z = minZ;
        c[7].x = maxX; c[7].y = maxY; c[7].z = maxZ;

        int modelStart = blockModel.getStart();
        createElementFace(element, Direction.DOWN, c[0], c[2], c[3], c[1]);
        createElementFace(element, Direction.UP, c[5], c[7], c[6], c[4]);
        createElementFace(element, Direction.NORTH, c[2], c[0], c[4], c[6]);
        createElementFace(element, Direction.SOUTH, c[1], c[3], c[7], c[5]);
        createElementFace(element, Direction.WEST, c[0], c[1], c[5], c[4]);
        createElementFace(element, Direction.EAST, c[3], c[2], c[6], c[7]);
        blockModel.initialize(modelStart);

        blockModel.transform(modelElementTransform
                .copy(element.getRotation().getMatrix())
                .scale(BLOCK_SCALE, BLOCK_SCALE, BLOCK_SCALE));
    }

    private final VectorM3f faceRotationVector = new VectorM3f(0, 0, 0);

    private void createElementFace(
            Element element,
            Direction faceDir,
            VectorM3f c0,
            VectorM3f c1,
            VectorM3f c2,
            VectorM3f c3) {
        Face face = element.getFaces().get(faceDir);
        if (face == null) return;

        Vector3i faceDirVector = faceDir.toVector();

        ExtendedBlock facedBlockNeighbor = getRotationRelativeBlock(faceDir);
        LightData blockLightData = block.getLightData();
        LightData facedLightData = facedBlockNeighbor.getLightData();

        int sunLight = Math.max(blockLightData.getSkyLight(), facedLightData.getSkyLight());
        int blockLight = Math.max(blockLightData.getBlockLight(), facedLightData.getBlockLight());

        if (block.isRemoveIfCave()
                && (renderSettings.isCaveDetectionUsesBlockLight()
                                ? Math.max(blockLight, sunLight)
                                : sunLight)
                        == 0) {
            return;
        }

        faceRotationVector.set(
                faceDirVector.getX(),
                faceDirVector.getY(),
                faceDirVector.getZ());
        faceRotationVector.rotateAndScale(element.getRotation().getMatrix());
        makeRotationRelative(faceRotationVector);

        if (renderSettings.isRenderTopOnly() && faceRotationVector.y < 0.01) return;
        if (face.getCullface() != null) {
            ExtendedBlock b = getRotationRelativeBlock(face.getCullface());
            BlockProperties p = b.getProperties();
            if (p.isCulling()) return;
            if (p.getCullingIdentical() && b.getBlockState().equals(block.getBlockState())) {
                return;
            }
        }

        ResourcePath<Texture> sourceTexture =
                face.getTexture().getTexturePath(modelResource.getTextures()::get);

        Vector4f uvRaw = face.getUv();
        float uvx = uvRaw.getX() / 16f;
        float uvy = uvRaw.getY() / 16f;
        float uvz = uvRaw.getZ() / 16f;
        float uvw = uvRaw.getW() / 16f;

        rawUvs[0].set(uvx, uvw);
        rawUvs[1].set(uvz, uvw);
        rawUvs[2].set(uvz, uvy);
        rawUvs[3].set(uvx, uvy);

        int rotationSteps = Math.floorDiv(face.getRotation(), 90) % 4;
        if (rotationSteps < 0) rotationSteps += 4;
        for (int i = 0; i < 4; i++) {
            uvs[i] = rawUvs[(rotationSteps + i) % 4];
        }

        float uvRotation = 0f;
        if (variant.isUvlock() && variant.isTransformed()) {
            float xRotSin = TrigMath.sin(variant.getX() * TrigMath.DEG_TO_RAD);
            float xRotCos = TrigMath.cos(variant.getX() * TrigMath.DEG_TO_RAD);
            uvRotation =
                    variant.getY()
                                    * (faceDirVector.getY() * xRotCos
                                            + faceDirVector.getZ() * xRotSin)
                            + variant.getX() * (1 - faceDirVector.getY());
        }

        if (uvRotation != 0) {
            uvRotation = (float) (uvRotation * TrigMath.DEG_TO_RAD);
            float cx = TrigMath.cos(uvRotation);
            float cy = TrigMath.sin(uvRotation);
            for (VectorM2f uv : uvs) {
                uv.translate(-0.5f, -0.5f);
                uv.rotate(cx, cy);
                uv.translate(0.5f, 0.5f);
            }
        }

        ResourcePath<Texture> resolvedTexture =
                resolveTexture(element, faceDir, c0, c1, c2, c3, sourceTexture);

        blockModel.initialize();
        blockModel.add(2);

        TileModel tileModel = blockModel.getTileModel();
        int face1 = blockModel.getStart();
        int face2 = face1 + 1;

        tileModel.setPositions(
                face1,
                c0.x, c0.y, c0.z,
                c1.x, c1.y, c1.z,
                c2.x, c2.y, c2.z);
        tileModel.setPositions(
                face2,
                c0.x, c0.y, c0.z,
                c2.x, c2.y, c2.z,
                c3.x, c3.y, c3.z);

        int textureId = textureGallery.get(resolvedTexture);
        tileModel.setMaterialIndex(face1, textureId);
        tileModel.setMaterialIndex(face2, textureId);

        tileModel.setUvs(
                face1,
                uvs[0].x, uvs[0].y,
                uvs[1].x, uvs[1].y,
                uvs[2].x, uvs[2].y);
        tileModel.setUvs(
                face2,
                uvs[0].x, uvs[0].y,
                uvs[2].x, uvs[2].y,
                uvs[3].x, uvs[3].y);

        if (face.getTintindex() >= 0) {
            if (tintColor.a < 0) {
                blockColorCalculator.getBlockColor(block, tintColor);
            }
            tileModel.setColor(face1, tintColor.r, tintColor.g, tintColor.b);
            tileModel.setColor(face2, tintColor.r, tintColor.g, tintColor.b);
        } else {
            tileModel.setColor(face1, 1f, 1f, 1f);
            tileModel.setColor(face2, 1f, 1f, 1f);
        }

        int emissiveBlockLight = Math.max(blockLight, element.getLightEmission());
        tileModel.setBlocklight(face1, emissiveBlockLight);
        tileModel.setBlocklight(face2, emissiveBlockLight);
        tileModel.setSunlight(face1, sunLight);
        tileModel.setSunlight(face2, sunLight);

        float ao0 = 1f;
        float ao1 = 1f;
        float ao2 = 1f;
        float ao3 = 1f;
        if (modelResource.isAmbientocclusion()) {
            ao0 = testAo(c0, faceDir);
            ao1 = testAo(c1, faceDir);
            ao2 = testAo(c2, faceDir);
            ao3 = testAo(c3, faceDir);
        }

        tileModel.setAOs(face1, ao0, ao1, ao2);
        tileModel.setAOs(face2, ao0, ao2, ao3);

        float a = faceRotationVector.y;
        if (a > 0.01 && resolvedTexture != null) {
            Texture texture = resolvedTexture.getResource(resourcePack::getTexture);
            if (texture != null) {
                mapColor.set(texture.getColorPremultiplied());
                if (tintColor.a >= 0) mapColor.multiply(tintColor);

                float combinedLight = Math.max(sunLight / 15f, blockLight / 15f);
                combinedLight =
                        (1 - renderSettings.getAmbientLight()) * combinedLight
                                + renderSettings.getAmbientLight();
                mapColor.r *= combinedLight;
                mapColor.g *= combinedLight;
                mapColor.b *= combinedLight;

                if (mapColor.a > blockColorOpacity) blockColorOpacity = mapColor.a;
                blockColor.add(mapColor);
            }
        }
    }

    private ResourcePath<Texture> resolveTexture(
            Element element,
            Direction localFace,
            VectorM3f c0,
            VectorM3f c1,
            VectorM3f c2,
            VectorM3f c3,
            ResourcePath<Texture> source) {
        if (source == null) return ResourcePack.MISSING_TEXTURE;

        String sourceId = source.getFormatted();
        Direction worldFace = directionOf(faceRotationVector);
        AxisPair axes = axesFor(element, c0, c1, c2, c3);
        if (worldFace == null || axes == null) return source;

        ConnectedTextureResourceExtension.FusionSpec fusion =
                connectedTextures.fusionSpec(sourceId);
        if (fusion != null) {
            int mask = connectionMask(
                    axes,
                    worldFace,
                    (other, front, frontOccluding, direction) ->
                            fusion.predicate().test(
                                    block.getBlockState(),
                                    other,
                                    front,
                                    frontOccluding,
                                    worldFace,
                                    direction));
            ResourcePath<Texture> material =
                    connectedTextures.fusionMaterial(sourceId, fusion, mask);
            if (!hasTexture(material)) return source;

            if (!"pieced".equals(fusion.layout()) && !"overlay".equals(fusion.layout())) {
                int tile = ConnectedTextureLayout.fusionTile(fusion.layout(), mask);
                remapUvs(fusion.grid(), tile);
            }
            return material;
        }

        CreateConnectedTextures.Spec create =
                connectedTextures.createSpec(
                        sourceId,
                        block.getBlockState().getFormatted(),
                        block.getBlockState().getProperties(),
                        worldFace);
        if (create == null) return source;

        String sheet = create.sheetTexture(block.getX(), block.getY(), block.getZ());
        int mask = connectionMask(
                axes,
                worldFace,
                (other, front, frontOccluding, direction) ->
                        createConnects(sourceId, create, other, frontOccluding, worldFace));
        mask = constrainCreateCorners(mask);

        int tile = ConnectedTextureLayout.createTile(create.type(), mask);
        ResourcePath<Texture> material =
                connectedTextures.createMaterial(sheet, create.type(), tile);
        if (!hasTexture(material)) return source;

        remapUvs(ConnectedTextureLayout.createGrid(create.type()), tile);
        return material;
    }

    private void remapUvs(ConnectedTextureLayout.Grid grid, int tile) {
        int tileX = Math.floorMod(tile, grid.width());
        int tileY = Math.floorDiv(tile, grid.width());
        for (VectorM2f uv : uvs) {
            uv.x = (tileX + uv.x) / grid.width();
            uv.y = (tileY + uv.y) / grid.height();
        }
    }

    private boolean createConnects(
            String sourceTexture,
            CreateConnectedTextures.Spec current,
            BlockState other,
            boolean frontOccluding,
            Direction worldFace) {
        if (other == null || other.isAir()) return false;

        BlockState own = block.getBlockState();
        boolean rotatedPillar = sourceTexture.startsWith(
                "createdeco:block/palettes/sheet_metal/");
        if (frontOccluding
                && (!rotatedPillar
                        || sameAxis(own.getProperties().get("axis"), worldFace))) {
            return false;
        }

        if (own.getFormatted().equals(other.getFormatted())) {
            if (rotatedPillar
                    && !java.util.Objects.equals(
                            own.getProperties().get("axis"),
                            other.getProperties().get("axis"))) {
                return false;
            }
            if (sourceTexture.contains("/scaffold/")) {
                return "true".equals(own.getProperties().get("bottom"))
                        && "true".equals(other.getProperties().get("bottom"));
            }
            return true;
        }

        // Encased Create blocks can connect across different block ids when they expose
        // the same casing sprite shift. Compare semantic CT sheets as a server-side
        // equivalent of Create's client CasingConnectivity registry.
        return createSemanticSpecs(other).contains(current.semanticKey());
    }

    private static boolean sameAxis(String stateAxis, Direction face) {
        if (stateAxis == null || face == null) return false;
        return switch (face) {
            case EAST, WEST -> "x".equals(stateAxis);
            case UP, DOWN -> "y".equals(stateAxis);
            case NORTH, SOUTH -> "z".equals(stateAxis);
        };
    }

    private Set<String> createSemanticSpecs(BlockState state) {
        return createSpecCache.computeIfAbsent(state, candidate -> {
            Set<String> output = new HashSet<>();
            var stateResource = resourcePack.getBlockState(candidate);
            if (stateResource == null) return Set.of();

            stateResource.forEach(candidate, 0, 0, 0, candidateVariant -> {
                Model model = candidateVariant.getModel().getResource(resourcePack::getModel);
                if (model == null || model.getElements() == null) return;
                for (Element element : model.getElements()) {
                    if (element == null) continue;
                    for (Face face : element.getFaces().values()) {
                        if (face == null) continue;
                        ResourcePath<Texture> path =
                                face.getTexture().getTexturePath(model.getTextures()::get);
                        if (path == null) continue;
                        CreateConnectedTextures.Spec spec =
                                connectedTextures.createSpec(
                                        path.getFormatted(),
                                        candidate.getFormatted(),
                                        candidate.getProperties(),
                                        Direction.NORTH);
                        if (spec != null) output.add(spec.semanticKey());
                    }
                }
            });

            return Set.copyOf(output);
        });
    }

    private int connectionMask(
            AxisPair axes,
            Direction worldFace,
            ConnectionTest test) {
        String[] names = {
            "top", "top_right", "right", "bottom_right",
            "bottom", "bottom_left", "left", "top_left"
        };
        int[][] offsets = {
            axes.up(),
            add(axes.up(), axes.right()),
            axes.right(),
            add(negate(axes.up()), axes.right()),
            negate(axes.up()),
            add(negate(axes.up()), negate(axes.right())),
            negate(axes.right()),
            add(axes.up(), negate(axes.right()))
        };

        Vector3i faceVector = worldFace.toVector();
        int mask = 0;
        for (int i = 0; i < offsets.length; i++) {
            int[] offset = offsets[i];
            ExtendedBlock otherBlock =
                    block.getNeighborBlock(offset[0], offset[1], offset[2]);
            ExtendedBlock frontBlock =
                    block.getNeighborBlock(
                            offset[0] + faceVector.getX(),
                            offset[1] + faceVector.getY(),
                            offset[2] + faceVector.getZ());
            if (test.test(
                    otherBlock.getBlockState(),
                    frontBlock.getBlockState(),
                    frontBlock.getProperties().isOccluding(),
                    names[i])) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    private static int constrainCreateCorners(int mask) {
        if ((mask & (ConnectedTextureLayout.TOP | ConnectedTextureLayout.RIGHT))
                != (ConnectedTextureLayout.TOP | ConnectedTextureLayout.RIGHT)) {
            mask &= ~ConnectedTextureLayout.TOP_RIGHT;
        }
        if ((mask & (ConnectedTextureLayout.RIGHT | ConnectedTextureLayout.BOTTOM))
                != (ConnectedTextureLayout.RIGHT | ConnectedTextureLayout.BOTTOM)) {
            mask &= ~ConnectedTextureLayout.BOTTOM_RIGHT;
        }
        if ((mask & (ConnectedTextureLayout.BOTTOM | ConnectedTextureLayout.LEFT))
                != (ConnectedTextureLayout.BOTTOM | ConnectedTextureLayout.LEFT)) {
            mask &= ~ConnectedTextureLayout.BOTTOM_LEFT;
        }
        if ((mask & (ConnectedTextureLayout.LEFT | ConnectedTextureLayout.TOP))
                != (ConnectedTextureLayout.LEFT | ConnectedTextureLayout.TOP)) {
            mask &= ~ConnectedTextureLayout.TOP_LEFT;
        }
        return mask;
    }

    private AxisPair axesFor(
            Element element,
            VectorM3f c0,
            VectorM3f c1,
            VectorM3f c2,
            VectorM3f c3) {
        VectorM3f[] positions = {c0, c1, c2, c3};
        Direction face = directionOf(faceRotationVector);
        int[] right = uvAxis(element, positions, true, face);
        int[] down = uvAxis(element, positions, false, face);
        if (right == null || down == null) return fallbackAxes(directionOf(faceRotationVector));
        return new AxisPair(negate(down), right);
    }

    private int[] uvAxis(
            Element element,
            VectorM3f[] positions,
            boolean uAxis,
            Direction face) {
        int target = uAxis ? 0 : 1;
        int other = uAxis ? 1 : 0;

        for (int i = 0; i < 4; i++) {
            int j = (i + 1) & 3;
            float primary = uv(uvs[j], target) - uv(uvs[i], target);
            float secondary = uv(uvs[j], other) - uv(uvs[i], other);
            if (Math.abs(primary) < 1.0e-4f || Math.abs(primary) < Math.abs(secondary)) {
                continue;
            }

            float sign = primary > 0 ? 1f : -1f;
            VectorM3f direction = new VectorM3f(
                    (positions[j].x - positions[i].x) * sign,
                    (positions[j].y - positions[i].y) * sign,
                    (positions[j].z - positions[i].z) * sign);
            direction.rotateAndScale(element.getRotation().getMatrix());
            makeRotationRelative(direction);
            int[] axis = dominantAxis(direction.x, direction.y, direction.z);
            if (axis != null && face != null && dotFace(axis, face) == 0) return axis;
        }
        return null;
    }

    private static float uv(VectorM2f uv, int component) {
        return component == 0 ? uv.x : uv.y;
    }

    private static int dotFace(int[] axis, Direction face) {
        Vector3i vector = face.toVector();
        return axis[0] * vector.getX()
                + axis[1] * vector.getY()
                + axis[2] * vector.getZ();
    }

    private static AxisPair fallbackAxes(Direction face) {
        if (face == null) return null;
        return switch (face) {
            case UP -> new AxisPair(new int[]{0, 0, -1}, new int[]{1, 0, 0});
            case DOWN -> new AxisPair(new int[]{0, 0, 1}, new int[]{1, 0, 0});
            case NORTH -> new AxisPair(new int[]{0, 1, 0}, new int[]{-1, 0, 0});
            case SOUTH -> new AxisPair(new int[]{0, 1, 0}, new int[]{1, 0, 0});
            case WEST -> new AxisPair(new int[]{0, 1, 0}, new int[]{0, 0, 1});
            case EAST -> new AxisPair(new int[]{0, 1, 0}, new int[]{0, 0, -1});
        };
    }

    private static Direction directionOf(VectorM3f direction) {
        int[] axis = dominantAxis(direction.x, direction.y, direction.z);
        if (axis == null) return null;
        if (axis[0] > 0) return Direction.EAST;
        if (axis[0] < 0) return Direction.WEST;
        if (axis[1] > 0) return Direction.UP;
        if (axis[1] < 0) return Direction.DOWN;
        if (axis[2] > 0) return Direction.SOUTH;
        return Direction.NORTH;
    }

    private static int[] dominantAxis(float x, float y, float z) {
        float ax = Math.abs(x);
        float ay = Math.abs(y);
        float az = Math.abs(z);
        if (Math.max(ax, Math.max(ay, az)) < 1.0e-4f) return null;
        if (ax >= ay && ax >= az) return new int[]{x >= 0 ? 1 : -1, 0, 0};
        if (ay >= ax && ay >= az) return new int[]{0, y >= 0 ? 1 : -1, 0};
        return new int[]{0, 0, z >= 0 ? 1 : -1};
    }

    private boolean hasTexture(ResourcePath<Texture> path) {
        return path != null && resourcePack.getTextures().containsKey(path);
    }

    private ExtendedBlock getRotationRelativeBlock(Direction direction) {
        return getRotationRelativeBlock(direction.toVector());
    }

    private ExtendedBlock getRotationRelativeBlock(Vector3i direction) {
        return getRotationRelativeBlock(
                direction.getX(), direction.getY(), direction.getZ());
    }

    private final VectorM3f rotationRelativeBlockDirection = new VectorM3f(0, 0, 0);

    private ExtendedBlock getRotationRelativeBlock(int dx, int dy, int dz) {
        rotationRelativeBlockDirection.set(dx, dy, dz);
        makeRotationRelative(rotationRelativeBlockDirection);
        return block.getNeighborBlock(
                Math.round(rotationRelativeBlockDirection.x),
                Math.round(rotationRelativeBlockDirection.y),
                Math.round(rotationRelativeBlockDirection.z));
    }

    private void makeRotationRelative(VectorM3f direction) {
        if (variant.isTransformed()) {
            direction.rotateAndScale(variant.getTransformMatrix());
        }
    }

    private float testAo(VectorM3f vertex, Direction dir) {
        Vector3i dirVec = dir.toVector();
        int occluding = 0;

        int x = vertex.x == 16 ? 1 : vertex.x == 0 ? -1 : 0;
        int y = vertex.y == 16 ? 1 : vertex.y == 0 ? -1 : 0;
        int z = vertex.z == 16 ? 1 : vertex.z == 0 ? -1 : 0;

        if (x * dirVec.getX() + y * dirVec.getY() > 0
                && getRotationRelativeBlock(x, y, 0).getProperties().isOccluding()) {
            occluding++;
        }
        if (x * dirVec.getX() + z * dirVec.getZ() > 0
                && getRotationRelativeBlock(x, 0, z).getProperties().isOccluding()) {
            occluding++;
        }
        if (y * dirVec.getY() + z * dirVec.getZ() > 0
                && getRotationRelativeBlock(0, y, z).getProperties().isOccluding()) {
            occluding++;
        }
        if (x * dirVec.getX() + y * dirVec.getY() + z * dirVec.getZ() > 0
                && getRotationRelativeBlock(x, y, z).getProperties().isOccluding()) {
            occluding++;
        }

        if (occluding > 3) occluding = 3;
        return Math.max(0f, Math.min(1f - occluding * 0.25f, 1f));
    }

    private static int[] add(int[] a, int[] b) {
        return new int[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static int[] negate(int[] value) {
        return new int[]{-value[0], -value[1], -value[2]};
    }

    private static float hashToFloat(int x, int z, long seed) {
        final long hash = x * 73428767L ^ z * 4382893L ^ seed * 457;
        return (hash * (hash + 456149) & 0x00ffffff) / (float) 0x01000000;
    }

    private record AxisPair(int[] up, int[] right) {
    }

    @FunctionalInterface
    private interface ConnectionTest {
        boolean test(
                BlockState other,
                BlockState front,
                boolean frontOccluding,
                String direction);
    }
}
