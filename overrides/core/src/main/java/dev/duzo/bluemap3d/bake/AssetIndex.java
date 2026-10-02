package dev.duzo.bluemap3d.bake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.neoforged.fml.ModList;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.function.Predicate;

/**
 * Where block models and textures are read from on a server.
 *
 * <p>This is the part of server-side meshing that has no clean answer, so it is worth
 * being explicit about why. A dedicated server has no client assets: there is no
 * {@code assets/minecraft/models} anywhere on its disk by default, because the vanilla
 * server jar does not ship them. Mod jars are different - they are the same jar on both
 * sides, so their models and textures <em>are</em> present and reachable through the
 * mod class loader.
 *
 * <p>So the index searches, in priority order:
 * <ol>
 *   <li>roots the admin configured, e.g. a resource pack zip;</li>
 *   <li>resource packs in BlueMap's directory, so a pack overrides vanilla;</li>
 *   <li><b>BlueMap's {@code resourceExtensions.zip}</b> - see below;</li>
 *   <li>BlueMap's own directory, which holds the vanilla client jar it downloaded.
 *       BlueMap needs that to render terrain at all, so on any server where BlueMap is
 *       working, vanilla assets are already sitting there;</li>
 *   <li>the mod class loader, which covers every installed mod.</li>
 * </ol>
 *
 * <h2>Block entities</h2>
 * Chests, beds, shulker boxes, signs and banners have no model geometry in vanilla -
 * the client draws them with a block-entity renderer, and their block model is a stub
 * with only a particle texture. Falling back to that texture is worse than nothing: it
 * draws a chest as a cube of plain oak planks.
 *
 * <p>BlueMap solved this already. It ships hand-authored blockstates and models for
 * exactly those blocks in {@code resourceExtensions.zip} inside its own jar, and
 * overlays them on top of vanilla. Reading the same zip is why BlueMap3D gets block
 * entities for free rather than needing its own hand-built geometry. It has to sit
 * <em>above</em> the client jar in the search order, because vanilla does ship a
 * {@code chest.json} blockstate and the extension is what replaces it.
 *
 * <p>Modded block entities need much less help. A mod almost always ships an ordinary
 * blockstate and model and uses its renderer only for the moving part - a CC turtle is
 * {@code computercraft:block/turtle_normal}, whose parent carries the real body and
 * backpack cuboids, and Create's tanks, shafts and cogwheels are all plain variants. Mod
 * jars are on the class loader, so those resolve with no configuration at all.
 *
 * <p>What genuinely has no geometry is a block drawn entirely in code. Those get a
 * map-colour cube, and the fix needs no code: the configured roots are searched first,
 * so dropping in a zip with hand-authored models for those blocks overrides them - the
 * same mechanism BlueMap uses on vanilla.
 *
 * <p>Whatever is still missing falls back to {@link MapColorSource}, per block, so a
 * gap costs texture fidelity on that block and nothing else.
 */
