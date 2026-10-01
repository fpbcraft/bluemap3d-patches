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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Shared hot-reloaded compatibility registry for BlueMap3D's moving-object path.
 *
 * <p>All moving compatibility queries flow through one immutable snapshot so model
 * sources, Create providers and future adapters cannot drift onto separate parsers.
 */
public final class CompatRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/CompatRegistry");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SCHEMA_VERSION = 1;
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
        for (Rule rule : current().rules) {
            if (rule.tint == null || !rule.appliesTo("moving")) continue;
            if (rule.matches(blockId, properties)) {
                return new TintMatch(rule.id, rule.tint);
            }
        }
        return null;
    }

    public ModelMatch model(String blockId, Map<String, String> properties) {
        for (Rule rule : current().rules) {
            if (rule.model == null || !rule.model.supportsMoving() || !rule.appliesTo("moving")) continue;
            List<String> captures = rule.captures(blockId, properties);
            if (captures != null) {
                return new ModelMatch(rule.id, rule.model, captures);
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
        Map<String, Rule> rules = new LinkedHashMap<>();
        Set<String> include = new LinkedHashSet<>();
        Set<String> exclude = new LinkedHashSet<>();
        Map<String, Boolean> features = new LinkedHashMap<>();

        loadBuiltins(rules, include, exclude, features);
        materializeConfigFiles(rules, include, exclude, features);
        loadExternal(rules, include, exclude, features);

        List<Rule> compiled = new ArrayList<>();
        for (Rule rule : rules.values()) {
            if (rule == null || !rule.enabled || rule.match == null) continue;
            try {
                rule.compile();
                compiled.add(rule);
            } catch (RuntimeException error) {
                LOGGER.warn("Ignoring invalid compatibility rule '{}': {}",
                        rule.id, error.getMessage());
            }
        }
        compiled.sort(Comparator
                .comparingInt((Rule rule) -> rule.priority)
                .reversed()
                .thenComparing(rule -> rule.id));

        snapshot = new Snapshot(
                List.copyOf(compiled),
                include.stream().map(Glob::new).toList(),
                exclude.stream().map(Glob::new).toList(),
                Map.copyOf(features));
        fingerprint = fingerprint();

        LOGGER.info(
                "Loaded moving compatibility: {} rule(s), {} namespace include pattern(s), {} exclude pattern(s), {} feature flag(s)",
                compiled.size(), include.size(), exclude.size(), features.size());
    }

    private static void loadBuiltins(
            Map<String, Rule> rules,
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
            Map<String, Rule> rules,
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

    private static Document read(InputStream input) {
        Document document = GSON.fromJson(
                new InputStreamReader(input, StandardCharsets.UTF_8),
                Document.class);
        if (document == null || document.schemaVersion != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported or missing schemaVersion");
        }
        return document;
    }

    private static void merge(
            Document document,
            Map<String, Rule> rules,
            Set<String> include,
            Set<String> exclude,
            Map<String, Boolean> features) {
        if (document.rules != null) {
            for (Rule rule : document.rules) {
                if (rule == null || rule.id == null || rule.id.isBlank()) continue;
                // Stable ids are override keys. Builtins load first, then local files.
                rules.put(rule.id, rule);
            }
        }

        if (document.moving != null) {
            if (document.moving.modelNamespaces != null) {
                NamespacePolicy policy = document.moving.modelNamespaces;
                if (policy.include != null) include.addAll(policy.include);
                if (policy.exclude != null) exclude.addAll(policy.exclude);
            }
            if (document.moving.features != null) {
                features.putAll(document.moving.features);
            }
        }
    }

    private static void materializeConfigFiles(
            Map<String, Rule> rules,
            Set<String> include,
            Set<String> exclude,
            Map<String, Boolean> features) {
        try {
            Files.createDirectories(EXTERNAL_DIRECTORY);

            Document reference = new Document();
            reference.schemaVersion = SCHEMA_VERSION;
            reference.id = "supported-defaults-generated";
            reference.description =
                    "Generated reference for the compatibility rules bundled with this build. "
                    + "Do not edit this file; put changes in local.json or another .json file.";
            reference.rules = new ArrayList<>(rules.values());

            Moving moving = new Moving();
            NamespacePolicy policy = new NamespacePolicy();
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
            return model == null ? null : model.resolveSourceBlock(targetBlockId, captures);
        }
    }

    public static final class Tint {
        private String type;
        private String color;
        private List<String> palette;
        private ValueSources value;
        private String defaultColor = "#FFFFFF";
        private List<DefaultColor> defaultByBlock;

        public String type() {
            return type;
        }

        public String color() {
            return color;
        }

        public List<String> palette() {
            return palette == null ? List.of() : palette;
        }

        public String movingNbtPath() {
            ValueSource source = value == null ? null : value.moving;
            return source != null && "nbt_path".equals(source.type) ? source.path : null;
        }

        public String defaultColor() {
            return defaultColor;
        }

        public String defaultColorFor(String blockId) {
            if (defaultByBlock != null) {
                for (DefaultColor entry : defaultByBlock) {
                    if (entry != null && entry.matches(blockId)) return entry.color;
                }
            }
            return defaultColor;
        }
    }

    public static final class Model {
        private String type;
        private String sourceBlock;
        private String sourceModel;
        private String targetModel;

        public String type() {
            return type;
        }

        private boolean supportsMoving() {
            return "alias".equals(type);
        }

        private void validate() {
            if ("alias".equals(type)) {
                if (sourceBlock == null || sourceBlock.isBlank()) {
                    throw new IllegalArgumentException("alias model requires sourceBlock");
                }
                return;
            }
            if ("resource_alias".equals(type)) {
                if (sourceModel == null || sourceModel.isBlank()) {
                    throw new IllegalArgumentException("resource_alias model requires sourceModel");
                }
                return;
            }
            throw new IllegalArgumentException("unsupported model type " + type);
        }

        public String resolveSourceBlock(String targetBlockId) {
            return resolveSourceBlock(targetBlockId, List.of());
        }

        private String resolveSourceBlock(String targetBlockId, List<String> captures) {
            if (!"alias".equals(type) || sourceBlock == null || sourceBlock.isBlank()) {
                return null;
            }
            return expandTemplate(sourceBlock, targetBlockId, captures);
        }

        private int requiredCaptures() {
            return Math.max(
                    highestCapture(sourceBlock),
                    Math.max(highestCapture(sourceModel), highestCapture(targetModel)));
        }

        private static int highestCapture(String template) {
            if (template == null || template.isBlank()) return 0;

            var matcher = Pattern.compile("\\$\\{(\\d+)\\}").matcher(template);
            int highest = 0;
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                if (index < 1) {
                    throw new IllegalArgumentException("wildcard captures are 1-based");
                }
                highest = Math.max(highest, index);
            }
            return highest;
        }
    }

    private record Snapshot(
            List<Rule> rules,
            List<Glob> movingIncludes,
            List<Glob> movingExcludes,
            Map<String, Boolean> features) {
        private static final Snapshot EMPTY =
                new Snapshot(List.of(), List.of(), List.of(), Map.of());
    }

    private static final class Document {
        int schemaVersion;
        String id;
        String description;
        List<Rule> rules;
        Moving moving;
    }

    private static final class Moving {
        NamespacePolicy modelNamespaces;
        Map<String, Boolean> features;
    }

    private static final class NamespacePolicy {
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
    }

    private static final class Rule {
        String id;
        boolean enabled = true;
        int priority;
        List<String> scope = List.of("terrain", "moving");
        Match match;
        Tint tint;
        Model model;

        private transient List<Glob> blockPatterns = List.of();
        private transient List<Glob> exclusions = List.of();
        private transient Map<String, Glob> propertyPatterns = Map.of();

        void compile() {
            blockPatterns = Glob.compileAll(match.blocks);
            exclusions = Glob.compileAll(match.exclude);

            Map<String, Glob> compiled = new LinkedHashMap<>();
            if (match.properties != null) {
                match.properties.forEach((name, pattern) ->
                        compiled.put(name, new Glob(pattern)));
            }
            propertyPatterns = Map.copyOf(compiled);

            if (blockPatterns.isEmpty()) {
                throw new IllegalArgumentException("match.blocks is empty");
            }
            if (model != null) {
                model.validate();
                int requiredCaptures = model.requiredCaptures();
                for (Glob pattern : blockPatterns) {
                    if (pattern.captureCount() < requiredCaptures) {
                        throw new IllegalArgumentException(
                                "model template references capture ${" + requiredCaptures
                                        + "} but a block pattern provides only "
                                        + pattern.captureCount() + " capture(s)");
                    }
                }
            }
        }

        boolean appliesTo(String wanted) {
            return scope == null || scope.isEmpty() || scope.contains(wanted);
        }

        boolean matches(String blockId, Map<String, String> properties) {
            if (blockId == null) return false;
            if (blockPatterns.stream().noneMatch(pattern -> pattern.matches(blockId))) {
                return false;
            }
            if (exclusions.stream().anyMatch(pattern -> pattern.matches(blockId))) {
                return false;
            }
            for (Map.Entry<String, Glob> entry : propertyPatterns.entrySet()) {
                String value = properties == null ? null : properties.get(entry.getKey());
                if (value == null || !entry.getValue().matches(value)) return false;
            }
            return true;
        }

        List<String> captures(String blockId, Map<String, String> properties) {
            if (blockId == null) return null;

            Glob matched = null;
            for (Glob pattern : blockPatterns) {
                if (pattern.matches(blockId)) {
                    matched = pattern;
                    break;
                }
            }
            if (matched == null) return null;
            if (exclusions.stream().anyMatch(pattern -> pattern.matches(blockId))) {
                return null;
            }
            for (Map.Entry<String, Glob> entry : propertyPatterns.entrySet()) {
                String value = properties == null ? null : properties.get(entry.getKey());
                if (value == null || !entry.getValue().matches(value)) return null;
            }
            return matched.captures(blockId);
        }
    }

    private static final class Match {
        List<String> blocks;
        List<String> exclude;
        Map<String, String> properties;
    }

    private static final class ValueSources {
        ValueSource terrain;
        ValueSource moving;
    }

    private static final class ValueSource {
        String type;
        String method;
        String path;
    }

    private static final class DefaultColor {
        List<String> blocks;
        String color;
        private transient List<Glob> patterns;

        boolean matches(String blockId) {
            if (patterns == null) patterns = Glob.compileAll(blocks);
            return patterns.stream().anyMatch(pattern -> pattern.matches(blockId));
        }
    }

    private static String expandTemplate(String template, String targetBlockId) {
        return expandTemplate(template, targetBlockId, List.of());
    }

    private static String expandTemplate(
            String template,
            String targetBlockId,
            List<String> captures) {
        int colon = targetBlockId.indexOf(':');
        String namespace = colon < 0 ? "minecraft" : targetBlockId.substring(0, colon);
        String path = colon < 0 ? targetBlockId : targetBlockId.substring(colon + 1);
        String[] segments = path.split("/");

        String result = template
                .replace("${id}", targetBlockId)
                .replace("${namespace}", namespace)
                .replace("${path}", path);

        for (int i = 0; i < segments.length; i++) {
            result = result.replace("${path" + i + "}", segments[i]);
        }
        if (captures != null) {
            for (int i = 0; i < captures.size(); i++) {
                result = result.replace("${" + (i + 1) + "}", captures.get(i));
            }
        }
        return result;
    }

    private static final class Glob {
        private final Pattern pattern;

        Glob(String source) {
            Objects.requireNonNull(source, "glob");
            pattern = Pattern.compile(toRegex(source));
        }

        boolean matches(String value) {
            return pattern.matcher(value).matches();
        }

        List<String> captures(String value) {
            var matcher = pattern.matcher(value);
            if (!matcher.matches()) return null;
            if (matcher.groupCount() == 0) return List.of();

            List<String> captures = new ArrayList<>(matcher.groupCount());
            for (int i = 1; i <= matcher.groupCount(); i++) {
                captures.add(matcher.group(i));
            }
            return List.copyOf(captures);
        }

        int captureCount() {
            return pattern.matcher("").groupCount();
        }

        static List<Glob> compileAll(List<String> source) {
            if (source == null || source.isEmpty()) return List.of();
            return source.stream().map(Glob::new).toList();
        }

        private static String toRegex(String glob) {
            StringBuilder regex = new StringBuilder("^");
            StringBuilder literal = new StringBuilder();

            for (int i = 0; i < glob.length(); i++) {
                char c = glob.charAt(i);
                if (c == '*' || c == '?') {
                    appendQuoted(regex, literal);
                    regex.append(c == '*' ? "(.*)" : ".");
                } else {
                    literal.append(c);
                }
            }

            appendQuoted(regex, literal);
            return regex.append('$').toString();
        }

        private static void appendQuoted(StringBuilder regex, StringBuilder literal) {
            if (literal.isEmpty()) return;
            regex.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }
}
