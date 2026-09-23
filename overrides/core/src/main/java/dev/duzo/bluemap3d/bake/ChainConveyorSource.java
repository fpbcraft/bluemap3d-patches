package dev.duzo.bluemap3d.bake;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates one attachment model for an entire Create chain-conveyor connection.
 *
 * <p>The attachment id is {@code bluemap3d:chain_conveyor/<segments>}. The source takes
 * vanilla's chain model and repeats it along local +Y in 16-pixel increments. The provider
 * then rotates +Y onto the connection vector and assigns a single {@link
 * dev.duzo.bluemap3d.api.ModelAttachment.Loop} motion to the whole run. One long conveyor
 * therefore costs one animated node rather than one node per repeated chain block.
 */
public final class ChainConveyorSource implements BlockModelSource {

    private static final ResourceLocation CHAIN_MODEL =
            ResourceLocation.fromNamespaceAndPath("minecraft", "block/chain");

    private final ResourcePackSource models;
    private final Map<Integer, List<ModelQuad>> cache = new ConcurrentHashMap<>();

    public ChainConveyorSource(ResourcePackSource models) {
        this.models = models;
    }

    @Override
    public List<ModelQuad> quadsFor(BlockState state) {
        return List.of();
    }

    @Override
    public List<ModelQuad> quadsForModel(ResourceLocation model, Map<String, String> textures) {
        if (!"bluemap3d".equals(model.getNamespace())) {
            return List.of();
        }

        String prefix = "chain_conveyor/";
        String path = model.getPath();
        if (!path.startsWith(prefix)) {
            return List.of();
        }

        final int segments;
        try {
            segments = Integer.parseInt(path.substring(prefix.length()));
        } catch (NumberFormatException error) {
            return List.of();
        }

        if (segments < 1 || segments > 256) {
            return List.of();
        }
        return cache.computeIfAbsent(segments, this::build);
    }

    private List<ModelQuad> build(int segments) {
        List<ModelQuad> base = models.quadsForModel(CHAIN_MODEL, Map.of());
        if (base.isEmpty()) {
            return List.of();
        }

        List<ModelQuad> out = new ArrayList<>(base.size() * segments);
        for (int segment = 0; segment < segments; segment++) {
            float dy = segment * 16f;
            for (ModelQuad quad : base) {
                float[] source = quad.positions();
                float[] positions = source.clone();
                for (int i = 0; i < 4; i++) {
                    positions[i * 3 + 1] += dy;
                }

                out.add(new ModelQuad(
                        null,
                        quad.shadeFace(),
                        positions,
                        quad.uvs().clone(),
                        quad.texture(),
                        quad.tint()));
            }
        }
        return List.copyOf(out);
    }

    @Override
    public BufferedImage texture(String texture) {
        return models.texture(texture);
    }

    @Override
    public boolean occludes(BlockState state) {
        return false;
    }
}
