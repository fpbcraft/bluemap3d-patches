package dev.duzo.bluemap3d.compat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Hot-reloaded compatibility registry for BlueMap3D's moving-object path.
 *
 * <p>Loading, materialized reference files, and public adapter types stay here. Rule
 * validation, wildcard semantics, captures, templates, model aliases, and tint matching
 * are delegated to {@link SharedCompatRules}, generated from the same canonical source as
 * the native BlueMap compatibility addon.
 */
public final class CompatRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/CompatRegistry");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BUILTIN_ROOT = "bluemap3d-compat/builtin/";
    private static final Path EXTERNAL_DIRECTORY =
            Path.of("config", "bluemap3d", "compat");
    private static final Path GENERATED_REFERENCE =
            EXTERNAL_DIRECTORY.resolve("supported-defaults.generated.json");
    private static final Path LOCAL_CONFIG =
            EXTERNAL_DIRECTORY.resolve("local.json");
    private static final String LOCAL_TEMPLATE =
            "bluemap3d-compat/local-template.json";
    private static final long RELOAD_INTERVAL_NANOS = 5_000_000_000L;

    private static final CompatRegistry INSTANCE = new CompatRegistry();

    private final AtomicLong nextReloadCheck = new AtomicLong();

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile String fingerprint = "";

    private CompatRegistry() {
        reload();
    }

    public static CompatRegistry get() {
        return INSTANCE;
    }

    public boolean preserveMovingNamespace(String namespace) {
        Snapshot current = current();
        if (namespace == null || namespace.isBlank()) return false;
        if (current.movingExcludes.stream().anyMatch(pattern -> pattern.matches(namespace))) {
            return false;
        }
        return current.movingIncludes.stream().anyMatch(pattern -> pattern.matches(namespace));
    }

    public boolean featureEnabled(String feature, boolean defaultValue) {
        if (feature == null || feature.isBlank()) return defaultValue;
        return current().features.getOrDefault(feature, defaultValue);
    }

    public TintMatch tint(String blockId, Map<String, String> properties) {
        SharedCompatRules.TintMatch match =
                SharedCompatRules.tint(current().rules, blockId, properties, "moving");
        return match == null
                ? null
                : new TintMatch(match.rule().id, new Tint(match.tint()));
    }

    public ModelMatch model(String blockId, Map<String, String> properties) {
        for (SharedCompatRules.Rule rule : current().rules) {
            if (rule.model == null
                    || !rule.model.supportsMoving()
                    || !rule.appliesTo("moving")) {
                continue;
            }
            List<String> captures = rule.captures(blockId, properties);
            if (captures != null) {
                return new ModelMatch(rule.id, new Model(rule.model), captures);
            }
        }
        return null;
    }

    private Snapshot current() {
        reloadIfDue();
        return snapshot;
    }

    private void reloadIfDue() {
        long now = System.nanoTime();
        long next = nextReloadCheck.get();
        if (now < next || !nextReloadCheck.compareAndSet(next, now + RELOAD_INTERVAL_NANOS)) {
            return;
        }

        String nextFingerprint = fingerprint();
        if (!nextFingerprint.equals(fingerprint)) {
            reload();
        }
    }

    private synchronized void reload() {
        Map<String, SharedCompatRules.Rule> rules = new LinkedHashMap<>();
        Set<String> include = new LinkedHashSet<>();
        Set<String> exclude = new LinkedHashSet<>();
        Map<String, Boolean> features = new LinkedHashMap<>();

        loadBuiltins(rules, include, exclude, features);
        materializeConfigFiles(rules, include, exclude, features);
        loadExternal(rules, include, exclude, features);

        List<SharedCompatRules.Rule> compiled = SharedCompatRules.compileRules(
                rules.values(),
                (id, error) -> LOGGER.warn(
                        "Ignoring invalid compatibility rule '{}': {}",
                        id, error.getMessage()));

        snapshot = new Snapshot(
                compiled,
                include.stream().map(SharedCompatRules.Glob::new).toList(),
                exclude.stream().map(SharedCompatRules.Glob::new).toList(),
                Map.copyOf(features));
        fingerprint = fingerprint();

        LOGGER.info(
                "Loaded moving compatibility: {} rule(s), {} namespace include pattern(s), {} exclude pattern(s), {} feature flag(s)",
                compiled.size(), include.size(), exclude.size(), features.size());
    }

    private static void loadBuiltins(
            Map<String, SharedCompatRules.Rule> rules,
            Set<String> include,
            Set<String> exclude,
            Map<String, Boolean> features) {
        ClassLoader loader = CompatRegistry.class.getClassLoader();
        try (InputStream index = loader.getResourceAsStream(BUILTIN_ROOT + "index.txt")) {
            if (index == null) {
                LOGGER.warn("Compatibility builtin index is missing");
                return;
            }

            String text = new String(index.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;

                try (InputStream input = loader.getResourceAsStream(BUILTIN_ROOT + name)) {
                    if (input == null) {
                        LOGGER.warn("Compatibility builtin is missing: {}", name);
                        continue;
                    }
                    merge(read(input), rules, include, exclude, features);
                }
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Could not load builtin compatibility config: {}", error.toString());
        }
    }

    private static void loadExternal(
            Map<String, SharedCompatRules.Rule> rules,
            Set<String> include,
            Set<String> exclude,
            Map<String, Boolean> features) {
        try {
            Files.createDirectories(EXTERNAL_DIRECTORY);
        } catch (IOException error) {
            return;
        }

        try (Stream<Path> files = Files.list(EXTERNAL_DIRECTORY)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(CompatRegistry::isExternalConfig)
                    .sorted()
                    .toList()) {
                try (InputStream input = Files.newInputStream(file)) {
                    merge(read(input), rules, include, exclude, features);
                } catch (IOException | RuntimeException error) {
                    LOGGER.warn("Could not load compatibility config {}: {}",
                            file, error.toString());
                }
            }
        } catch (IOException error) {
            LOGGER.warn("Could not scan compatibility directory: {}", error.toString());
        }
    }

    private static SharedCompatRules.Document read(InputStream input) {
        SharedCompatRules.Document document = GSON.fromJson(
                new InputStreamReader(input, StandardCharsets.UTF_8),
                SharedCompatRules.Document.class);
        if (document == null
                || document.schemaVersion != SharedCompatRules.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported or missing schemaVersion");
        }
        return document;
    }

    private static void merge(
            SharedCompatRules.Document document,
            Map<String, SharedCompatRules.Rule> rules,
            Set<String> include,
            Set<String> exclude,
            Map<String, Boolean> features) {
        if (document.rules != null) {
            for (SharedCompatRules.Rule rule : document.rules) {
                if (rule == null || rule.id == null || rule.id.isBlank()) continue;
                rules.put(rule.id, rule);
            }
        }

        if (document.moving != null) {
            if (document.moving.modelNamespaces != null) {
                SharedCompatRules.NamespacePolicy policy = document.moving.modelNamespaces;
                if (policy.include != null) include.addAll(policy.include);
                if (policy.exclude != null) exclude.addAll(policy.exclude);
            }
            if (document.moving.features != null) {
                features.putAll(document.moving.features);
            }
        }
    }

    private static void materializeConfigFiles(
            Map<String, SharedCompatRules.Rule> rules,
            Set<String> include,
            Set<String> exclude,
            Map<String, Boolean> features) {
        try {
            Files.createDirectories(EXTERNAL_DIRECTORY);

            SharedCompatRules.Document reference = new SharedCompatRules.Document();
            reference.schemaVersion = SharedCompatRules.SCHEMA_VERSION;
            reference.id = "supported-defaults-generated";
            reference.description =
                    "Generated reference for the compatibility rules bundled with this build. "
                    + "Do not edit this file; put changes in local.json or another .json file.";
            reference.rules = new ArrayList<>(rules.values());

            SharedCompatRules.Moving moving = new SharedCompatRules.Moving();
            SharedCompatRules.NamespacePolicy policy = new SharedCompatRules.NamespacePolicy();
            policy.include = new ArrayList<>(include);
            policy.exclude = new ArrayList<>(exclude);
            moving.modelNamespaces = policy;
            moving.features = new LinkedHashMap<>(features);
            reference.moving = moving;

            Files.writeString(
                    GENERATED_REFERENCE,
                    GSON.toJson(reference) + System.lineSeparator(),
                    StandardCharsets.UTF_8);

            if (!Files.exists(LOCAL_CONFIG)) {
                try (InputStream input = CompatRegistry.class.getClassLoader()
                        .getResourceAsStream(LOCAL_TEMPLATE)) {
                    if (input == null) {
                        LOGGER.warn("Compatibility local template is missing from the bundle");
                    } else {
                        Files.write(
                                LOCAL_CONFIG,
                                input.readAllBytes(),
                                java.nio.file.StandardOpenOption.CREATE_NEW);
                    }
                }
            }
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // Another compatibility component won the startup race creating local.json.
        } catch (IOException | RuntimeException error) {
            LOGGER.warn("Could not materialize compatibility config files: {}", error.toString());
        }
    }

    private static boolean isExternalConfig(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") && !name.endsWith(".generated.json");
    }

    private static String fingerprint() {
        if (!Files.isDirectory(EXTERNAL_DIRECTORY)) return "<missing>";

        StringBuilder value = new StringBuilder();
        try (Stream<Path> files = Files.list(EXTERNAL_DIRECTORY)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(CompatRegistry::isExternalConfig)
                    .sorted()
                    .toList()) {
                value.append(file.getFileName())
                        .append(':')
                        .append(Files.getLastModifiedTime(file).toMillis())
                        .append(':')
                        .append(Files.size(file))
                        .append(';');
            }
        } catch (IOException error) {
            return "<error>";
        }
        return value.toString();
    }

    public record TintMatch(String ruleId, Tint tint) {
    }

    public record ModelMatch(String ruleId, Model model, List<String> captures) {
        public String resolveSourceBlock(String targetBlockId) {
            return model == null ? null : model.delegate.resolveSourceBlock(targetBlockId, captures);
        }
    }

    public static final class Tint {
        private final SharedCompatRules.Tint delegate;

        private Tint(SharedCompatRules.Tint delegate) {
            this.delegate = delegate;
        }

        public String type() {
            return delegate.type;
        }

        public String color() {
            return delegate.color;
        }

        public List<String> palette() {
            return delegate.palette == null ? List.of() : delegate.palette;
        }

        public String movingNbtPath() {
            return delegate.movingNbtPath();
        }

        public String defaultColor() {
            return delegate.defaultColor;
        }

        public String defaultColorFor(String blockId) {
            return delegate.defaultColorFor(blockId);
        }
    }

    public static final class Model {
        private final SharedCompatRules.Model delegate;

        private Model(SharedCompatRules.Model delegate) {
            this.delegate = delegate;
        }

        public String type() {
            return delegate.type;
        }

        public String resolveSourceBlock(String targetBlockId) {
            return delegate.resolveSourceBlock(targetBlockId);
        }
    }

    private record Snapshot(
            List<SharedCompatRules.Rule> rules,
            List<SharedCompatRules.Glob> movingIncludes,
            List<SharedCompatRules.Glob> movingExcludes,
            Map<String, Boolean> features) {
        private static final Snapshot EMPTY =
                new Snapshot(List.of(), List.of(), List.of(), Map.of());
    }
}
