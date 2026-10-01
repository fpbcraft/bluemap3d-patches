package dev.duzo.bluemapcompat;

import de.bluecolored.bluemap.core.logger.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Shared hot-reloaded rule snapshot for all native BlueMap compatibility features. */
final class CompatManager {

    private static final Duration RELOAD_INTERVAL = Duration.ofSeconds(5);
    private static final AtomicReference<CompatRuleSet> RULES =
            new AtomicReference<>(CompatRuleSet.load());
    private static final List<Runnable> RELOAD_LISTENERS = new CopyOnWriteArrayList<>();
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private static volatile String fingerprint = fingerprint();

    private CompatManager() {
    }

    static CompatRuleSet rules() {
        return RULES.get();
    }

    static void onReload(Runnable listener) {
        RELOAD_LISTENERS.add(listener);
    }

    static void start() {
        if (!STARTED.compareAndSet(false, true)) return;

        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "BlueMap-Compat-Reload");
            thread.setDaemon(true);
            return thread;
        };
        ScheduledExecutorService watcher = Executors.newSingleThreadScheduledExecutor(factory);
        watcher.scheduleWithFixedDelay(
                CompatManager::reloadIfChanged,
                RELOAD_INTERVAL.toSeconds(),
                RELOAD_INTERVAL.toSeconds(),
                TimeUnit.SECONDS);

        Logger.global.logInfo(String.format(
                "Config-driven compatibility loaded: %s rule(s)",
                RULES.get().size()));
    }

    private static void reloadIfChanged() {
        try {
            String next = fingerprint();
            if (next.equals(fingerprint)) return;

            CompatRuleSet reloaded = CompatRuleSet.load();
            RULES.set(reloaded);
            fingerprint = next;

            for (Runnable listener : RELOAD_LISTENERS) {
                try {
                    listener.run();
                } catch (RuntimeException error) {
                    Logger.global.logWarning(
                            "Compatibility reload listener failed: " + error);
                }
            }

            Logger.global.logInfo(String.format(
                    "Reloaded BlueMap compatibility config: %s rule(s) active",
                    reloaded.size()));
        } catch (RuntimeException error) {
            Logger.global.logWarning(
                    "Compatibility config reload failed; keeping previous rules: " + error);
        }
    }

    private static String fingerprint() {
        Path directory = CompatRuleSet.EXTERNAL_DIRECTORY;
        if (!Files.isDirectory(directory)) return "<missing>";

        StringBuilder value = new StringBuilder();
        try (var files = Files.list(directory)) {
            for (Path file : files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".json") && !name.endsWith(".generated.json");
                    })
                    .sorted()
                    .toList()) {
                value.append(file.getFileName())
                        .append(':')
                        .append(Files.getLastModifiedTime(file).toMillis())
                        .append(':')
                        .append(Files.size(file))
                        .append(';');
            }
        } catch (Exception error) {
            return "<error:" + error.getClass().getSimpleName() + ">";
        }
        return value.toString();
    }
}
