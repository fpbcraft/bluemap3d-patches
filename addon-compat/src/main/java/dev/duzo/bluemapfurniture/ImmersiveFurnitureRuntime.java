package dev.duzo.bluemapfurniture;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import de.bluecolored.bluenbt.BlueNBT;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.GZIPInputStream;

/**
 * Resolves Immersive Furniture data for BlueMap's asynchronous terrain renderer.
 *
 * <p>Low-memory furniture only stores an integer blockstate identifier in the chunk.
 * The identifier-to-hash registry is persisted in {@code data/immersive_furniture.dat},
 * while the actual FurnitureData lives in
 * {@code immersive_furniture/hash/<hash>.nbt}. Reading those files directly keeps the
 * BlueMap addon independent from NeoForge/mod classloader visibility. A reflective
 * runtime bridge remains as a fallback for unusual setups.
 */
final class ImmersiveFurnitureRuntime {

    private static final BlueNBT NBT = new BlueNBT();

    private static final Map<String, ImmersiveFurnitureData.Definition> BY_HASH =
            new ConcurrentHashMap<>();
    private static final Map<Integer, String> IDENTIFIER_TO_HASH =
            new ConcurrentHashMap<>();
    private static final Set<String> TRACED = ConcurrentHashMap.newKeySet();

    private static volatile List<Path> worldRoots = List.of();
    private static volatile Api api;
    private static volatile boolean unavailableLogged;

    private ImmersiveFurnitureRuntime() {
    }

    static void configureWorldRoots(Iterable<Path> roots) {
        LinkedHashSet<Path> normalized = new LinkedHashSet<>();
        for (Path root : roots) {
            if (root == null) continue;
            normalized.add(root.toAbsolutePath().normalize());
        }

        worldRoots = List.copyOf(normalized);
        BY_HASH.clear();
        IDENTIFIER_TO_HASH.clear();

        int registries = 0;
        int mappings = 0;
        for (Path root : worldRoots) {
            Map<Integer, String> loaded = loadRegistry(root);
            if (!loaded.isEmpty()) {
                registries++;
                IDENTIFIER_TO_HASH.putAll(loaded);
                mappings += loaded.size();
            }
        }

        Logger.global.logInfo(String.format(
                "Immersive Furniture world data ready: roots=%s registryFiles=%s identifiers=%s",
                worldRoots.size(),
                registries,
                mappings));
    }

    static ImmersiveFurnitureData.Definition resolve(
            BlockNeighborhood block, ImmersiveFurnitureBlockEntity entity) {
        ImmersiveFurnitureData.Definition inline =
                ImmersiveFurnitureData.decodeNbt(entity.furniture());
        if (inline != null && !inline.isEmpty()) {
            trace(block, "inline-nbt", null, inline);
            return inline;
        }

        String hash = entity.furnitureHash();
        String source = "block-entity-hash";
        if (hash == null || hash.isBlank()) {
            hash = hashForIdentifier(block);
            source = "identifier";
        }
        if (hash == null || hash.isBlank()) {
            trace(block, "unresolved", null, inline);
            return inline;
        }

        ImmersiveFurnitureData.Definition cached = BY_HASH.get(hash);
        if (cached != null) {
            trace(block, source + "-cache", hash, cached);
            return cached;
        }

        ImmersiveFurnitureData.Definition persisted = loadHashData(hash);
        if (persisted != null && !persisted.isEmpty()) {
            BY_HASH.put(hash, persisted);
            trace(block, source + "-disk", hash, persisted);
            return persisted;
        }

        ImmersiveFurnitureData.Definition reflected = resolveRuntime(hash);
        if (reflected != null && !reflected.isEmpty()) {
            BY_HASH.put(hash, reflected);
            trace(block, source + "-runtime", hash, reflected);
            return reflected;
        }

        trace(block, source + "-missing", hash, inline);
        return inline;
    }

