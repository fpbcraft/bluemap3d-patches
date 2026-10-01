package dev.duzo.bluemapcompat;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import de.bluecolored.bluemap.core.logger.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Immutable compatibility-rule snapshot.
 *
 * <p>Built-in rule files are loaded first, then server-local files from
 * config/bluemap3d/compat. Rule IDs are stable merge keys: a local rule with the same ID
 * replaces the shipped rule. This lets a server override one behavior without copying an
 * entire mod definition.
 */
final class CompatRuleSet {

    static final int SCHEMA_VERSION = 1;
    static final Path EXTERNAL_DIRECTORY = Path.of("config", "bluemap3d", "compat");
    private static final Path LOCAL_CONFIG = EXTERNAL_DIRECTORY.resolve("local.json");
    private static final String LOCAL_TEMPLATE = "bluemap3d-compat/local-template.json";

    private static final String BUILTIN_ROOT = "bluemap3d-compat/builtin/";
    private static final Gson GSON = new Gson();

    private final List<Rule> rules;

    private CompatRuleSet(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    static CompatRuleSet load() {
        Map<String, Rule> merged = new LinkedHashMap<>();
        ClassLoader loader = CompatRuleSet.class.getClassLoader();

        loadBuiltins(loader, merged);
        loadExternal(merged);

        List<Rule> compiled = new ArrayList<>();
        for (Rule rule : merged.values()) {
            if (rule == null || !rule.enabled || rule.match == null) continue;
            try {
                rule.compile();
                compiled.add(rule);
            } catch (RuntimeException error) {
                Logger.global.logWarning(String.format(
                        "Ignoring invalid compatibility rule '%s': %s",
                        rule.id, error.getMessage()));
            }
        }

        compiled.sort(Comparator
                .comparingInt((Rule rule) -> rule.priority)
                .reversed()
                .thenComparing(rule -> rule.id));

        return new CompatRuleSet(compiled);
    }

    TintMatch tint(String blockId, Map<String, String> properties, String scope) {
        for (Rule rule : rules) {
            if (rule.tint == null || !rule.appliesTo(scope)) continue;
            if (rule.matches(blockId, properties)) {
                return new TintMatch(rule, rule.tint);
            }
        }
        return null;
    }

    ModelMatch model(String blockId, Map<String, String> properties, String scope) {
        for (Rule rule : rules) {
            if (rule.model == null || !rule.appliesTo(scope)) continue;
            List<String> captures = rule.captures(blockId, properties);
            if (captures != null) {
                return new ModelMatch(rule, rule.model, captures);
            }
        }
        return null;
    }

    ModelResourceMatch modelResource(String modelId, String scope) {
        for (Rule rule : rules) {
            if (rule.model == null || !rule.appliesTo(scope)) continue;
            List<String> captures = rule.modelCaptures(modelId);
            if (captures != null) {
                return new ModelResourceMatch(rule, rule.model, captures);
            }
        }
        return null;
    }

    int size() {
        return rules.size();
    }

    private static void loadBuiltins(ClassLoader loader, Map<String, Rule> merged) {
        try (InputStream index = loader.getResourceAsStream(BUILTIN_ROOT + "index.txt")) {
            if (index == null) {
                Logger.global.logWarning("Compatibility builtin index is missing");
                return;
            }

            String text = new String(index.readAllBytes(), StandardCharsets.UTF_8);
            for (String line : text.split("\\R")) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                try (InputStream input = loader.getResourceAsStream(BUILTIN_ROOT + name)) {
                    if (input == null) {
                        Logger.global.logWarning("Compatibility builtin is missing: " + name);
                        continue;
                    }
                    mergeDocument(read(input, "builtin/" + name), merged);
                }
            }
        } catch (IOException error) {
            Logger.global.logError("Failed to load built-in compatibility rules", error);
        }
    }

