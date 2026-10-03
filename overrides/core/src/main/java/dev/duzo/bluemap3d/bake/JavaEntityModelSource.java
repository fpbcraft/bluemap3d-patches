package dev.duzo.bluemap3d.bake;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Dedicated-server extractor for ordinary Java-authored Minecraft entity models.
 *
 * <p>Many mods do not ship a data-driven entity model. Their geometry exists only in a
 * client class whose static {@code createBodyLayer()} method builds a
 * {@code LayerDefinition} from {@code MeshDefinition}, {@code PartDefinition},
 * {@code CubeListBuilder}, {@code CubeDeformation} and {@code PartPose}. Loading those
 * classes on a dedicated server is unsafe because they reference client-only Minecraft
 * types, but the class bytes are present in the installed mod jar.
 *
 * <p>This class interprets only that small, declarative builder subset directly from
 * bytecode using ASM (already provided by NeoForge/FML). It does <em>not</em> execute
 * arbitrary mod code. A model that uses an unsupported control-flow/custom-builder
 * construct simply returns no geometry and the normal entity fallback remains active.
 *
 * <p>The result is the same cuboid/UV geometry Minecraft's ModelPart.Cube constructor
 * would bake, including hierarchy transforms, mirror state, independent X/Y/Z
 * deformation and texture scaling.
 */
