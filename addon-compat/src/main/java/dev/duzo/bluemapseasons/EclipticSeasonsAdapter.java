package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.core.logger.Logger;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Observes Ecliptic's authoritative calendar without adding a required mod dependency.
 * An explicit opt-in is required to force map updates; the default does not generate extra work.
 */
public final class EclipticSeasonsAdapter {
    private static final String POLICY_PROPERTY = "bluemap.compat.ecliptic.rerender";
    private static final AtomicBoolean ENABLED = new AtomicBoolean();
    private static volatile ScheduledExecutorService polling;
    private static volatile SeasonState lastState;

    private EclipticSeasonsAdapter() {}

    public static void onEnable(BlueMapAPI api) {
        if (!EclipticSeasonsBridge.isAvailable() || !ENABLED.compareAndSet(false, true)) return;

        SeasonRefreshPolicy policy = SeasonRefreshPolicy.parse(System.getProperty(POLICY_PROPERTY, "off"));
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "BlueMap-Ecliptic-Seasons");
            thread.setDaemon(true);
            return thread;
        });
        polling = executor;
        executor.scheduleWithFixedDelay(() -> poll(api, policy), 0, 60, TimeUnit.SECONDS);
        Logger.global.logInfo("Ecliptic Seasons detected; BlueMap seasonal refresh policy: " + policy);
    }

    public static void onDisable() {
        ENABLED.set(false);
        ScheduledExecutorService executor = polling;
        polling = null;
        lastState = null;
        if (executor != null) executor.shutdownNow();
    }

    private static void poll(BlueMapAPI api, SeasonRefreshPolicy policy) {
        if (!ENABLED.get()) return;
        try {
            Object server = EclipticSeasonsBridge.getServer();
            if (server == null) return;
            EclipticSeasonsBridge.executeOnServer(server, () -> {
                if (!ENABLED.get()) return;
                try {
                    Optional<SeasonState> current = EclipticSeasonsBridge.read(server);
                    if (current.isEmpty()) return;
                    SeasonState next = current.get();
                    SeasonState previous = lastState;
                    lastState = next;
                    if (previous == null || previous.equals(next)) return;

                    Logger.global.logInfo("Ecliptic solar term changed: " + previous.solarTerm()
                            + " -> " + next.solarTerm());
                    if (!policy.shouldRefresh(previous, next)) return;
                    Object overworld = server.getClass().getMethod("overworld").invoke(server);
                    Optional<BlueMapWorld> world = api.getWorld(overworld);
                    if (world.isEmpty()) {
                        Logger.global.logWarning("Ecliptic Seasons: BlueMap Overworld not found; skipping forced refresh");
                        return;
                    }
                    for (BlueMapMap map : world.get().getMaps()) {
                        api.getRenderManager().scheduleMapUpdateTask(map, true);
                    }
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Logger.global.logWarning("Could not read Ecliptic Seasons state: " + error);
                }
            });
        } catch (ReflectiveOperationException | RuntimeException error) {
            Logger.global.logWarning("Could not schedule Ecliptic Seasons poll: " + error);
        }
    }
}
