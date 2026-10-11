package dev.duzo.bluemapseasons;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Optional runtime bridge: none of these classes are linked unless Ecliptic Seasons is installed.
 * read() must be called on the Minecraft server thread.
 */
final class EclipticSeasonsBridge {
    private static final String API = "com.teamtea.eclipticseasons.api.EclipticSeasonsApi";
    private static final String SERVER = "net.neoforged.neoforge.server.ServerLifecycleHooks";

    private EclipticSeasonsBridge() {}

    static boolean isAvailable() {
        try {
            Class.forName(API);
            return true;
        } catch (ClassNotFoundException | LinkageError missing) {
            return false;
        }
    }

    static Object getServer() throws ReflectiveOperationException {
        return Class.forName(SERVER).getMethod("getCurrentServer").invoke(null);
    }

    static Optional<SeasonState> read(Object server) throws ReflectiveOperationException {
        if (server == null) return Optional.empty();
        Object level = server.getClass().getMethod("overworld").invoke(server);
        if (level == null) return Optional.empty();

        Class<?> apiType = Class.forName(API);
        Object api = apiType.getMethod("getInstance").invoke(null);
        Class<?> levelType = Class.forName("net.minecraft.world.level.Level");
        boolean enabled = (boolean) apiType.getMethod("isSeasonEnabled", levelType).invoke(api, level);
        if (!enabled) return Optional.empty();

        Object term = apiType.getMethod("getSolarTerm", levelType).invoke(api, level);
        if (term == null || "NONE".equals(term.toString())) return Optional.empty();
        Object season = term.getClass().getMethod("getSeason").invoke(term);
        if (season == null) return Optional.empty();
        return Optional.of(new SeasonState(term.toString(), season.toString()));
    }

    static void executeOnServer(Object server, Runnable task) throws ReflectiveOperationException {
        Method execute = server.getClass().getMethod("execute", Runnable.class);
        execute.invoke(server, task);
    }
}
