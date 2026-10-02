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
import net.minecraft.core.registries.BuiltInRegistries;
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
        installEmptyNeoForgeLoadingContext();
        bootstrapRegistries();

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

        for (String required : new String[]{
                "minecraft:sheep#main",
                "minecraft:cow#main",
                "minecraft:pig#main",
                "minecraft:chicken#main"}) {
            if (!generated.containsKey(required)) {
                throw new IllegalStateException("Missing required entity model layer: " + required);
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
     * NeoForge extends vanilla bootstrap by asking the loading-time mod list for custom
     * feature flags. A normal game launch has populated that list already; this tiny
     * build-only JavaExec has not. Install an empty list through the loader's own factory
     * before bootstrapping. Reflection keeps this helper compatible with the small API
     * shape changes between FML releases and is confined to the generator source set.
     */
    private static void installEmptyNeoForgeLoadingContext() {
        try {
            Class<?> type = Class.forName("net.neoforged.fml.loading.LoadingModList");
            java.lang.reflect.Method get = type.getDeclaredMethod("get");
            get.setAccessible(true);
            if (get.invoke(null) != null) return;

            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("of")
                        || !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || !type.isAssignableFrom(method.getReturnType())) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    Object[] arguments = emptyArguments(method.getParameterTypes());
                    method.invoke(null, arguments);
                    if (get.invoke(null) != null) return;
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try another overload or the constructor fallback below.
                }
            }

            for (java.lang.reflect.Constructor<?> constructor : type.getDeclaredConstructors()) {
                try {
                    constructor.setAccessible(true);
                    Object instance = constructor.newInstance(emptyArguments(constructor.getParameterTypes()));
                    for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                                && field.getType() == type) {
                            field.setAccessible(true);
                            field.set(null, instance);
                            if (get.invoke(null) != null) return;
                        }
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // Try the next constructor.
                }
            }

            throw new IllegalStateException("Could not initialize NeoForge LoadingModList");
        } catch (ClassNotFoundException ignored) {
            // Vanilla/Fabric generator classpath: no loader hook exists.
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not initialize NeoForge loading context", e);
        }
    }

    private static Object[] emptyArguments(Class<?>[] parameterTypes) {
        Object[] arguments = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameter = parameterTypes[i];
            if (java.util.List.class.isAssignableFrom(parameter)
                    || java.util.Collection.class.isAssignableFrom(parameter)) {
                arguments[i] = java.util.List.of();
            } else if (java.util.Map.class.isAssignableFrom(parameter)) {
                arguments[i] = java.util.Map.of();
            } else if (java.util.Set.class.isAssignableFrom(parameter)) {
                arguments[i] = java.util.Set.of();
            } else if (java.util.Optional.class.isAssignableFrom(parameter)) {
                arguments[i] = java.util.Optional.empty();
            } else if (parameter == boolean.class) {
                arguments[i] = false;
            } else if (parameter == int.class || parameter == short.class
                    || parameter == byte.class || parameter == long.class) {
                arguments[i] = 0;
            } else if (parameter == float.class || parameter == double.class) {
                arguments[i] = 0D;
            } else if (parameter == char.class) {
                arguments[i] = '\0';
            } else {
                arguments[i] = null;
            }
        }
        return arguments;
    }

    private static void bootstrapRegistries() {
        try {
            Bootstrap.bootStrap();
        } catch (ExceptionInInitializerError error) {
            // NeoForge's standalone userdev classpath contains the mapped game classes
            // but not the client language resources. Bootstrap can therefore fail late
            // while validating creative-tab translations, after all built-in registries
            // required by LayerDefinitions are already initialized. Only tolerate that
            // late failure; an actually empty registry is still fatal.
            if (BuiltInRegistries.REGISTRY.keySet().isEmpty()) {
                throw error;
            }
        }
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
