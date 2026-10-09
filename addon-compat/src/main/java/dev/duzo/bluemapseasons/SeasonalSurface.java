package dev.duzo.bluemapseasons;

import de.bluecolored.bluemap.core.world.Chunk;
import de.bluecolored.bluemap.core.world.LightData;

/** Saved-column metadata, independent of Minecraft's live Level and Ecliptic client code. */
public record SeasonalSurface(String biome, int height, int kind, boolean snow) {
    public static final SeasonalSurface EMPTY = new SeasonalSurface("", 0, 0, false);

    public static int kind(String id) {
        return switch (id) {
            case "minecraft:grass_block", "minecraft:short_grass", "minecraft:tall_grass",
                    "minecraft:fern", "minecraft:large_fern" -> 1;
            case "minecraft:oak_leaves", "minecraft:jungle_leaves", "minecraft:acacia_leaves",
                    "minecraft:dark_oak_leaves", "minecraft:vine" -> 2;
            default -> 0;
        };
    }

    public static boolean snowCandidate(String id) {
        return switch (id) {
            case "minecraft:grass_block", "minecraft:dirt", "minecraft:coarse_dirt",
                    "minecraft:podzol", "minecraft:mycelium", "minecraft:stone", "minecraft:gravel" -> true;
            default -> false;
        };
    }

    public static SeasonalSurface sample(Chunk chunk, int x, int z, int minY, int maxY) {
        if (!chunk.isGenerated() || !chunk.hasWorldSurfaceHeights()) return EMPTY;
        int y = Math.min(maxY, chunk.getWorldSurfaceY(x, z));
        // Heightmaps point above the surface. Descend only through air, not through roofs/water.
        while (y >= minY) {
            String id = chunk.getBlockState(x, y, z).getFormatted();
            if (!id.equals("minecraft:air") && !id.equals("minecraft:cave_air")
                    && !id.equals("minecraft:void_air")) {
                int bracket = id.indexOf('[');
                if (bracket >= 0) id = id.substring(0, bracket);
                LightData light = chunk.getLightData(x, y + 1, z, new LightData(0, 0));
                boolean snow = chunk.hasLightData() && light.getSkyLight() == 15
                        && light.getBlockLight() < 10 && snowCandidate(id);
                return new SeasonalSurface(chunk.getBiome(x, y, z).getKey().toString(), y + 1, kind(id), snow);
            }
            y--;
        }
        return EMPTY;
    }
}
