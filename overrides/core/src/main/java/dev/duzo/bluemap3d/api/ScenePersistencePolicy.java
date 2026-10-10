package dev.duzo.bluemap3d.api;

/** Pure persistence policy kept free of Minecraft/NeoForge runtime types for regression tests. */
final class ScenePersistencePolicy {

    private ScenePersistencePolicy() {}

    static boolean isAuthoritativelyMissing(
            String provider,
            String dimension,
            String prefix,
            String savedProvider,
            String savedDimension,
            String savedId,
            boolean live) {
        return !live
                && provider.equals(savedProvider)
                && dimension.equals(savedDimension)
                && savedId.startsWith(prefix);
    }

    static boolean shouldDropForMigration(String provider, int sourceFormat) {
        return (sourceFormat < 7 && "create_contraptions".equals(provider))
                || (sourceFormat < 3 && "simulated_springs".equals(provider))
                || (sourceFormat < 4 && "sable_ships".equals(provider))
                || (sourceFormat < 5 && "simulated_ropes".equals(provider));
    }
}
