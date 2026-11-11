package dev.duzo.bluemap3d.bake;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Server-side material substitution for Steam 'n' Rails copycat headstocks.
 *
 * <p>Railways' JSON models contain the complete headstock/coupler geometry but mark only
 * the copycat faces with Create's copycat_base sprite. The client replaces those faces at
 * bake time. On a dedicated server that wrapper never runs, so do the equivalent texture
 * substitution here while leaving buffer/coupler textures untouched.
 */
public final class RailwaysCopycatHeadstockSource implements BlockModelSource {

    private static final String COPYCAT_BASE = "create:block/copycat_base";

    private final ResourcePackSource models;

    public RailwaysCopycatHeadstockSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return List.of();
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state, CompoundTag metadata) {
        return List.of();
    }

    @Override
    public List<ModelQuad> quadsFor(BlockRenderContext context) {
        var id = BuiltInRegistries.BLOCK.getKey(context.state().getBlock());
        if (!"railways".equals(id.getNamespace())
                || !id.getPath().startsWith("copycat_headstock")) {
            return List.of();
        }

        BlockState material =
                CopiedMaterialResolver.materialFor(context.blockEntityData(), null);
        if (!CopiedMaterialResolver.usable(material)) return List.of();

        List<ModelQuad> base = models.quadsFor(context.state());
        if (base.isEmpty()) return List.of();

        List<ModelQuad> out = new ArrayList<>(base.size());
        boolean replaced = false;
        for (ModelQuad quad : base) {
            if (!COPYCAT_BASE.equals(quad.texture())) {
                out.add(quad);
                continue;
            }

            Direction face = quad.shadeFace() != null ? quad.shadeFace() : quad.cullFace();
            Appearance appearance = appearance(material, face, context);
            out.add(new ModelQuad(
                    quad.cullFace(),
                    quad.shadeFace(),
                    quad.positions(),
                    quad.uvs(),
                    appearance.texture(),
                    appearance.tint()));
            replaced = true;
        }

        return replaced ? List.copyOf(out) : List.of();
    }

    @Override
    public BufferedImage texture(String texture) {
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return false;
    }

    private Appearance appearance(
            BlockState material,
            Direction surface,
            BlockRenderContext context) {
        List<ModelQuad> quads = models.quadsFor(context.withState(material));
        ModelQuad chosen = null;
        for (ModelQuad quad : quads) {
            if (surface != null && quad.cullFace() == surface) {
                chosen = quad;
                break;
            }
            if (surface != null && chosen == null && quad.shadeFace() == surface) {
                chosen = quad;
            }
        }
        if (chosen == null && !quads.isEmpty()) chosen = quads.getFirst();

        if (chosen != null
                && chosen.texture() != null
                && models.texture(chosen.texture()) != null) {
            return new Appearance(chosen.texture(), chosen.tint());
        }

        String particle = models.particleTexture(material);
        if (particle != null && models.texture(particle) != null) {
            return new Appearance(particle, 0xFFFFFF);
        }
        return new Appearance("minecraft:block/stone", 0xFFFFFF);
    }

    private record Appearance(String texture, int tint) {}
}
