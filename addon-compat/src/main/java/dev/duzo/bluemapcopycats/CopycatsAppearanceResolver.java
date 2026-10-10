package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.map.TextureGallery;
import de.bluecolored.bluemap.core.resources.BlockColorCalculatorFactory;
import de.bluecolored.bluemap.core.resources.ResourcePath;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.ResourcePack;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.blockstate.Variant;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Element;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Face;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.model.Model;
import de.bluecolored.bluemap.core.resources.pack.resourcepack.texture.Texture;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.BlockState;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import dev.duzo.bluemapctm.ConnectedTextureTerrainDispatch;
import dev.duzo.bluemapctm.CopiedMaterialConnectedTextures;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves the BlueMap texture index and biome tint for a copied material face.
 */
final class CopycatsAppearanceResolver {

    private final ResourcePack resourcePack;
    private final TextureGallery textureGallery;
    private final CopiedMaterialConnectedTextures copiedConnectedTextures;
    private final BlockColorCalculatorFactory.BlockColorCalculator blockColorCalculator;

    CopycatsAppearanceResolver(ResourcePack resourcePack, TextureGallery textureGallery) {
        this.resourcePack = resourcePack;
        this.textureGallery = textureGallery;
        this.copiedConnectedTextures = new CopiedMaterialConnectedTextures(resourcePack);
        this.blockColorCalculator = resourcePack.getColorCalculatorFactory().createCalculator();
    }

    Appearance resolve(
            CopycatsMaterial material,
            Direction wantedFace,
            BlockNeighborhood block) {
        BlockState materialState = material.asBlockState();
        String materialId = materialState.getFormatted();

        // Static compatibility adapters replace some ordinary blockstates with
        // lightweight renderer-dispatch blockstates after BlueMap has baked resources.
        // Copycats needs the copied material's real model/texture, not that dispatch
        // placeholder, so unwrap any routed material before resolving its faces.
        var stateResource = ConnectedTextureTerrainDispatch.original(materialId);
        if (stateResource == null) {
            stateResource = ConnectedTerrainDispatch.original(materialId);
        }
        if (stateResource == null) {
            stateResource = resourcePack.getBlockState(materialState);
        }
        if (stateResource == null) {
            CopycatsTrace.log(block, "MODEL", "material=" + materialId + " face=" + wantedFace + " reason=missing-blockstate");
            return null;
        }

        List<Variant> variants = new ArrayList<>(2);
        stateResource.forEach(
                materialState,
                block.getX(),
                block.getY(),
                block.getZ(),
                variants::add);
        if (variants.isEmpty()) {
            CopycatsTrace.log(block, "MODEL", "material=" + materialId + " face=" + wantedFace + " reason=no-variant");
            return null;
        }

        Model model = variants.getFirst().getModel().getResource(resourcePack::getModel);
        if (model == null) {
            CopycatsTrace.log(block, "MODEL", "material=" + materialId + " face=" + wantedFace + " reason=missing-model");
            return null;
        }
        // Railways and Pretty in Pink both use inherited vanilla cube-column models.
        // Faces and texture variables live on the parent rather than the leaf JSON.
        model.applyParent(resourcePack);
        if (model.getElements() == null) {
            CopycatsTrace.log(block, "MODEL", "material=" + materialId + " face=" + wantedFace + " reason=no-elements-after-inheritance");
            return null;
        }

        List<Map<Direction, Face>> faces = new ArrayList<>();
        for (Element element : model.getElements()) {
            if (element != null) faces.add(element.getFaces());
        }
        Face selected = CopycatsFaceSelector.selectPreferredFace(
                faces,
                wantedFace,
                List.of(Direction.values()));
        if (selected == null) {
            CopycatsTrace.log(block, "MODEL", "material=" + materialId + " face=" + wantedFace + " reason=no-face");
            return null;
        }

        ResourcePath<Texture> texture =
                selected.getTexture().getTexturePath(model.getTextures()::get);
        if (texture == null) texture = ResourcePack.MISSING_TEXTURE;
        var connected = copiedConnectedTextures.resolve(texture, materialState, wantedFace, block);
        int textureIndex = textureGallery.get(connected.texture());
        CopycatsTrace.log(block, "ATLAS", "material=" + materialId + " face=" + wantedFace
                + " baseTexture=" + texture + " chosenTexture=" + connected.texture()
                + " sourcePresent=" + resourcePack.getTextures().containsKey(texture)
                + " chosenPresent=" + resourcePack.getTextures().containsKey(connected.texture())
                + " index=" + textureIndex
                + " uv=" + connected.u0() + "," + connected.v0() + ","
                + connected.u1() + "," + connected.v1());

        Color tint = new Color().set(1f, 1f, 1f, 1f, true);
        if (selected.getTintindex() >= 0) {
            blockColorCalculator.getBlockColor(
                    new MaterialStateBlock(block, materialState),
                    tint);
            if (tint.a < 0) tint.set(1f, 1f, 1f, 1f, true);
        }
        return new Appearance(textureIndex, tint,
                connected.u0(), connected.v0(), connected.u1(), connected.v1());
    }

    record Appearance(int textureIndex, Color tint,
            float u0, float v0, float u1, float v1) {
    }
}
