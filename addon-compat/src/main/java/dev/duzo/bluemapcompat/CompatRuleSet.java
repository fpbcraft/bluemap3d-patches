package dev.duzo.bluemapcompat;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import de.bluecolored.bluemap.core.logger.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Immutable native-BlueMap compatibility snapshot.
 *
 * <p>Filesystem/classpath loading stays adapter-specific. Rule validation, matching,
 * captures, template expansion, and model/tint semantics live in {@link SharedCompatRules}
 * and are generated from the same canonical source used by BlueMap3D core.
 */
final class CompatRuleSet {

    static final Path EXTERNAL_DIRECTORY = Path.of("config", "bluemap3d", "compat");
    private static final Path LOCAL_CONFIG = EXTERNAL_DIRECTORY.resolve("local.json");
    private static final String LOCAL_TEMPLATE = "bluemap3d-compat/local-template.json";
    private static final String BUILTIN_ROOT = "bluemap3d-compat/builtin/";
    private static final Gson GSON = new Gson();

    private final List<SharedCompatRules.Rule> rules;

    private CompatRuleSet(List<SharedCompatRules.Rule> rules) {
        this.rules = rules;
    }

    static CompatRuleSet load() {
        Map<String, SharedCompatRules.Rule> merged = new LinkedHashMap<>();
        ClassLoader loader = CompatRuleSet.class.getClassLoader();

        loadBuiltins(loader, merged);
        loadExternal(merged);

        List<SharedCompatRules.Rule> compiled = SharedCompatRules.compileRules(
                merged.values(),
                (id, error) -> Logger.global.logWarning(String.format(
                        "Ignoring invalid compatibility rule '%s': %s",
                        id, error.getMessage())));

        return new CompatRuleSet(compiled);
    }

    SharedCompatRules.TintMatch tint(
            String blockId,
            Map<String, String> properties,
            String scope) {
        return SharedCompatRules.tint(rules, blockId, properties, scope);
    }

    SharedCompatRules.ModelMatch model(
            String blockId,
            Map<String, String> properties,
            String scope) {
        return SharedCompatRules.model(rules, blockId, properties, scope);
    }

    SharedCompatRules.ResourceModelMatch resourceModel(String modelId, String scope) {
        return SharedCompatRules.resourceModel(rules, modelId, scope);
    }

    int size() {
        return rules.size();
    }

    private static void loadBuiltins(
            ClassLoader loader,
            Map<String, SharedCompatRules.Rule> merged) {
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

    private static void loadExternal(Map<String, SharedCompatRules.Rule> merged) {
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

    private static SharedCompatRules.Document read(InputStream input, String source)
            throws IOException {
        SharedCompatRules.Document document = GSON.fromJson(
                new InputStreamReader(input, StandardCharsets.UTF_8),
                SharedCompatRules.Document.class);
        if (document == null) {
            throw new JsonParseException("empty document: " + source);
        }
        if (document.schemaVersion != SharedCompatRules.SCHEMA_VERSION) {
            throw new JsonParseException(
                    "unsupported schemaVersion " + document.schemaVersion + " in " + source);
        }
        return document;
    }

    private static void mergeDocument(
            SharedCompatRules.Document document,
            Map<String, SharedCompatRules.Rule> merged) {
        if (document.rules == null) return;
        for (SharedCompatRules.Rule rule : document.rules) {
            if (rule == null || rule.id == null || rule.id.isBlank()) {
                Logger.global.logWarning(String.format(
                        "Ignoring compatibility rule without an id in document '%s'",
                        document.id));
                continue;
            }
            merged.put(rule.id, rule);
        }
    }
}