    private static String hashForIdentifier(BlockNeighborhood block) {
        String value = block.getBlockState().getProperties().get("identifier");
        if (value == null) return null;

        int identifier;
        try {
            identifier = Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return null;
        }

        if ("immersive_furniture:furniture_light".equals(
                block.getBlockState().getFormatted())) {
            identifier += 65536;
        }

        String persisted = IDENTIFIER_TO_HASH.get(identifier);
        if (persisted != null && !persisted.isBlank()) return persisted;

        Api runtime = api();
        if (runtime == null) return null;
        try {
            Object resolved = runtime.resolveIdentifier().invoke(null, identifier);
            return resolved instanceof String hash ? hash : null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            logUnavailable(error);
            return null;
        }
    }

    private static ImmersiveFurnitureData.Definition loadHashData(String hash) {
        for (Path root : worldRoots) {
            Path file = root
                    .resolve("immersive_furniture")
                    .resolve("hash")
                    .resolve(hash + ".nbt");
            Object raw = readCompressed(file);
            ImmersiveFurnitureData.Definition decoded =
                    ImmersiveFurnitureData.decodeNbt(raw);
            if (decoded != null && !decoded.isEmpty()) return decoded;
        }
        return null;
    }

    private static Map<Integer, String> loadRegistry(Path root) {
        Path file = root.resolve("data").resolve("immersive_furniture.dat");
        return ImmersiveFurnitureData.decodeIdentifierRegistry(readCompressed(file));
    }

    private static Object readCompressed(Path file) {
        if (file == null || !Files.isRegularFile(file)) return null;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return NBT.read(in, Object.class);
        } catch (IOException | RuntimeException error) {
            String key = "read#" + file;
            if (TRACED.add(key)) {
                Logger.global.logWarning(String.format(
                        "Could not read Immersive Furniture data file %s: %s",
                        file,
                        error));
            }
            return null;
        }
    }

    private static ImmersiveFurnitureData.Definition resolveRuntime(String hash) {
        Api runtime = api();
        if (runtime == null) return null;
        try {
            Object data = runtime.getData().invoke(null, hash);
            return ImmersiveFurnitureData.decodeRuntime(data);
        } catch (ReflectiveOperationException | RuntimeException error) {
            logUnavailable(error);
            return null;
        }
    }

    private static Api api() {
        Api current = api;
        if (current != null) return current;
        try {
            Class<?> registry =
                    Class.forName("net.conczin.immersive_furniture.data.FurnitureRegistry");
            Class<?> manager =
                    Class.forName("net.conczin.immersive_furniture.data.FurnitureDataManager");
            current = new Api(
                    registry.getMethod("resolve", int.class),
                    manager.getMethod("getData", String.class));
            api = current;
            return current;
        } catch (ReflectiveOperationException | RuntimeException error) {
            logUnavailable(error);
            return null;
        }
    }

    private static void trace(
            BlockNeighborhood block,
            String source,
            String hash,
            ImmersiveFurnitureData.Definition definition) {
        String id = block.getBlockState().getFormatted();
        String identifier =
                block.getBlockState().getProperties().getOrDefault("identifier", "<none>");
        String key = id + "#" + identifier + "#" + source;
        if (!TRACED.add(key)) return;

        Logger.global.logInfo(String.format(
                "IMMERSIVE-FURNITURE block=%s identifier=%s source=%s hash=%s elements=%s",
                id,
                identifier,
                source,
                hash == null ? "<none>" : hash,
                definition == null ? 0 : definition.elements().size()));
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> result ? result : null;
    }

    private static void logUnavailable(Throwable error) {
        if (unavailableLogged) return;
        unavailableLogged = true;
        Logger.global.logDebug(String.format(
                "Immersive Furniture runtime reflection unavailable; persisted world data is primary: %s",
                error));
    }

    private record Api(Method resolveIdentifier, Method getData) {
    }
}
