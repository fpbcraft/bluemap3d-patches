package dev.duzo.bluemap3d.entities.modelgen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.client.model.geom.LayerDefinitions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.server.Bootstrap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Build-time only. Bakes Minecraft's real client ModelPart layers to plain triangles so
 * the dedicated server never has to load a client renderer.
 */
public final class VanillaEntityModelGenerator {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final Map<String, Float> RENDERER_SCALES = Map.ofEntries(
            Map.entry("bat", 0.35F),
            Map.entry("cat", 0.8F),
            Map.entry("cave_spider", 0.7F),
            Map.entry("donkey", 0.87F),
            Map.entry("elder_guardian", 2.35F),
            Map.entry("evoker", 0.9375F),
            Map.entry("ghast", 4.5F),
            Map.entry("giant", 6F),
            Map.entry("horse", 1.1F),
            Map.entry("husk", 1.0625F),
            Map.entry("illusioner", 0.9375F),
            Map.entry("mule", 0.92F),
            Map.entry("pillager", 0.9375F),
            Map.entry("polar_bear", 1.2F),
            Map.entry("villager", 0.9375F),
            Map.entry("vindicator", 0.9375F),
            Map.entry("wandering_trader", 0.9375F),
            Map.entry("witch", 0.9375F),
            Map.entry("wither", 2F),
            Map.entry("wither_skeleton", 1.2F)
    );

    private VanillaEntityModelGenerator() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected output path and Minecraft version");
        }

        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        Map<String, JsonObject> generated = new TreeMap<>();
        for (var entry : LayerDefinitions.createRoots().entrySet()) {
            ModelLayerLocation location = entry.getKey();
            if (!"minecraft".equals(location.getModel().getNamespace())) continue;

            String model = location.getModel().getPath();
            GeometryCollector collector =
                    new GeometryCollector(RENDERER_SCALES.getOrDefault(model, 1F));
            entry.getValue().bakeRoot().render(new PoseStack(), collector, 0, 0);

            if (!collector.isEmpty()) {
                generated.put(location.getModel() + "#" + location.getLayer(), collector.toJson());
            }
        }

        JsonObject models = new JsonObject();
        generated.forEach(models::add);

        JsonObject root = new JsonObject();
        root.addProperty("format", 2);
        root.addProperty("minecraft", args[1]);
        root.add("models", models);

        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, GSON.toJson(root), StandardCharsets.UTF_8);
        System.out.printf("Generated %,d vanilla entity model layers at %s%n",
                generated.size(), output);
    }

    /**
     * ModelPart emits already-transformed vertices. Capturing the stream preserves cube
     * inflation, child transforms, omitted faces and the exact model-layer UV layout.
     */
    private static final class GeometryCollector implements VertexConsumer {
        private final JsonArray positions = new JsonArray();
        private final JsonArray uvs = new JsonArray();
        private final float scale;

        private float x;
        private float y;
        private float z;
        private float u;
        private float v;

        private GeometryCollector(float scale) {
            this.scale = scale;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float nx, float ny, float nz) {
            // Minecraft model coordinates are y-down around a point 1.5 blocks above
            // the feet. BlueMap3D attachments are y-up with their origin at the feet.
            positions.add(clean(x * scale * 16F));
            positions.add(clean((1.501F - y) * scale * 16F));
            positions.add(clean(-z * scale * 16F));

            // VolumeMesher's attachment API uses 0..16 sprite-local UV coordinates.
            uvs.add(clean(u * 16F));
            uvs.add(clean((1F - v) * 16F));
            return this;
        }

        private boolean isEmpty() {
            return positions.isEmpty();
        }

        private JsonObject toJson() {
            if ((positions.size() / 3) % 4 != 0) {
                throw new IllegalStateException("Entity model emitted an incomplete quad");
            }
            JsonObject model = new JsonObject();
            model.add("positions", positions);
            model.add("uvs", uvs);
            return model;
        }

        private static double clean(float value) {
            double rounded = Math.rint(value * 1_000_000D) / 1_000_000D;
            return rounded == -0D ? 0D : rounded;
        }
    }
}
