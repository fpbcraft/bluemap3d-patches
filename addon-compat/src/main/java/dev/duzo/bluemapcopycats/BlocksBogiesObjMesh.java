package dev.duzo.bluemapcopycats;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Loads the real Blocks &amp; Bogies OBJ partials from the installed mod JAR.
 *
 * <p>No third-party assets are copied into the compatibility addon. OBJ models
 * remain part of the installed mod, so replacement resource packs also work.
 * Loading is lazy and bounded; parsed immutable meshes are cached per path.
 */
final class BlocksBogiesObjMesh {
    private static final String ROOT = "assets/create_bb/models/block/";
    private static final Map<String, List<Triangle>> CACHE = new ConcurrentHashMap<>();
    private static volatile Path source;
    private static volatile boolean searched;

    private BlocksBogiesObjMesh() {}

    static List<Triangle> load(String model) {
        if (!model.startsWith("bogie/") || model.contains("..")) return List.of();
        return CACHE.computeIfAbsent(model, key -> {
            Path jar = locateJar();
            if (jar == null) return List.of();
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                ZipEntry entry = zip.getEntry(ROOT + key + ".obj");
                if (entry == null || entry.getSize() > 8_000_000) return List.of();
                try (InputStream stream = zip.getInputStream(entry)) {
                    return parse(new BufferedReader(
                            new InputStreamReader(stream, StandardCharsets.UTF_8)));
                }
            } catch (IOException | IllegalArgumentException failure) {
                return List.of();
            }
        });
    }

    static boolean installed() {
        return locateJar() != null;
    }

    private static synchronized Path locateJar() {
        if (searched) return source;
        searched = true;
        Path mods = Path.of("mods");
        if (!Files.isDirectory(mods)) return null;
        try (Stream<Path> files = Files.list(mods)) {
            List<Path> jars = files.filter(path -> path.getFileName().toString().endsWith(".jar"))
                    .sorted().toList();
            for (Path path : jars) {
                try (ZipFile jar = new ZipFile(path.toFile())) {
                    if (jar.getEntry(ROOT + "bogie/large/shared/wheels.obj") != null
                            && jar.getEntry(ROOT + "bogie/textures.mtl") != null) {
                        source = path;
                        return source;
                    }
                } catch (IOException ignored) {
                    // Ignore unrelated or corrupt mod jars.
                }
            }
        } catch (IOException ignored) {
            // Use the simpler fallback when local mod assets cannot be read.
        }
        return null;
    }

    static List<Triangle> parse(String obj) {
        return parse(new BufferedReader(new StringReader(obj)));
    }

    private static List<Triangle> parse(BufferedReader reader) {
        List<float[]> vertices = new ArrayList<>();
        List<float[]> uvs = new ArrayList<>();
        List<Triangle> faces = new ArrayList<>();
        String material = "frame";
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("v ")) {
                    if (vertices.size() > 200_000) break;
                    String[] p = trimmed.substring(2).trim().split("\\s+");
                    if (p.length >= 3)
                        vertices.add(new float[]{Float.parseFloat(p[0]),Float.parseFloat(p[1]),Float.parseFloat(p[2])});
                } else if (trimmed.startsWith("vt ")) {
                    String[] p = trimmed.substring(3).trim().split("\\s+");
                    if (p.length >= 2)
                        uvs.add(new float[]{Float.parseFloat(p[0]),1f-Float.parseFloat(p[1])});
                } else if (trimmed.startsWith("usemtl ")) {
                    material = trimmed.substring(7).trim();
                } else if (trimmed.startsWith("f ")) {
                    String[] corners = trimmed.substring(2).trim().split("\\s+");
                    if (corners.length < 3 || corners.length > 16) continue;
                    ObjCorner[] points = new ObjCorner[corners.length];
                    for (int i=0;i<corners.length;i++) {
                        String[] entries = corners[i].split("/");
                        int v = index(entries[0], vertices.size());
                        int uv = entries.length > 1 && !entries[1].isEmpty()
                                ? index(entries[1], uvs.size()) : -1;
                        points[i] = new ObjCorner(vertices.get(v),
                                uv >= 0 ? uvs.get(uv) : new float[]{0,0});
                    }
                    for (int i=1;i+1<points.length;i++)
                        faces.add(new Triangle(points[0], points[i], points[i+1], material));
                    if (faces.size() >= 80_000) break;
                }
            }
        } catch (IOException | RuntimeException malformed) {
            return List.of();
        }
        return List.copyOf(faces);
    }

    private static int index(String text,int length) {
        int value=Integer.parseInt(text);
        int actual=value>0 ? value-1 : length+value;
        if (actual<0 || actual>=length) throw new IllegalArgumentException("OBJ index out of bounds");
        return actual;
    }

    record ObjCorner(float[] xyz,float[] uv) {}
    record Triangle(ObjCorner a,ObjCorner b,ObjCorner c,String material) {}
}
