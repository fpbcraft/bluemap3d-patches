package dev.duzo.bluemap3d.bake;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaEntityModelSourceTest {

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
    private static final String LAYER =
            "net/minecraft/client/model/geom/builders/LayerDefinition";

    @Test
    void matchesSmallSpellingDifferencesInModelClassNames() {
        assertTrue(JavaEntityModelSource.javaNameScoreForTest(
                "spiky_bug", "SpikebugModel.class") > 0);
    }

    @Test
    void doesNotGuessAmongAmbiguousSharedHorseModels() {
        assertEquals(0, JavaEntityModelSource.javaNameScoreForTest(
                "belgian_horse", "MediumHorseModel.class"));
    }

    @Test
    void extractsBlockbenchStyleModelWithStaticPartNames() {
        byte[] model = modelClass("test/OstrichModel", FactoryKind.ZERO_ARG);
        assertEquals(1, JavaEntityModelSource.extractCubeCountForTest(model));
    }

    @Test
    void extractsParameterizedDeformationFactory() {
        byte[] model = modelClass("test/ShibaModel", FactoryKind.PARAMETERIZED);
        assertEquals(1, JavaEntityModelSource.extractCubeCountForTest(model));
    }

    @Test
    void extractsModelThatStartsFromVanillaHumanoidMesh() {
        byte[] model = modelClass("test/GuardModel", FactoryKind.HUMANOID_BASE);
        assertEquals(1, JavaEntityModelSource.extractCubeCountForTest(model));
    }

    private static byte[] modelClass(String owner, FactoryKind kind) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
        cw.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
                "BODY",
                "Ljava/lang/String;",
                null,
                "body").visitEnd();

        String desc = kind == FactoryKind.PARAMETERIZED
                ? "(L" + DEFORMATION + ";)L" + LAYER + ";"
                : "()L" + LAYER + ";";
        MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                kind == FactoryKind.PARAMETERIZED ? "createBodyLayer" : "getTexturedModelData",
                desc,
                null,
                null);
        mv.visitCode();

        int meshLocal;
        if (kind == FactoryKind.HUMANOID_BASE) {
            mv.visitFieldInsn(Opcodes.GETSTATIC, DEFORMATION, "NONE", "L" + DEFORMATION + ";");
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitMethodInsn(
                    Opcodes.INVOKESTATIC,
                    "net/minecraft/client/model/HumanoidModel",
                    "createMesh",
                    "(L" + DEFORMATION + ";F)L" + MESH + ";",
                    false);
            meshLocal = 0;
            mv.visitVarInsn(Opcodes.ASTORE, meshLocal);
        } else {
            mv.visitTypeInsn(Opcodes.NEW, MESH);
            mv.visitInsn(Opcodes.DUP);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, MESH, "<init>", "()V", false);
            meshLocal = kind == FactoryKind.PARAMETERIZED ? 1 : 0;
            mv.visitVarInsn(Opcodes.ASTORE, meshLocal);
        }

        int rootLocal = meshLocal + 1;
        mv.visitVarInsn(Opcodes.ALOAD, meshLocal);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, MESH, "getRoot", "()L" + PART + ";", false);
        mv.visitVarInsn(Opcodes.ASTORE, rootLocal);

        mv.visitVarInsn(Opcodes.ALOAD, rootLocal);
        mv.visitFieldInsn(Opcodes.GETSTATIC, owner, "BODY", "Ljava/lang/String;");
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, BUILDER, "create", "()L" + BUILDER + ";", false);
        mv.visitIntInsn(Opcodes.BIPUSH, 4);
        mv.visitIntInsn(Opcodes.BIPUSH, 8);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL, BUILDER, "texOffs",
                "(II)L" + BUILDER + ";", false);

        // addBox(-2, -4, -3, 4, 8, 6, deformation)
        mv.visitLdcInsn(-2.0F);
        mv.visitLdcInsn(-4.0F);
        mv.visitLdcInsn(-3.0F);
        mv.visitLdcInsn(4.0F);
        mv.visitLdcInsn(8.0F);
        mv.visitLdcInsn(6.0F);
        if (kind == FactoryKind.PARAMETERIZED) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
        } else {
            mv.visitTypeInsn(Opcodes.NEW, DEFORMATION);
            mv.visitInsn(Opcodes.DUP);
            mv.visitInsn(Opcodes.FCONST_0);
            mv.visitMethodInsn(
                    Opcodes.INVOKESPECIAL, DEFORMATION, "<init>", "(F)V", false);
        }
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                BUILDER,
                "addBox",
                "(FFFFFFL" + DEFORMATION + ";)L" + BUILDER + ";",
                false);

        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitLdcInsn(24.0F);
        mv.visitInsn(Opcodes.FCONST_0);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, POSE, "offset", "(FFF)L" + POSE + ";", false);
        mv.visitMethodInsn(
                Opcodes.INVOKEVIRTUAL,
                PART,
                "addOrReplaceChild",
                "(Ljava/lang/String;L" + BUILDER + ";L" + POSE + ";)L" + PART + ";",
                false);
        mv.visitInsn(Opcodes.POP);

        mv.visitVarInsn(Opcodes.ALOAD, meshLocal);
        mv.visitIntInsn(Opcodes.BIPUSH, 64);
        mv.visitIntInsn(Opcodes.BIPUSH, 64);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                LAYER,
                "create",
                "(L" + MESH + ";II)L" + LAYER + ";",
                false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private enum FactoryKind {
        ZERO_ARG,
        PARAMETERIZED,
        HUMANOID_BASE
    }
}
