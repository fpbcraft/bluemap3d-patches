package dev.duzo.bluemapseasons;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable, small server-thread capture. No per-position queries or chunk loading. */
public record SeasonalPalette(List<Entry> biomes, String term) {
    public record Entry(String id, int grass, int foliage, float mix,
                        int baseGrass, int baseFoliage, int snow) {}

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static SeasonalPalette capture(Object server, String solarTerm) throws ReflectiveOperationException {
        Object access = server.getClass().getMethod("registryAccess").invoke(server);
        Object biomeKey = Class.forName("net.minecraft.core.registries.Registries").getField("BIOME").get(null);
        Object registry = access.getClass().getMethod("registryOrThrow", Class.forName("net.minecraft.resources.ResourceKey"))
                .invoke(access, biomeKey);
        Class<?> biomeClass = Class.forName("net.minecraft.world.level.biome.Biome");
        Method get = registry.getClass().getMethod("get", Class.forName("net.minecraft.resources.ResourceLocation"));
        Method tag = Class.forName("com.teamtea.eclipticseasons.common.core.biome.BiomeClimateManager")
                .getMethod("getColorTag", biomeClass);
        Class<? extends Enum> termClass = Class.forName("com.teamtea.eclipticseasons.api.constant.solar.SolarTerm").asSubclass(Enum.class);
        Object term = Enum.valueOf(termClass, solarTerm);
        Method color = termClass.getMethod("getSolarTermColor", Class.forName("net.minecraft.tags.TagKey"));
        Object level = server.getClass().getMethod("overworld").invoke(server);
        Method snow = Class.forName("com.teamtea.eclipticseasons.common.core.biome.WeatherManager")
                .getMethod("getSnowDepthAtBiome", Class.forName("net.minecraft.world.level.Level"), biomeClass);
        Method baseGrass = biomeClass.getMethod("getGrassColor", double.class, double.class);
        Method baseFoliage = biomeClass.getMethod("getFoliageColor");
        List<Entry> entries = new ArrayList<>();
        for (Object key : (Iterable<?>) registry.getClass().getMethod("keySet").invoke(registry)) {
            Object biome = get.invoke(registry, key);
            if (biome == null) continue;
            int bg = ((Number) baseGrass.invoke(biome, 0d, 0d)).intValue();
            int bf = ((Number) baseFoliage.invoke(biome)).intValue();
            int grass = bg, foliage = bf;
            float mix = 0;
            Object climate = tag.invoke(null, biome);
            if (climate != null) {
                Object info = color.invoke(term, climate);
                mix = ((Number) info.getClass().getMethod("getMix").invoke(info)).floatValue();
                grass = ((Number) info.getClass().getMethod("getGrassColor").invoke(info)).intValue();
                foliage = ((Number) info.getClass().getMethod("getLeaveColor").invoke(info)).intValue();
            }
            entries.add(new Entry(key.toString(), grass, foliage, Math.max(0, Math.min(1, mix)), bg, bf,
                    Math.max(0, Math.min(100, ((Number) snow.invoke(null, level, biome)).intValue()))));
        }
        entries.sort(Comparator.comparing(Entry::id));
        if (entries.size() >= 65535) throw new IllegalStateException("Too many seasonal biomes");
        return new SeasonalPalette(List.copyOf(entries), solarTerm);
    }
}