    private static void loadExternal(Map<String, Rule> merged) {
        try {
            Files.createDirectories(EXTERNAL_DIRECTORY);
            ensureLocalConfig();
        } catch (IOException error) {
            Logger.global.logWarning(String.format(
                    "Could not create compatibility config directory %s: %s",
                    EXTERNAL_DIRECTORY, error));
            return;
        }

        try (Stream<Path> files = Files.list(EXTERNAL_DIRECTORY)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(CompatRuleSet::isExternalConfig)
                    .sorted()
                    .toList()) {
                try (InputStream input = Files.newInputStream(file)) {
                    mergeDocument(read(input, file.toString()), merged);
                } catch (IOException | JsonParseException error) {
                    Logger.global.logWarning(String.format(
                            "Could not load compatibility config %s: %s", file, error));
                }
            }
        } catch (IOException error) {
            Logger.global.logWarning(String.format(
                    "Could not scan compatibility config directory %s: %s",
                    EXTERNAL_DIRECTORY, error));
        }
    }

    private static void ensureLocalConfig() throws IOException {
        if (Files.exists(LOCAL_CONFIG)) return;

        try (InputStream input = CompatRuleSet.class.getClassLoader()
                .getResourceAsStream(LOCAL_TEMPLATE)) {
            if (input == null) {
                Logger.global.logWarning("Compatibility local template is missing from the addon");
                return;
            }
            try {
                Files.write(
                        LOCAL_CONFIG,
                        input.readAllBytes(),
                        java.nio.file.StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // BlueMap3D core may have created the same file during startup.
            }
        }
    }

    private static boolean isExternalConfig(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") && !name.endsWith(".generated.json");
    }

    private static Document read(InputStream input, String source) throws IOException {
        Document document = GSON.fromJson(
                new InputStreamReader(input, StandardCharsets.UTF_8),
                Document.class);
        if (document == null) {
            throw new JsonParseException("empty document: " + source);
        }
        if (document.schemaVersion != SCHEMA_VERSION) {
            throw new JsonParseException(
                    "unsupported schemaVersion " + document.schemaVersion + " in " + source);
        }
        return document;
    }

    private static void mergeDocument(Document document, Map<String, Rule> merged) {
        if (document.rules == null) return;
        for (Rule rule : document.rules) {
            if (rule == null || rule.id == null || rule.id.isBlank()) {
                Logger.global.logWarning(String.format(
                        "Ignoring compatibility rule without an id in document '%s'",
                        document.id));
                continue;
            }
            merged.put(rule.id, rule);
        }
    }

    record TintMatch(Rule rule, Tint tint) {
    }

    record ModelMatch(Rule rule, Model model, List<String> captures) {
        String resolveSourceBlock(String targetBlockId) {
            return model.resolveSourceBlock(targetBlockId, captures);
        }

        String resolveSourceModel(String targetBlockId) {
            return model.resolveSourceModel(targetBlockId, captures);
        }

        String resolveTargetModel(String targetBlockId) {
            return model.resolveTargetModel(targetBlockId, captures);
        }
    }

    record ModelResourceMatch(Rule rule, Model model, List<String> captures) {
        String resolveSourceModel(String targetModelId) {
            return model.resolveSourceModel(targetModelId, captures);
        }

        JsonObject inlineDefinition() {
            return model.definition;
        }
    }

    static final class Document {
        int schemaVersion;
        String id;
        String description;
        List<Rule> rules;
    }

    static final class Rule {
        String id;
        boolean enabled = true;
        int priority;
        List<String> scope = List.of("terrain", "moving");
        Match match;
        Tint tint;
        Model model;

        private transient List<Glob> blockPatterns = List.of();
        private transient List<Glob> modelPatterns = List.of();
        private transient List<Glob> exclusions = List.of();
        private transient Map<String, Glob> propertyPatterns = Map.of();

        void compile() {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("missing id");
            blockPatterns = Glob.compileAll(match.blocks);
            modelPatterns = Glob.compileAll(match.models);
            exclusions = Glob.compileAll(match.exclude);

            Map<String, Glob> compiledProperties = new LinkedHashMap<>();
            if (match.properties != null) {
                match.properties.forEach((key, value) ->
                        compiledProperties.put(key, new Glob(value)));
            }
            propertyPatterns = Map.copyOf(compiledProperties);

            if (blockPatterns.isEmpty() && modelPatterns.isEmpty()) {
                throw new IllegalArgumentException(
                        "match must contain at least one blocks or models pattern");
            }
            if (model != null) {
                model.validate();
                int requiredCaptures = model.requiredCaptures();
                List<Glob> capturePatterns =
                        !modelPatterns.isEmpty() ? modelPatterns : blockPatterns;
                for (Glob pattern : capturePatterns) {
                    if (pattern.captureCount() < requiredCaptures) {
                        throw new IllegalArgumentException(
                                "model template references capture ${" + requiredCaptures
                                        + "} but pattern '" + pattern + "' provides only "
                                        + pattern.captureCount() + " capture(s)");
                    }
                }
            }
        }

        boolean appliesTo(String wantedScope) {
            return scope == null
                    || scope.isEmpty()
                    || scope.contains(wantedScope);
        }

        boolean matches(String blockId, Map<String, String> properties) {
            if (blockId == null || blockPatterns.isEmpty()) return false;
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
            if (blockId == null || blockPatterns.isEmpty()) return null;

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

        List<String> modelCaptures(String modelId) {
            if (modelId == null || modelPatterns.isEmpty()) return null;
            for (Glob pattern : modelPatterns) {
                List<String> captures = pattern.captures(modelId);
                if (captures != null) return captures;
            }
            return null;
        }
    }

    static final class Match {
        List<String> blocks;
        List<String> models;
        List<String> exclude;
        Map<String, String> properties;
    }

    static final class Model {
        String type;
        String sourceBlock;
        String sourceModel;
        String targetModel;
        JsonObject definition;

        void validate() {
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
            if ("inline".equals(type)) {
                if (definition == null) {
                    throw new IllegalArgumentException("inline model requires definition");
                }
                return;
            }
            throw new IllegalArgumentException("unsupported model type " + type);
        }

        String resolveSourceBlock(String targetBlockId) {
            return resolveSourceBlock(targetBlockId, List.of());
        }

        String resolveSourceBlock(String targetBlockId, List<String> captures) {
            if (!"alias".equals(type) || sourceBlock == null || sourceBlock.isBlank()) {
                return null;
            }
            return expandTemplate(sourceBlock, targetBlockId, captures);
        }

        String resolveSourceModel(String targetBlockId, List<String> captures) {
            if (!"resource_alias".equals(type) || sourceModel == null || sourceModel.isBlank()) {
                return null;
            }
            return expandTemplate(sourceModel, targetBlockId, captures);
        }

        String resolveTargetModel(String targetBlockId, List<String> captures) {
            if (!"resource_alias".equals(type)) return null;
            if (targetModel != null && !targetModel.isBlank()) {
                return expandTemplate(targetModel, targetBlockId, captures);
            }

            int colon = targetBlockId.indexOf(':');
            String namespace = colon < 0 ? "minecraft" : targetBlockId.substring(0, colon);
            String path = colon < 0 ? targetBlockId : targetBlockId.substring(colon + 1);
            return namespace + ":block/" + path;
        }

        int requiredCaptures() {
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

    static final class Tint {
        String type;
        String color;
        List<String> palette;
        ValueSources value;
        String defaultColor = "#FFFFFF";
        List<DefaultColor> defaultByBlock;
    }

    static final class ValueSources {
        ValueSource terrain;
        ValueSource moving;
    }

    static final class ValueSource {
        String type;
        String method;
        String path;
    }

    static final class DefaultColor {
        List<String> blocks;
        String color;

        private transient List<Glob> patterns;

        boolean matches(String blockId) {
            if (patterns == null) patterns = Glob.compileAll(blocks);
            return patterns.stream().anyMatch(pattern -> pattern.matches(blockId));
        }
    }

    static String expandTemplate(String template, String targetBlockId) {
        return expandTemplate(template, targetBlockId, List.of());
    }

    static String expandTemplate(
            String template,
            String targetBlockId,
            List<String> captures) {
        if (template == null || targetBlockId == null) return template;

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

    static final class Glob {
        private final String source;
        private final Pattern pattern;

        Glob(String source) {
            this.source = Objects.requireNonNull(source, "glob");
            this.pattern = Pattern.compile(toRegex(source));
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

        static List<Glob> compileAll(List<String> patterns) {
            if (patterns == null || patterns.isEmpty()) return List.of();
            return patterns.stream().map(Glob::new).toList();
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

        @Override
        public String toString() {
            return source;
        }
    }
}
