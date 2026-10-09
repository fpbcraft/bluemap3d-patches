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
    private static volatile SeasonalLayer layer;
    private static final java.util.concurrent.atomic.AtomicLong GENERATION = new java.util.concurrent.atomic.AtomicLong();

    private EclipticSeasonsAdapter() {}

    public static void onEnable(BlueMapAPI api) {
        if (!EclipticSeasonsBridge.isAvailable() || !ENABLED.compareAndSet(false, true)) return;

        if (SeasonalLayer.enabled()) {
            try { layer = new SeasonalLayer(api); }
            catch (java.io.IOException failure) {
                ENABLED.set(false);
                Logger.global.logWarning("Cannot start seasonal layer: " + failure);
                return;
            }
        }
        SeasonRefreshPolicy policy = SeasonRefreshPolicy.parse(System.getProperty(POLICY_PROPERTY, "off"));
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "BlueMap-Ecliptic-Seasons");
            thread.setDaemon(true);
            return thread;
        });
        polling = executor;
        long generation = GENERATION.incrementAndGet();
        executor.scheduleWithFixedDelay(() -> poll(api, policy, generation), 0, 10, TimeUnit.SECONDS);
        Logger.global.logInfo("Ecliptic Seasons detected; BlueMap seasonal refresh policy: " + policy);
    }

    public static void onDisable() {
        ENABLED.set(false);
        GENERATION.incrementAndGet();
        ScheduledExecutorService executor = polling;
        polling = null;
        lastState = null;
        SeasonalTintBridge.reset();
        SeasonalLayer previousLayer = layer;
        layer = null;
        if (previousLayer != null) previousLayer.close();
        if (executor != null) executor.shutdownNow();
    }

    private static void poll(BlueMapAPI api, SeasonRefreshPolicy policy, long generation) {
        if (!ENABLED.get() || GENERATION.get() != generation) return;
        try {
            Object server = EclipticSeasonsBridge.getServer();
            if (server == null) return;
            EclipticSeasonsBridge.executeOnServer(server, () -> {
                if (!ENABLED.get() || GENERATION.get() != generation) return;
                try {
                    Optional<SeasonState> current = EclipticSeasonsBridge.read(server);
                    if (current.isEmpty()) {
                        if (layer != null) layer.clear();
                        SeasonalTintBridge.reset();
                        return;
                    }
                    SeasonState next = current.get();
                    if (layer != null) {
                        try { layer.capture(server, next.solarTerm()); }
                        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                            layer.clear();
                            Logger.global.logWarning("Cannot capture dynamic seasonal state: " + failure);
                        }
                        return;
                    }
                    SeasonState previous = lastState;
                    lastState = next;
                    if (previous == null || !previous.solarTerm().equals(next.solarTerm())) {
                        try {
                            SeasonalTintBridge.capture(server, next.solarTerm());
                        } catch (ReflectiveOperationException | LinkageError failure) {
                            Logger.global.logWarning("Unable to capture Ecliptic seasonal tint palette: " + failure);
                            SeasonalTintBridge.reset();
                        }
                    }
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