public final class AssetIndex implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Assets");

    private final List<Path> roots;
    private final List<FileSystem> ownedFileSystems;
    private final ClassLoader modLoader;
    private final Map<String, byte[]> cache = new HashMap<>();
    private final Map<String, List<String>> pathCache = new HashMap<>();
    private static final byte[] MISSING = new byte[0];

    private AssetIndex(List<Path> roots, List<FileSystem> ownedFileSystems, ClassLoader modLoader) {
        this.roots = roots;
        this.ownedFileSystems = ownedFileSystems;
        this.modLoader = modLoader;
    }

    /**
     * Opens an index.
     *
     * @param configured  extra jars, zips or directories, highest priority first
     * @param blueMapRoot BlueMap's directory (the parent of its web root), or
     *                    {@code null} if unknown
     */
    public static AssetIndex open(List<Path> configured, Path blueMapRoot) {
        List<Path> roots = new ArrayList<>();
        List<FileSystem> owned = new ArrayList<>();

        List<Path> candidates = new ArrayList<>(configured);

        // Resource packs first, so a pack can override anything below it.
        if (blueMapRoot != null) {
            candidates.addAll(listIfDirectory(blueMapRoot.resolve("resourcepacks")));
            candidates.addAll(listIfDirectory(blueMapRoot.resolve("packs")));
        }

        // Then BlueMap's block-entity geometry, above the client jar so its overrides win.
        Path extensions = extractResourceExtensions(blueMapRoot);
        if (extensions != null) {
            candidates.add(extensions);
        }

        // Then the vanilla client jar BlueMap downloaded, and anything else lying about.
        if (blueMapRoot != null) {
            candidates.addAll(listIfDirectory(blueMapRoot));
            candidates.addAll(listIfDirectory(blueMapRoot.resolve("resources")));
        }

        // Finally, index every loaded mod file as an enumerable asset root. The class
        // loader is still retained as the exact-path fallback, but it cannot list files;
        // entity model discovery needs to inspect arbitrary geo/texture names in mod jars.
        try {
            for (var modFileInfo : ModList.get().getModFiles()) {
                Path modPath = modFileInfo.getFile().getFilePath();
                if (modPath != null) {
                    candidates.add(modPath);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.debug("Could not enumerate loaded mod files for asset discovery: {}", e.toString());
        }

        for (Path candidate : new LinkedHashSet<>(candidates)) {
            try {
                if (Files.isDirectory(candidate)) {
                    roots.add(candidate);
                } else if (Files.isRegularFile(candidate) && isArchive(candidate)) {
                    FileSystem fs = FileSystems.newFileSystem(candidate, (ClassLoader) null);
                    owned.add(fs);
                    roots.add(fs.getPath("/"));
                }
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Skipping asset source {}: {}", candidate, e.toString());
            }
        }

        if (!roots.isEmpty()) {
            LOGGER.info("Indexed {} asset source(s) for block models", roots.size());
        } else {
            LOGGER.info("No asset sources found; textured meshing will rely on mod jars only");
        }
        return new AssetIndex(roots, owned, AssetIndex.class.getClassLoader());
    }

    /**
     * Reads a resource-pack path such as
     * {@code assets/minecraft/models/block/stone.json}.
     *
     * @return the bytes, or {@code null} if no source has it
     */
    public byte[] read(String path) {
        byte[] hit = cache.get(path);
        if (hit != null) {
            return hit == MISSING ? null : hit;
        }

        byte[] found = readUncached(path);
        cache.put(path, found == null ? MISSING : found);
        return found;
    }

    /**
     * Lists resource paths beneath a prefix, in source-priority order.
     *
     * <p>This is intentionally separate from {@link #read(String)}: classloaders can
     * resolve a known resource but cannot portably enumerate every entry in a mod jar.
     * Loaded mod jars are therefore added to {@code roots} in {@link #open(List, Path)}
     * so data-driven entity models can be discovered without hard-coded filenames.
     */
    public List<String> pathsUnder(String prefix, int limit) {
        if (limit <= 0) return List.of();

        String normalized = prefix.replace('\\', '/');
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);

        String cacheKey = normalized + "|" + limit;
        List<String> cached = pathCache.get(cacheKey);
        if (cached != null) return cached;

        LinkedHashSet<String> found = new LinkedHashSet<>();
        for (Path root : roots) {
            if (found.size() >= limit) break;
            try {
                Path base = root.resolve(normalized);
                if (!Files.exists(base)) continue;

                try (var stream = Files.walk(base)) {
                    var iterator = stream.filter(Files::isRegularFile).iterator();
                    while (iterator.hasNext() && found.size() < limit) {
                        Path file = iterator.next();
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        found.add(relative);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Broken/closed zip root or malformed entry. Continue with lower priority roots.
            }
        }

        List<String> result = List.copyOf(found);
        pathCache.put(cacheKey, result);
        return result;
    }

    /** Convenience filtered listing with the same stable source order. */
    public List<String> findPaths(String prefix, Predicate<String> filter, int limit) {
        if (filter == null) return pathsUnder(prefix, limit);
        List<String> out = new ArrayList<>();
        for (String path : pathsUnder(prefix, Math.max(limit, 1) * 4)) {
            if (filter.test(path)) {
                out.add(path);
                if (out.size() >= limit) break;
            }
        }
        return List.copyOf(out);
    }

    private byte[] readUncached(String path) {
        for (Path root : roots) {
            try {
                Path file = root.resolve(path);
                if (Files.isRegularFile(file)) {
                    return Files.readAllBytes(file);
                }
            } catch (IOException | RuntimeException e) {
                // A malformed zip entry name, or a root that went away. Try the next.
            }
        }
        // Mod jars, via the loader that can see all of them.
        try (InputStream in = modLoader.getResourceAsStream(path)) {
            if (in != null) {
                return readAll(in);
            }
        } catch (IOException e) {
            // fall through
        }
        return null;
    }

    /** Whether anything at all is available beyond the mod class loader. */
    public boolean hasExternalRoots() {
        return !roots.isEmpty();
    }

    @Override
    public void close() {
        for (FileSystem fs : ownedFileSystems) {
            try {
                fs.close();
            } catch (IOException e) {
                // Nothing useful to do while shutting down.
            }
        }
        ownedFileSystems.clear();
        roots.clear();
        cache.clear();
        pathCache.clear();
    }

    /**
     * Finds BlueMap's {@code resourceExtensions.zip}, the source of block-entity
     * geometry.
     *
     * <p>Prefers the copy BlueMap extracted next to its config, and falls back to
     * reading it straight off BlueMap's jar on the classpath - which is the more
     * reliable of the two, since it does not depend on where BlueMap chose to unpack.
     * A {@code FileSystem} cannot be opened over a stream, so the classpath copy is
     * spilled to a temp file that is deleted on exit.
     *
     * @return a path to the zip, or {@code null} if BlueMap is not providing one
     */
    private static Path extractResourceExtensions(Path blueMapRoot) {
        if (blueMapRoot != null) {
            Path extracted = blueMapRoot.resolve("resourceExtensions.zip");
            if (Files.isRegularFile(extracted)) {
                return extracted;
            }
        }
        try (InputStream in = AssetIndex.class.getResourceAsStream(
                "/de/bluecolored/bluemap/resourceExtensions.zip")) {
            if (in == null) {
                LOGGER.debug("BlueMap's resourceExtensions.zip is not on the classpath; "
                        + "chests and other block entities will fall back to map colours");
                return null;
            }
            Path temp = Files.createTempFile("bluemap3d-resource-extensions", ".zip");
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Using BlueMap's resourceExtensions.zip for block-entity geometry");
            return temp;
        } catch (IOException e) {
            LOGGER.warn("Could not read BlueMap's resourceExtensions.zip: {}", e.toString());
            return null;
        }
    }

    private static List<Path> listIfDirectory(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(p -> Files.isDirectory(p) || isArchive(p))
                    // Core's own hide-pack maps blocks to an empty model so BlueMap leaves
                    // them out of its tiles. Reading it back here would find that empty
                    // model and mesh nothing - hiding the very objects the pack exists to
                    // let us draw live.
                    .filter(p -> !p.getFileName().toString()
                            .startsWith(dev.duzo.bluemap3d.publish.HiddenBlockPack.PACK_NAME))
                    .toList();
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static boolean isArchive(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
