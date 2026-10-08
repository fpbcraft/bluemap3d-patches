package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.core.util.math.Color;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-thread Ecliptic color snapshot; the BlueMap render threads only read immutable data.
 * This intentionally does not load any Ecliptic client-only classes.
 */
public final class SeasonalTintBridge {
    private static volatile Map<String, SeasonalTint> snapshot = Map.of();

    private SeasonalTintBridge() {}

    static void reset() {
        snapshot = Map.of();
    }

    /** Runs only on the Minecraft server thread. */
    static void capture(Object server, String solarTerm) throws ReflectiveOperationException {
        Object access = server.getClass().getMethod("registryAccess").invoke(server);
        Class<?> registries = Class.forName("net.minecraft.core.registries.Registries");
        Object biomeKey = registries.getField("BIOME").get(null);
        Class<?> resourceKey = Class.forName("net.minecraft.resources.ResourceKey");
        Object registry = access.getClass().getMethod("registryOrThrow", resourceKey).invoke(access, biomeKey);
        Class<?> resourceLocation = Class.forName("net.minecraft.resources.ResourceLocation");
        Class<?> mcBiome = Class.forName("net.minecraft.world.level.biome.Biome");
        Class<?> climate = Class.forName("com.teamtea.eclipticseasons.common.core.biome.BiomeClimateManager");
        Method getTag = climate.getMethod("getColorTag", mcBiome);
        Method get = registry.getClass().getMethod("get", resourceLocation);
        Object term = Enum.valueOf((Class<? extends Enum>) Class.forName(
                "com.teamtea.eclipticseasons.api.constant.solar.SolarTerm").asSubclass(Enum.class), solarTerm);
        Method termColor = term.getClass().getMethod("getSolarTermColor", Class.forName("net.minecraft.tags.TagKey"));

        Map<String, SeasonalTint> colors = new HashMap<>();
        for (Object key : (Iterable<?>) registry.getClass().getMethod("keySet").invoke(registry)) {
            Object biome = get.invoke(registry, key);
            if (biome == null) continue;
            Object tag = getTag.invoke(null, biome);
            if (tag == null) continue;
            Object info = termColor.invoke(term, tag);
            float mix = ((Number) info.getClass().getMethod("getMix").invoke(info)).floatValue();
            int grass = ((Number) info.getClass().getMethod("getGrassColor").invoke(info)).intValue();
            int foliage = ((Number) info.getClass().getMethod("getLeaveColor").invoke(info)).intValue();
            colors.put(key.toString(), new SeasonalTint(grass, foliage, mix));
        }
        snapshot = Collections.unmodifiableMap(colors);
    }

    public static Color tint(String blockId, BlockNeighborhood block, Color base) {
        if (!Boolean.parseBoolean(System.getProperty("bluemap.compat.ecliptic.tint", "true"))) return base;
        if (block == null || block.getBiome() == null || block.getBiome().getKey() == null) return base;
        SeasonalTint tint = snapshot.get(block.getBiome().getKey().toString());
        if (tint == null) return base;

        String id = blockId.contains("[") ? blockId.substring(0, blockId.indexOf('[')) : blockId;
        boolean leaves = id.endsWith("_leaves") && !id.endsWith("spruce_leaves")
                && !id.endsWith("birch_leaves") && !id.endsWith("mangrove_leaves");
        boolean grass = id.equals("minecraft:grass_block") || id.equals("minecraft:short_grass")
                || id.equals("minecraft:tall_grass") || id.equals("minecraft:fern")
                || id.equals("minecraft:large_fern");
        if (!leaves && !grass) return base;
        return tint.apply(base, leaves);
    }
}
