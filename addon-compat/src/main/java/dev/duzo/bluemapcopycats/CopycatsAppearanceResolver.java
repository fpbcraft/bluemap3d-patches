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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Resolves the BlueMap texture index and biome tint for a copied material face.
 */
final class CopycatsAppearanceResolver {

    private final ResourcePack resourcePack;
    private final TextureGallery textureGallery;
    private final BlockColorCalculatorFactory.BlockColorCalculator blockColorCalculator;

    CopycatsAppearanceResolver(ResourcePack resourcePack, TextureGallery textureGallery) {
        this.resourcePack = resourcePack;
        this.textureGallery = textureGallery;
        this.blockColorCalculator = resourcePack.getColorCalculatorFactory().createCalculator();
    }

    Appearance resolve(
            CopycatsMaterial material,
            Direction wantedFace,
            BlockNeighborhood block) {
        BlockState materialState = material.asBlockState();
        var stateResource = resourcePack.getBlockState(materialState);
        if (stateResource == null) return null;

        List<Variant> variants = new ArrayList<>(2);
        stateResource.forEach(
                materialState,
                block.getX(),
                block.getY(),
                block.getZ(),
                variants::add);
        if (variants.isEmpty()) return null;

        Model model = variants.getFirst().getModel().getResource(resourcePack::getModel);
        if (model == null || model.getElements() == null) return null;

        List<Map<Direction, Face>> faces = new ArrayList<>();
        for (Element element : model.getElements()) {
            if (element != null) faces.add(element.getFaces());
        }
        Face selected = CopycatsFaceSelector.selectPreferredFace(
                faces,
                wantedFace,
                List.of(Direction.values()));
        if (selected == null) return null;

        ResourcePath<Texture> texture =
                selected.getTexture().getTexturePath(model.getTextures()::get);
        if (texture == null) texture = ResourcePack.MISSING_TEXTURE;
        int textureIndex = textureGallery.get(texture);

        Color tint = new Color().set(1f, 1f, 1f, 1f, true);
        if (selected.getTintindex() >= 0) {
            blockColorCalculator.getBlockColor(
                    new MaterialStateBlock(block, materialState),
                    tint);
            if (tint.a < 0) tint.set(1f, 1f, 1f, 1f, true);
        }
        return new Appearance(textureIndex, tint);
    }

    record Appearance(int textureIndex, Color tint) {
    }
}
