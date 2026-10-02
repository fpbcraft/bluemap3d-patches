package dev.duzo.bluemapcompat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.function.BiConsumer;

/**
 * GENERATED from compat/shared/SharedCompatRules.java.in.
 *
 * <p>Pure compatibility-rule model and matcher shared by the native BlueMap addon and
 * BlueMap3D moving-object integration. Keep filesystem loading, logging, and hot-reload
 * policy in the surrounding adapter.
 */
final class SharedCompatRules {
    static final int SCHEMA_VERSION = 1;

    private SharedCompatRules() {
    }

    static List<Rule> compileRules(
            Iterable<Rule> source,
            BiConsumer<String, RuntimeException> invalidRule) {
        List<Rule> compiled = new ArrayList<>();
        for (Rule rule : source) {
            if (rule == null || !rule.enabled || rule.match == null) continue;
            try {
                rule.compile();
                compiled.add(rule);
            } catch (RuntimeException error) {
                invalidRule.accept(rule.id, error);
            }
        }
        compiled.sort(Comparator
                .comparingInt((Rule rule) -> rule.priority)
                .reversed()
                .thenComparing(rule -> rule.id));
        return List.copyOf(compiled);
    }

    static TintMatch tint(
            List<Rule> rules,
            String blockId,
            Map<String, String> properties,
            String scope) {
        for (Rule rule : rules) {
            if (rule.tint == null || !rule.appliesTo(scope)) continue;
            if (rule.matches(blockId, properties)) {
                return new TintMatch(rule, rule.tint);
            }
        }
        return null;
    }

    static ModelMatch model(
            List<Rule> rules,
            String blockId,
            Map<String, String> properties,
            String scope) {
        for (Rule rule : rules) {
            if (rule.model == null || !rule.appliesTo(scope)) continue;
            List<String> captures = rule.captures(blockId, properties);
            if (captures != null) {
                return new ModelMatch(rule, rule.model, captures);
            }
        }
        return null;
    }

    static ResourceModelMatch resourceModel(
            List<Rule> rules,
            String modelId,
            String scope) {
        for (Rule rule : rules) {
            if (rule.model == null
                    || !"resource_alias".equals(rule.model.type)
                    || !rule.appliesTo(scope)) {
                continue;
            }
            List<String> captures = rule.modelCaptures(modelId);
            if (captures != null) {
                return new ResourceModelMatch(rule, rule.model, modelId, captures);
            }
        }
        return null;
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

    record ResourceModelMatch(
            Rule rule,
            Model model,
            String targetModel,
            List<String> captures) {
        String resolveSourceModel() {
            return model.resolveSourceModel(targetModel, captures);
        }
    }

    static final class Document {
        int schemaVersion;
        String id;
        String description;
        List<Rule> rules;
        Moving moving;
    }

    static final class Moving {
        NamespacePolicy modelNamespaces;
        Map<String, Boolean> features;
    }

    static final class NamespacePolicy {
        List<String> include = new ArrayList<>();
        List<String> exclude = new ArrayList<>();
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
            if (match == null) throw new IllegalArgumentException("missing match");
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
                        "match must contain at least one block or model pattern");
            }
            if (tint != null && blockPatterns.isEmpty()) {
                throw new IllegalArgumentException("tint rules require match.blocks");
            }
            if (model != null) {
                model.validate();
                int requiredCaptures = model.requiredCaptures();
                List<Glob> capturePatterns =
                        "resource_alias".equals(model.type) && !modelPatterns.isEmpty()
                                ? modelPatterns
                                : blockPatterns;
                if (capturePatterns.isEmpty()) {
                    throw new IllegalArgumentException(
                            model.type + " rules require a compatible match pattern");
                }
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
            return scope == null || scope.isEmpty() || scope.contains(wantedScope);
        }

        boolean matches(String blockId, Map<String, String> properties) {
            return captures(blockId, properties) != null;
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

        boolean supportsMoving() {
            return "alias".equals(type);
        }

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

        String movingNbtPath() {
            ValueSource source = value == null ? null : value.moving;
            return source != null && "nbt_path".equals(source.type) ? source.path : null;
        }

        String defaultColorFor(String blockId) {
            if (defaultByBlock != null) {
                for (DefaultColor entry : defaultByBlock) {
                    if (entry != null && entry.matches(blockId)) return entry.color;
                }
            }
            return defaultColor;
        }
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