final class JavaEntityModelSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/EntityJavaModels");

    private static final String LAYER =
            "net/minecraft/client/model/geom/builders/LayerDefinition";
    private static final String MESH =
            "net/minecraft/client/model/geom/builders/MeshDefinition";
    private static final String PART =
            "net/minecraft/client/model/geom/builders/PartDefinition";
    private static final String BUILDER =
            "net/minecraft/client/model/geom/builders/CubeListBuilder";
    private static final String DEFORMATION =
            "net/minecraft/client/model/geom/builders/CubeDeformation";
    private static final String POSE =
            "net/minecraft/client/model/geom/PartPose";

    private static final Object NULL = new Object();

    private final AssetIndex assets;
    private volatile List<String> modelClasses;

    JavaEntityModelSource(AssetIndex assets) {
        this.assets = assets;
    }

    List<ModelQuad> resolve(
            String namespace,
            String entityPath,
            Map<String, String> metadata,
            String texture) {
        List<Candidate> candidates = candidates(namespace, entityPath, metadata);
        for (Candidate candidate : candidates) {
            byte[] bytes = assets.read(candidate.path());
            if (bytes == null) continue;

            try {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                JavaLayer best = null;
                String methodName = null;
                for (MethodNode method : layerFactories(node)) {
                    List<Object> defaults = defaultArguments(method);
                    if (defaults == null) continue;

                    try {
                        Object value = new Interpreter(node).invoke(method, defaults, 0);
                        if (value instanceof JavaLayer layer && !layer.root().isEmpty()) {
                            best = layer;
                            methodName = method.name;
                            break;
                        }
                    } catch (UnsupportedModel ignored) {
                        // Try another layer factory in the same class.
                    }
                }

                if (best == null) continue;

                List<ModelQuad> quads = bake(best, texture);
                if (!quads.isEmpty()) {
                    LOGGER.info(
                            "Resolved Java entity model {}:{} from {}#{} ({} quads)",
                            namespace, entityPath, candidate.path(), methodName, quads.size());
                    return quads;
                }
            } catch (RuntimeException error) {
                LOGGER.debug(
                        "Could not extract Java model {} for {}:{}: {}",
                        candidate.path(), namespace, entityPath, error.toString());
            }
        }

        return List.of();
    }

    List<DiagnosticCandidate> diagnose(
            String namespace, String entityPath, Map<String, String> metadata) {
        List<DiagnosticCandidate> out = new ArrayList<>();
        for (Candidate candidate : candidates(namespace, entityPath, metadata)) {
            byte[] bytes = assets.read(candidate.path());
            if (bytes == null) {
                out.add(new DiagnosticCandidate(candidate.path(), candidate.score(), false, "unreadable"));
                continue;
            }

            try {
                ClassNode node = new ClassNode();
                new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                List<MethodNode> factoryMethods = layerFactories(node);
                List<String> factories = factoryMethods.stream()
                        .map(method -> method.name + method.desc)
                        .toList();
                if (factories.isEmpty()) {
                    out.add(new DiagnosticCandidate(
                            candidate.path(), candidate.score(), false, "no LayerDefinition factory"));
                    continue;
                }

                String detail = "factories=" + factories;
                boolean supported = false;
                for (MethodNode method : factoryMethods) {
                    List<Object> defaults = defaultArguments(method);
                    if (defaults == null) {
                        detail = method.name + ": unsupported factory parameters";
                        continue;
                    }
                    try {
                        Object result = new Interpreter(node).invoke(method, defaults, 0);
                        if (result instanceof JavaLayer layer && !layer.root().isEmpty()) {
                            supported = true;
                            detail = "extractable via " + method.name
                                    + " (" + countCubes(layer.root()) + " cubes)";
                            break;
                        }
                    } catch (UnsupportedModel error) {
                        detail = method.name + ": " + error.getMessage();
                    }
                }
                out.add(new DiagnosticCandidate(
                        candidate.path(), candidate.score(), supported, detail));
            } catch (RuntimeException error) {
                out.add(new DiagnosticCandidate(
                        candidate.path(), candidate.score(), false, error.toString()));
            }
        }
        return List.copyOf(out);
    }

    private static List<MethodNode> layerFactories(ClassNode node) {
        List<MethodNode> out = node.methods.stream()
                .filter(method -> (method.access & Opcodes.ACC_STATIC) != 0)
                .filter(method -> LAYER.equals(Type.getReturnType(method.desc).getInternalName()))
                .sorted(Comparator.comparingInt(JavaEntityModelSource::factoryScore).reversed())
                .toList();
        return out;
    }

    private static int factoryScore(MethodNode method) {
        String name = method.name.toLowerCase(Locale.ROOT);
        int score = 0;
        if (name.contains("body")) score += 500;
        if (name.contains("texturedmodel")) score += 450;
        if (name.contains("modeldata")) score += 400;
        if (name.contains("layer")) score += 200;
        if (name.contains("armor")) score -= 500;
        if (name.contains("overlay")) score -= 400;
        return score;
    }

    /**
     * Standard model factories sometimes expose deformation/scale knobs even when the
     * renderer uses their neutral values. Supply only values whose neutral meaning is
     * unambiguous; unknown object parameters make that factory ineligible.
     */
    private static List<Object> defaultArguments(MethodNode method) {
        List<Object> out = new ArrayList<>();
        for (Type type : Type.getArgumentTypes(method.desc)) {
            switch (type.getSort()) {
                case Type.BOOLEAN, Type.BYTE, Type.SHORT, Type.INT, Type.CHAR -> out.add(0);
                case Type.LONG -> out.add(0L);
                case Type.FLOAT -> out.add(0F);
                case Type.DOUBLE -> out.add(0D);
                case Type.OBJECT -> {
                    if (DEFORMATION.equals(type.getInternalName())) {
                        out.add(JavaDeformation.NONE);
                    } else {
                        return null;
                    }
                }
                default -> {
                    return null;
                }
            }
        }
        return List.copyOf(out);
    }

    static int extractCubeCountForTest(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode method : layerFactories(node)) {
            List<Object> defaults = defaultArguments(method);
            if (defaults == null) continue;
            Object result = new Interpreter(node).invoke(method, defaults, 0);
            if (result instanceof JavaLayer layer && !layer.root().isEmpty()) {
                return countCubes(layer.root());
            }
        }
        return 0;
    }

    private List<Candidate> candidates(
            String namespace, String entityPath, Map<String, String> metadata) {
        String namespaceCompact = compact(namespace);
        List<Candidate> out = new ArrayList<>();

        for (String path : modelClasses()) {
            String lower = path.toLowerCase(Locale.ROOT);
            String leaf = leaf(lower);
            if (!leaf.endsWith("model.class")
                    && !leaf.contains("model$")) {
                continue;
            }
            if (leaf.contains("renderer") || leaf.contains("animation")
                    || leaf.contains("layer")) {
                continue;
            }

            int score = EntityAssetMatch.score(entityPath, path, "main")
                    + EntityAssetMatch.appearanceScore(metadata, path);
            if (score <= 0) continue;

            String compactPath = compact(path);
            if (!namespaceCompact.isEmpty() && compactPath.contains(namespaceCompact)) {
                score += 260;
            } else {
                // Namespace mismatch is not fatal because mod ids and Java packages can be
                // very different, but exact-package candidates should dominate.
                score -= 80;
            }

            if (leaf.endsWith("babymodel.class")
                    && "baby".equals(metadata.get("__bm3d_visual_age"))) {
                score += 500;
            } else if (leaf.endsWith("babymodel.class")) {
                score -= 300;
            }

            out.add(new Candidate(path, score));
        }

        out.sort(Comparator.comparingInt(Candidate::score).reversed());
        return out.size() <= 24 ? List.copyOf(out) : List.copyOf(out.subList(0, 24));
    }

    private List<String> modelClasses() {
        List<String> existing = modelClasses;
        if (existing != null) return existing;

        synchronized (this) {
            if (modelClasses != null) return modelClasses;
            modelClasses = assets.findPaths(
                    "",
                    path -> {
                        String lower = path.toLowerCase(Locale.ROOT);
                        return lower.endsWith(".class")
                                && lower.contains("model")
                                && !lower.contains("modelgen");
                    },
                    8192);
            LOGGER.info("Indexed {} candidate Java entity model class(es)", modelClasses.size());
            return modelClasses;
        }
    }

    private static List<ModelQuad> bake(JavaLayer layer, String texture) {
        List<ModelQuad> out = new ArrayList<>();
        emitPart(out, layer.root(), new Matrix4f(), layer.textureWidth(), layer.textureHeight(), texture);
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private static void emitPart(
            List<ModelQuad> out,
            JavaPart part,
            Matrix4f parent,
            float textureWidth,
            float textureHeight,
            String texture) {
        Matrix4f transform = new Matrix4f(parent)
                .translate(part.pose().x(), part.pose().y(), part.pose().z());

        if (Math.abs(part.pose().xRot()) + Math.abs(part.pose().yRot())
                + Math.abs(part.pose().zRot()) > 1.0e-7F) {
            transform.rotateZYX(
                    part.pose().zRot(),
                    part.pose().yRot(),
                    part.pose().xRot());
        }

        for (JavaCube cube : part.cubes()) {
            emitCube(out, cube, transform, textureWidth, textureHeight, texture);
        }
        for (JavaPart child : part.children().values()) {
            emitPart(out, child, transform, textureWidth, textureHeight, texture);
        }
    }

    private static void emitCube(
            List<ModelQuad> out,
            JavaCube cube,
            Matrix4f transform,
            float textureWidth,
            float textureHeight,
            String texture) {
        float x1 = cube.x() - cube.growX();
        float y1 = cube.y() - cube.growY();
        float z1 = cube.z() - cube.growZ();
        float x2 = cube.x() + cube.dx() + cube.growX();
        float y2 = cube.y() + cube.dy() + cube.growY();
        float z2 = cube.z() + cube.dz() + cube.growZ();

        if (cube.mirror()) {
            float swap = x2;
            x2 = x1;
            x1 = swap;
        }

        Vertex v7 = new Vertex(x1, y1, z1);
        Vertex v0 = new Vertex(x2, y1, z1);
        Vertex v1 = new Vertex(x2, y2, z1);
        Vertex v2 = new Vertex(x1, y2, z1);
        Vertex v3 = new Vertex(x1, y1, z2);
        Vertex v4 = new Vertex(x2, y1, z2);
        Vertex v5 = new Vertex(x2, y2, z2);
        Vertex v6 = new Vertex(x1, y2, z2);

        float tw = Math.max(1F, textureWidth * cube.texScaleU());
        float th = Math.max(1F, textureHeight * cube.texScaleV());

        float f4 = cube.texU();
        float f5 = cube.texU() + cube.dz();
        float f6 = cube.texU() + cube.dz() + cube.dx();
        float f7 = cube.texU() + cube.dz() + cube.dx() + cube.dx();
        float f8 = cube.texU() + cube.dz() + cube.dx() + cube.dz();
        float f9 = cube.texU() + cube.dz() + cube.dx() + cube.dz() + cube.dx();
        float f10 = cube.texV();
        float f11 = cube.texV() + cube.dz();
        float f12 = cube.texV() + cube.dz() + cube.dy();

        addFace(out, transform, texture, cube.mirror(),
                new Vertex[]{v4,v3,v7,v0}, f5,f10,f6,f11, tw,th);
        addFace(out, transform, texture, cube.mirror(),
                new Vertex[]{v1,v2,v6,v5}, f6,f11,f7,f10, tw,th);
        addFace(out, transform, texture, cube.mirror(),
                new Vertex[]{v7,v3,v6,v2}, f4,f11,f5,f12, tw,th);
        addFace(out, transform, texture, cube.mirror(),
                new Vertex[]{v0,v7,v2,v1}, f5,f11,f6,f12, tw,th);
        addFace(out, transform, texture, cube.mirror(),
                new Vertex[]{v4,v0,v1,v5}, f6,f11,f8,f12, tw,th);
        addFace(out, transform, texture, cube.mirror(),
                new Vertex[]{v3,v4,v5,v6}, f8,f11,f9,f12, tw,th);
    }

    private static void addFace(
            List<ModelQuad> out,
            Matrix4f transform,
            String texture,
            boolean mirror,
            Vertex[] vertices,
            float u1,
            float v1,
            float u2,
            float v2,
            float textureWidth,
            float textureHeight) {
        float[] uvs = new float[]{
                u2 / textureWidth * 16F, v1 / textureHeight * 16F,
                u1 / textureWidth * 16F, v1 / textureHeight * 16F,
                u1 / textureWidth * 16F, v2 / textureHeight * 16F,
                u2 / textureWidth * 16F, v2 / textureHeight * 16F
        };

        if (mirror) {
            reverse(vertices);
            reverseUv(uvs);
        }

        float[] positions = new float[12];
        Vector3f point = new Vector3f();
        for (int i = 0; i < 4; i++) {
            transform.transformPosition(vertices[i].x(), vertices[i].y(), vertices[i].z(), point);

            // Match VanillaEntityModelGenerator.GeometryCollector. ModelPart uses
            // y-down pixel coordinates around y=24; BlueMap3D entity meshes use y-up
            // pixels with the origin at the entity's feet.
            positions[i * 3] = point.x;
            positions[i * 3 + 1] = 24.016F - point.y;
            positions[i * 3 + 2] = -point.z;
        }

        if (degenerate(positions)) return;
        out.add(new ModelQuad(null, null, positions, uvs, texture, 0xFFFFFF));
    }

    private static boolean degenerate(float[] positions) {
        Vector3f a = new Vector3f(
                positions[3] - positions[0],
                positions[4] - positions[1],
                positions[5] - positions[2]);
        Vector3f b = new Vector3f(
                positions[6] - positions[0],
                positions[7] - positions[1],
                positions[8] - positions[2]);
        return a.cross(b).lengthSquared() < 1.0e-10F;
    }

    private static void reverse(Vertex[] values) {
        for (int i = 0; i < values.length / 2; i++) {
            Vertex tmp = values[i];
            values[i] = values[values.length - 1 - i];
            values[values.length - 1 - i] = tmp;
        }
    }

    private static void reverseUv(float[] uv) {
        for (int i = 0; i < 2; i++) {
            int j = 3 - i;
            float u = uv[i * 2];
            float v = uv[i * 2 + 1];
            uv[i * 2] = uv[j * 2];
            uv[i * 2 + 1] = uv[j * 2 + 1];
            uv[j * 2] = u;
            uv[j * 2 + 1] = v;
        }
    }

    private static int countCubes(JavaPart part) {
        int count = part.cubes().size();
        for (JavaPart child : part.children().values()) {
            count += countCubes(child);
        }
        return count;
    }

    record DiagnosticCandidate(String classPath, int score, boolean extractable, String detail) {
    }

    private record Candidate(String path, int score) {
    }

    private record Vertex(float x, float y, float z) {
    }

    private record JavaPose(float x, float y, float z, float xRot, float yRot, float zRot) {
        private static final JavaPose ZERO = new JavaPose(0,0,0,0,0,0);
    }

    private record JavaDeformation(float x, float y, float z) {
        private static final JavaDeformation NONE = new JavaDeformation(0,0,0);

        JavaDeformation extend(float dx, float dy, float dz) {
            return new JavaDeformation(x + dx, y + dy, z + dz);
        }
    }

    private record JavaCube(
            float texU,
            float texV,
            float x,
            float y,
            float z,
            float dx,
            float dy,
            float dz,
            float growX,
            float growY,
            float growZ,
            boolean mirror,
            float texScaleU,
            float texScaleV) {
    }

    private static final class JavaBuilder {
        int texU;
        int texV;
        boolean mirror;
        final List<JavaCube> cubes = new ArrayList<>();

        JavaBuilder texOffs(int u, int v) {
            texU = u;
            texV = v;
            return this;
        }

        JavaBuilder mirror(boolean value) {
            mirror = value;
            return this;
        }

        JavaBuilder addBox(List<Object> args) {
            int offset = 0;
            if (!args.isEmpty() && args.get(0) instanceof String) offset = 1;
            if (args.size() - offset < 6) {
                throw new UnsupportedModel("Unsupported addBox descriptor");
            }

            float x = number(args.get(offset));
            float y = number(args.get(offset + 1));
            float z = number(args.get(offset + 2));
            float dx = number(args.get(offset + 3));
            float dy = number(args.get(offset + 4));
            float dz = number(args.get(offset + 5));

            JavaDeformation deformation = JavaDeformation.NONE;
            boolean boxMirror = mirror;
            float texScaleU = 1F;
            float texScaleV = 1F;

            for (int i = offset + 6; i < args.size(); i++) {
                Object value = unwrap(args.get(i));
                if (value instanceof JavaDeformation found) {
                    deformation = found;
                    if (i + 2 < args.size()
                            && isNumber(args.get(i + 1))
                            && isNumber(args.get(i + 2))) {
                        texScaleU = number(args.get(i + 1));
                        texScaleV = number(args.get(i + 2));
                    }
                    break;
                }
                if (value instanceof Boolean bool) {
                    boxMirror = bool;
                }
            }

            // Named integer-dimension overload ends with explicit texture offsets.
            if (offset == 1 && args.size() >= 10
                    && isIntegral(args.get(args.size() - 2))
                    && isIntegral(args.get(args.size() - 1))) {
                texOffs(integer(args.get(args.size() - 2)), integer(args.get(args.size() - 1)));
            }

            cubes.add(new JavaCube(
                    texU, texV, x,y,z, dx,dy,dz,
                    deformation.x(), deformation.y(), deformation.z(),
                    boxMirror, texScaleU, texScaleV));
            return this;
        }
    }

    private static final class JavaPart {
        final String name;
        final JavaPose pose;
        final List<JavaCube> cubes;
        final Map<String, JavaPart> children = new LinkedHashMap<>();

        JavaPart(String name, JavaPose pose, List<JavaCube> cubes) {
            this.name = name;
            this.pose = pose;
            this.cubes = List.copyOf(cubes);
        }

        boolean isEmpty() {
            if (!cubes.isEmpty()) return false;
            for (JavaPart child : children.values()) {
                if (!child.isEmpty()) return false;
            }
            return true;
        }

        JavaPose pose() {
            return pose;
        }

        List<JavaCube> cubes() {
            return cubes;
        }

        Map<String, JavaPart> children() {
            return children;
        }
    }

    private static final class JavaMesh {
        final JavaPart root = new JavaPart("root", JavaPose.ZERO, List.of());
    }

    private record JavaLayer(JavaPart root, int textureWidth, int textureHeight) {
    }

    private static final class NewValue {
        final String type;
        Object value;

        NewValue(String type) {
            this.type = type;
        }
    }

    private static final class UnsupportedModel extends RuntimeException {
        UnsupportedModel(String message) {
            super(message);
        }
    }

    /**
     * Tiny JVM interpreter for model builder methods. It intentionally supports no
     * arbitrary object interaction and no conditional control flow.
     */
    private static final class Interpreter {
        private final ClassNode owner;
        private final Map<String, MethodNode> methods = new HashMap<>();
        private final Map<String, Object> constants = new HashMap<>();
        private final Set<String> active = new HashSet<>();

        Interpreter(ClassNode owner) {
            this.owner = owner;
            for (MethodNode method : owner.methods) {
                methods.put(method.name + method.desc, method);
            }
            for (FieldNode field : owner.fields) {
                if (field.value != null) constants.put(field.name, field.value);
            }
        }

        Object invoke(MethodNode method, List<Object> arguments, int depth) {
            if (depth > 12) throw new UnsupportedModel("helper recursion too deep");
            String key = method.name + method.desc;
            if (!active.add(key)) throw new UnsupportedModel("recursive helper " + method.name);

            try {
                Type[] argumentTypes = Type.getArgumentTypes(method.desc);
                Object[] locals = new Object[Math.max(method.maxLocals, arguments.size() + 4)];
                int local = 0;
                for (int i = 0; i < arguments.size(); i++) {
                    locals[local] = arguments.get(i);
                    local += argumentTypes[i].getSize();
                }

                ArrayDeque<Object> stack = new ArrayDeque<>();
                AbstractInsnNode instruction = method.instructions.getFirst();
                while (instruction != null) {
                    int opcode = instruction.getOpcode();
                    if (opcode < 0) {
                        instruction = instruction.getNext();
                        continue;
                    }

                    switch (opcode) {
                        case Opcodes.NOP -> {}
                        case Opcodes.ACONST_NULL -> stack.push(NULL);
                        case Opcodes.ICONST_M1 -> stack.push(-1);
                        case Opcodes.ICONST_0 -> stack.push(0);
                        case Opcodes.ICONST_1 -> stack.push(1);
                        case Opcodes.ICONST_2 -> stack.push(2);
                        case Opcodes.ICONST_3 -> stack.push(3);
                        case Opcodes.ICONST_4 -> stack.push(4);
                        case Opcodes.ICONST_5 -> stack.push(5);
                        case Opcodes.FCONST_0 -> stack.push(0F);
                        case Opcodes.FCONST_1 -> stack.push(1F);
                        case Opcodes.FCONST_2 -> stack.push(2F);
                        case Opcodes.DCONST_0 -> stack.push(0D);
                        case Opcodes.DCONST_1 -> stack.push(1D);
                        case Opcodes.BIPUSH, Opcodes.SIPUSH ->
                                stack.push(((IntInsnNode) instruction).operand);
                        case Opcodes.LDC -> stack.push(((LdcInsnNode) instruction).cst);

                        case Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.FLOAD, Opcodes.DLOAD, Opcodes.LLOAD -> {
                            Object value = locals[((VarInsnNode) instruction).var];
                            stack.push(value == null ? NULL : value);
                        }
                        case Opcodes.ASTORE, Opcodes.ISTORE, Opcodes.FSTORE, Opcodes.DSTORE, Opcodes.LSTORE ->
                                locals[((VarInsnNode) instruction).var] = stack.pop();

                        case Opcodes.POP -> stack.pop();
                        case Opcodes.DUP -> stack.push(stack.peek());

                        case Opcodes.I2F, Opcodes.D2F, Opcodes.L2F -> stack.push(number(stack.pop()));
                        case Opcodes.I2D, Opcodes.F2D, Opcodes.L2D ->
                                stack.push((double) number(stack.pop()));
                        case Opcodes.IADD, Opcodes.FADD, Opcodes.DADD ->
                                binary(stack, '+');
                        case Opcodes.ISUB, Opcodes.FSUB, Opcodes.DSUB ->
                                binary(stack, '-');
                        case Opcodes.IMUL, Opcodes.FMUL, Opcodes.DMUL ->
                                binary(stack, '*');
                        case Opcodes.IDIV, Opcodes.FDIV, Opcodes.DDIV ->
                                binary(stack, '/');
                        case Opcodes.FNEG, Opcodes.DNEG, Opcodes.INEG ->
                                stack.push(-number(stack.pop()));

                        case Opcodes.NEW -> stack.push(new NewValue(((TypeInsnNode) instruction).desc));
                        case Opcodes.CHECKCAST -> {}

                        case Opcodes.GETSTATIC ->
                                handleGetStatic(stack, (FieldInsnNode) instruction);

                        case Opcodes.INVOKESPECIAL, Opcodes.INVOKESTATIC,
                                Opcodes.INVOKEVIRTUAL, Opcodes.INVOKEINTERFACE ->
                                invokeInstruction(stack, (MethodInsnNode) instruction, depth);

                        case Opcodes.ARETURN, Opcodes.IRETURN, Opcodes.FRETURN,
                                Opcodes.DRETURN, Opcodes.LRETURN ->
                                { return unwrap(stack.pop()); }
                        case Opcodes.RETURN -> { return null; }

                        case Opcodes.GOTO,
                                Opcodes.IFEQ, Opcodes.IFNE, Opcodes.IFLT, Opcodes.IFGE,
                                Opcodes.IFGT, Opcodes.IFLE, Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE,
                                Opcodes.IF_ICMPLT, Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT,
                                Opcodes.IF_ICMPLE, Opcodes.IFNULL, Opcodes.IFNONNULL ->
                                throw new UnsupportedModel("conditional/control-flow bytecode");

                        default -> throw new UnsupportedModel(
                                "opcode " + opcode + " in " + method.name);
                    }

                    instruction = instruction.getNext();
                }
                return null;
            } finally {
                active.remove(key);
            }
        }

        private void invokeInstruction(
                ArrayDeque<Object> stack, MethodInsnNode call, int depth) {
            Type[] argumentTypes = Type.getArgumentTypes(call.desc);
            List<Object> args = new ArrayList<>(argumentTypes.length);
            for (int i = argumentTypes.length - 1; i >= 0; i--) {
                args.add(0, unwrap(stack.pop()));
            }

            Object receiver = null;
            if (call.getOpcode() != Opcodes.INVOKESTATIC) {
                receiver = stack.pop();
            }

            Object result;
            if (call.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(call.name)) {
                result = construct(receiver, call.owner, args);
            } else if (call.getOpcode() == Opcodes.INVOKESTATIC) {
                result = invokeStatic(call, args, depth);
            } else {
                result = invokeVirtual(unwrap(receiver), call, args);
            }

            if (Type.getReturnType(call.desc).getSort() != Type.VOID) {
                stack.push(result == null ? NULL : result);
            }
        }

        private Object construct(Object receiver, String type, List<Object> args) {
            if (!(receiver instanceof NewValue value)) {
                throw new UnsupportedModel("constructor receiver " + type);
            }

            Object built;
            if (MESH.equals(type)) {
                built = new JavaMesh();
            } else if (BUILDER.equals(type)) {
                built = new JavaBuilder();
            } else if (DEFORMATION.equals(type)) {
                if (args.size() == 1) {
                    float grow = number(args.get(0));
                    built = new JavaDeformation(grow, grow, grow);
                } else if (args.size() == 3) {
                    built = new JavaDeformation(
                            number(args.get(0)), number(args.get(1)), number(args.get(2)));
                } else {
                    throw new UnsupportedModel("CubeDeformation constructor");
                }
            } else {
                throw new UnsupportedModel("constructor " + type);
            }

            value.value = built;
            return null;
        }

        private Object invokeStatic(MethodInsnNode call, List<Object> args, int depth) {
            if (BUILDER.equals(call.owner) && "create".equals(call.name)) {
                return new JavaBuilder();
            }
            if (POSE.equals(call.owner)) {
                return switch (call.name) {
                    case "offset" -> new JavaPose(
                            number(args.get(0)), number(args.get(1)), number(args.get(2)),
                            0,0,0);
                    case "rotation" -> new JavaPose(
                            0,0,0,
                            number(args.get(0)), number(args.get(1)), number(args.get(2)));
                    case "offsetAndRotation" -> new JavaPose(
                            number(args.get(0)), number(args.get(1)), number(args.get(2)),
                            number(args.get(3)), number(args.get(4)), number(args.get(5)));
                    default -> throw new UnsupportedModel("PartPose." + call.name);
                };
            }
            if (LAYER.equals(call.owner) && "create".equals(call.name)) {
                JavaMesh mesh = cast(args.get(0), JavaMesh.class, "LayerDefinition mesh");
                return new JavaLayer(
                        mesh.root,
                        integer(args.get(1)),
                        integer(args.get(2)));
            }
            if ("java/lang/Math".equals(call.owner)) {
                return switch (call.name) {
                    case "toRadians" -> Math.toRadians(doubleNumber(args.get(0)));
                    default -> throw new UnsupportedModel("Math." + call.name);
                };
            }
            if ("java/util/Set".equals(call.owner) && "of".equals(call.name)) {
                return Set.copyOf(args);
            }
            if ("java/util/EnumSet".equals(call.owner)
                    && ("of".equals(call.name) || "allOf".equals(call.name))) {
                return Set.copyOf(args);
            }

            // Common vanilla base-model factories (HumanoidModel.createMesh,
            // QuadrupedModel.createBodyMesh, etc.) return a MeshDefinition. Starting with
            // an empty root is safe for models that replace/add their own parts; if they
            // depend on an inherited child, getChild() below fails closed and the model
            // falls back rather than rendering corrupt geometry.
            Type returnType = Type.getReturnType(call.desc);
            if (call.owner.startsWith("net/minecraft/client/model/")
                    && returnType.getSort() == Type.OBJECT
                    && MESH.equals(returnType.getInternalName())) {
                return new JavaMesh();
            }

            if (owner.name.equals(call.owner)) {
                MethodNode helper = methods.get(call.name + call.desc);
                if (helper == null || (helper.access & Opcodes.ACC_STATIC) == 0) {
                    throw new UnsupportedModel("missing static helper " + call.name);
                }
                return invoke(helper, args, depth + 1);
            }

            throw new UnsupportedModel("static call " + call.owner + "." + call.name);
        }

        private Object invokeVirtual(Object receiver, MethodInsnNode call, List<Object> args) {
            if (receiver instanceof JavaMesh mesh && MESH.equals(call.owner)
                    && "getRoot".equals(call.name)) {
                return mesh.root;
            }
            if (receiver instanceof JavaBuilder builder && BUILDER.equals(call.owner)) {
                return switch (call.name) {
                    case "texOffs" -> builder.texOffs(
                            integer(args.get(0)), integer(args.get(1)));
                    case "mirror" -> builder.mirror(
                            args.isEmpty() || booleanValue(args.get(0)));
                    case "addBox" -> builder.addBox(args);
                    default -> throw new UnsupportedModel("CubeListBuilder." + call.name);
                };
            }
            if (receiver instanceof JavaPart part && PART.equals(call.owner)) {
                if ("addOrReplaceChild".equals(call.name)) {
                    String name = String.valueOf(args.get(0));
                    JavaBuilder builder = cast(args.get(1), JavaBuilder.class, "child cubes");
                    JavaPose pose = cast(args.get(2), JavaPose.class, "child pose");
                    JavaPart child = new JavaPart(name, pose, builder.cubes);
                    JavaPart previous = part.children.put(name, child);
                    if (previous != null) child.children.putAll(previous.children);
                    return child;
                }
                if ("getChild".equals(call.name)) {
                    return part.children.get(String.valueOf(args.get(0)));
                }
            }
            if (receiver instanceof JavaDeformation deformation
                    && DEFORMATION.equals(call.owner)
                    && "extend".equals(call.name)) {
                if (args.size() == 1) {
                    float grow = number(args.get(0));
                    return deformation.extend(grow, grow, grow);
                }
                if (args.size() == 3) {
                    return deformation.extend(
                            number(args.get(0)), number(args.get(1)), number(args.get(2)));
                }
            }

            throw new UnsupportedModel(
                    "virtual call " + call.owner + "." + call.name);
        }

        private void handleGetStatic(
                ArrayDeque<Object> stack, FieldInsnNode field) {
            if (POSE.equals(field.owner) && "ZERO".equals(field.name)) {
                stack.push(JavaPose.ZERO);
                return;
            }
            if (DEFORMATION.equals(field.owner) && "NONE".equals(field.name)) {
                stack.push(JavaDeformation.NONE);
                return;
            }
            if (owner.name.equals(field.owner) && constants.containsKey(field.name)) {
                Object value = constants.get(field.name);
                stack.push(value == null ? NULL : value);
                return;
            }
            if ("java/lang/Math".equals(field.owner) && "PI".equals(field.name)) {
                stack.push(Math.PI);
                return;
            }
            if ("net/minecraft/util/Mth".equals(field.owner)) {
                if ("PI".equals(field.name)) {
                    stack.push((float) Math.PI);
                    return;
                }
                if ("DEG_TO_RAD".equals(field.name)) {
                    stack.push((float) (Math.PI / 180.0));
                    return;
                }
            }
            // Direction values only control omitted cube faces. Keeping all six faces is
            // visually safe and avoids linking to client-side model types.
            if ("net/minecraft/core/Direction".equals(field.owner)) {
                stack.push(field.name);
                return;
            }
            throw new UnsupportedModel("static field " + field.owner + "." + field.name);
        }

        private static void binary(ArrayDeque<Object> stack, char operation) {
            float right = number(stack.pop());
            float left = number(stack.pop());
            stack.push(switch (operation) {
                case '+' -> left + right;
                case '-' -> left - right;
                case '*' -> left * right;
                case '/' -> left / right;
                default -> throw new IllegalStateException();
            });
        }
    }

    private static Object unwrap(Object value) {
        if (value == NULL) return null;
        if (value instanceof NewValue pending) {
            if (pending.value == null) {
                throw new UnsupportedModel("uninitialized " + pending.type);
            }
            return pending.value;
        }
        return value;
    }

    private static <T> T cast(Object value, Class<T> type, String label) {
        Object actual = unwrap(value);
        if (!type.isInstance(actual)) {
            throw new UnsupportedModel(label + " is " + String.valueOf(actual));
        }
        return type.cast(actual);
    }

    private static boolean isNumber(Object value) {
        return unwrap(value) instanceof Number;
    }

    private static boolean isIntegral(Object value) {
        Object actual = unwrap(value);
        return actual instanceof Byte || actual instanceof Short
                || actual instanceof Integer || actual instanceof Long;
    }

    private static float number(Object value) {
        Object actual = unwrap(value);
        if (actual instanceof Number number) return number.floatValue();
        throw new UnsupportedModel("expected number, got " + String.valueOf(actual));
    }

    private static double doubleNumber(Object value) {
        Object actual = unwrap(value);
        if (actual instanceof Number number) return number.doubleValue();
        throw new UnsupportedModel("expected number, got " + String.valueOf(actual));
    }

    private static int integer(Object value) {
        Object actual = unwrap(value);
        if (actual instanceof Number number) return number.intValue();
        throw new UnsupportedModel("expected integer, got " + String.valueOf(actual));
    }

    private static boolean booleanValue(Object value) {
        Object actual = unwrap(value);
        if (actual instanceof Boolean bool) return bool;
        if (actual instanceof Number number) return number.intValue() != 0;
        throw new UnsupportedModel("expected boolean, got " + String.valueOf(actual));
    }

    private static String compact(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = Character.toLowerCase(value.charAt(i));
            if (Character.isLetterOrDigit(ch)) out.append(ch);
        }
        return out.toString();
    }

    private static String leaf(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }
}
