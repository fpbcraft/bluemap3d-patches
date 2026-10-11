package dev.duzo.bluemapcopycats;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BlocksBogiesObjMeshTest {
    @Test
    void triangulatesActualObjQuadsWithMaterialAndUvPreserved() {
        String obj = """
                # Blender export
                v -1 0 0
                v 1 0 0
                v 1 1 0
                v -1 1 0
                vt 0 0
                vt 1 0
                vt 1 1
                vt 0 1
                usemtl frame2
                f 1/1 2/2 3/3 4/4
                """;
        var triangles=BlocksBogiesObjMesh.parse(obj);
        assertEquals(2,triangles.size());
        assertEquals("frame2",triangles.getFirst().material());
        assertEquals(-1f,triangles.getFirst().a().xyz()[0]);
        assertEquals(1f,triangles.getFirst().a().uv()[1]);
        assertEquals(0f,triangles.getFirst().c().uv()[1]);
    }

    @Test
    void supportsSharedObjNegativeIndices() {
        String obj = """
                v 0 0 0
                v 1 0 0
                v 1 1 0
                usemtl wheels
                f -3 -2 -1
                """;
        var triangles=BlocksBogiesObjMesh.parse(obj);
        assertEquals(1,triangles.size());
        assertEquals("wheels",triangles.getFirst().material());
    }

    @Test
    void rejectsMalformedObjWithoutReturningPartialGeometry() {
        assertTrue(BlocksBogiesObjMesh.parse("v 0 0 0\nf 1 2 3").isEmpty());
        assertTrue(BlocksBogiesObjMesh.load("../../textures").isEmpty());
    }
}
