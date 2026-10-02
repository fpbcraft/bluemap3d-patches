package dev.duzo.bluemap3d.entities;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Server-safe vanilla mob appearance registry.
 *
 * <p>These are simplified cuboid silhouettes built from ordinary resource-pack models
 * and vanilla block textures. Exact skins, poses and modded entity models can layer on
 * top of this seam later without bringing client-only EntityRenderer classes server-side.
 */
final class MobAppearanceRegistry {
    private static final String WHITE = "minecraft:block/white_wool";
    private static final String LIGHT_GRAY = "minecraft:block/light_gray_wool";
    private static final String GRAY = "minecraft:block/gray_wool";
    private static final String BLACK = "minecraft:block/black_wool";
    private static final String BROWN = "minecraft:block/brown_wool";
    private static final String RED = "minecraft:block/red_wool";
    private static final String ORANGE = "minecraft:block/orange_wool";
    private static final String YELLOW = "minecraft:block/yellow_wool";
    private static final String LIME = "minecraft:block/lime_wool";
    private static final String GREEN = "minecraft:block/green_wool";
    private static final String CYAN = "minecraft:block/cyan_wool";
    private static final String LIGHT_BLUE = "minecraft:block/light_blue_wool";
    private static final String BLUE = "minecraft:block/blue_wool";
    private static final String PURPLE = "minecraft:block/purple_wool";
    private static final String PINK = "minecraft:block/pink_wool";
    private static final String IRON = "minecraft:block/iron_block";
    private static final String SNOW = "minecraft:block/snow";
    private static final String PUMPKIN = "minecraft:block/carved_pumpkin";
    private static final String SCULK = "minecraft:block/sculk";
    private static final String SLIME = "minecraft:block/slime_block";
    private static final String MAGMA = "minecraft:block/magma";

    private static final MobAppearance COW = quadruped("cow", BROWN, BROWN, BLACK);
    private static final MobAppearance PIG = quadruped("pig", PINK, PINK, PINK);
    private static final MobAppearance SHEEP = quadruped("sheep", WHITE, LIGHT_GRAY, LIGHT_GRAY);
    private static final MobAppearance GOAT = quadruped("goat", WHITE, LIGHT_GRAY, GRAY);
    private static final MobAppearance HORSE = quadruped("horse", BROWN, BROWN, BLACK);
    private static final MobAppearance LLAMA = quadruped("llama", LIGHT_GRAY, WHITE, BROWN);
    private static final MobAppearance CAMEL = quadruped("camel", YELLOW, BROWN, BROWN);
    private static final MobAppearance WOLF = quadruped("wolf", LIGHT_GRAY, GRAY, GRAY);
    private static final MobAppearance CAT = quadruped("cat", ORANGE, ORANGE, WHITE);
    private static final MobAppearance FOX = quadruped("fox", ORANGE, ORANGE, BLACK);
    private static final MobAppearance POLAR_BEAR = quadruped("polar-bear", WHITE, WHITE, LIGHT_GRAY);
    private static final MobAppearance PANDA = quadruped("panda", WHITE, BLACK, BLACK);
    private static final MobAppearance RABBIT = quadruped("rabbit", BROWN, BROWN, LIGHT_GRAY);
    private static final MobAppearance RAVAGER = quadruped("ravager", GRAY, LIGHT_GRAY, BLACK);
    private static final MobAppearance SNIFFER = quadruped("sniffer", RED, GREEN, BROWN);
    private static final MobAppearance ARMADILLO = quadruped("armadillo", BROWN, BROWN, GRAY);
    private static final MobAppearance TURTLE = quadruped("turtle", GREEN, LIME, GREEN);
    private static final MobAppearance FROG = quadruped("frog", GREEN, LIME, GREEN);
    private static final MobAppearance HOGLIN = quadruped("hoglin", RED, BROWN, BLACK);
    private static final MobAppearance STRIDER = quadruped("strider", RED, RED, PURPLE);

    private static final MobAppearance ZOMBIE = humanoid("zombie", GREEN, CYAN, BLUE);
    private static final MobAppearance SKELETON = humanoid("skeleton", LIGHT_GRAY, LIGHT_GRAY, LIGHT_GRAY);
    private static final MobAppearance ENDERMAN = humanoid("enderman", BLACK, BLACK, BLACK);
    private static final MobAppearance VILLAGER = humanoid("villager", PINK, BROWN, BROWN);
    private static final MobAppearance WITCH = humanoid("witch", PINK, PURPLE, BLACK);
    private static final MobAppearance PILLAGER = humanoid("pillager", LIGHT_GRAY, GRAY, BLACK);
    private static final MobAppearance PIGLIN = humanoid("piglin", PINK, BROWN, BLACK);
    private static final MobAppearance IRON_GOLEM = humanoid("iron-golem", IRON, IRON, IRON);
    private static final MobAppearance SNOW_GOLEM = humanoid("snow-golem", PUMPKIN, SNOW, SNOW);
    private static final MobAppearance WARDEN = humanoid("warden", SCULK, SCULK, CYAN);
    private static final MobAppearance ALLAY = humanoid("allay", LIGHT_BLUE, LIGHT_BLUE, WHITE);
    private static final MobAppearance VEX = humanoid("vex", LIGHT_GRAY, LIGHT_BLUE, GRAY);
    private static final MobAppearance BREEZE = humanoid("breeze", LIGHT_BLUE, CYAN, WHITE);
    private static final MobAppearance BLAZE = humanoid("blaze", YELLOW, ORANGE, YELLOW);

    private static final MobAppearance CREEPER =
            appearance("creeper", "entity/creeper", Map.of("body", GREEN, "head", GREEN, "leg", GREEN));
    private static final MobAppearance SPIDER =
            appearance("spider", "entity/spider", Map.of("body", BLACK, "head", GRAY, "leg", BLACK));
    private static final MobAppearance CAVE_SPIDER =
            appearance("cave-spider", "entity/spider", Map.of("body", BLUE, "head", CYAN, "leg", BLACK));
    private static final MobAppearance CHICKEN = bird("chicken", WHITE, RED, WHITE, YELLOW);
    private static final MobAppearance PARROT = bird("parrot", RED, BLUE, BLUE, GRAY);
    private static final MobAppearance BEE = bird("bee", YELLOW, BLACK, WHITE, BLACK);
    private static final MobAppearance PHANTOM = bird("phantom", BLUE, CYAN, BLUE, BLACK);
    private static final MobAppearance BAT = bird("bat", BLACK, BLACK, GRAY, BLACK);

    private static final MobAppearance SLIME_MOB = cube("slime", SLIME);
    private static final MobAppearance MAGMA_CUBE = cube("magma-cube", MAGMA);
    private static final MobAppearance GHAST = cube("ghast", WHITE);
    private static final MobAppearance SHULKER = cube("shulker", PURPLE);
    private static final MobAppearance SQUID = cube("squid", BLUE);
    private static final MobAppearance GLOW_SQUID = cube("glow-squid", CYAN);

    private static final MobAppearance COD = fish("cod", BROWN, YELLOW);
    private static final MobAppearance SALMON = fish("salmon", RED, LIGHT_GRAY);
    private static final MobAppearance TROPICAL_FISH = fish("tropical-fish", YELLOW, CYAN);
    private static final MobAppearance PUFFERFISH = fish("pufferfish", YELLOW, BROWN);
    private static final MobAppearance DOLPHIN = fish("dolphin", GRAY, LIGHT_GRAY);
    private static final MobAppearance AXOLOTL = fish("axolotl", PINK, LIGHT_BLUE);
    private static final MobAppearance GUARDIAN = fish("guardian", CYAN, ORANGE);

    private MobAppearanceRegistry() {
    }

    static MobAppearance resolve(ResourceLocation typeId) {
        if (typeId == null || !"minecraft".equals(typeId.getNamespace())) return null;

        return switch (typeId.getPath()) {
            case "cow", "mooshroom" -> COW;
            case "pig" -> PIG;
            case "sheep" -> SHEEP;
            case "goat" -> GOAT;
            case "horse", "skeleton_horse", "zombie_horse" -> HORSE;
            case "donkey", "mule", "llama", "trader_llama" -> LLAMA;
            case "camel" -> CAMEL;
            case "wolf" -> WOLF;
            case "cat", "ocelot" -> CAT;
            case "fox" -> FOX;
            case "polar_bear" -> POLAR_BEAR;
            case "panda" -> PANDA;
            case "rabbit" -> RABBIT;
            case "ravager" -> RAVAGER;
            case "sniffer" -> SNIFFER;
            case "armadillo" -> ARMADILLO;
            case "turtle" -> TURTLE;
            case "frog" -> FROG;
            case "hoglin", "zoglin" -> HOGLIN;
            case "strider" -> STRIDER;

            case "zombie", "husk", "drowned", "zombie_villager" -> ZOMBIE;
            case "skeleton", "stray", "bogged", "wither_skeleton" -> SKELETON;
            case "enderman" -> ENDERMAN;
            case "villager", "wandering_trader" -> VILLAGER;
            case "witch" -> WITCH;
            case "pillager", "vindicator", "evoker", "illusioner" -> PILLAGER;
            case "piglin", "piglin_brute", "zombified_piglin" -> PIGLIN;
            case "iron_golem" -> IRON_GOLEM;
            case "snow_golem" -> SNOW_GOLEM;
            case "warden" -> WARDEN;
            case "allay" -> ALLAY;
            case "vex" -> VEX;
            case "breeze" -> BREEZE;
            case "blaze" -> BLAZE;

            case "creeper" -> CREEPER;
            case "spider" -> SPIDER;
            case "cave_spider" -> CAVE_SPIDER;
            case "chicken" -> CHICKEN;
            case "parrot" -> PARROT;
            case "bee" -> BEE;
            case "phantom" -> PHANTOM;
            case "bat" -> BAT;
            case "slime" -> SLIME_MOB;
            case "magma_cube" -> MAGMA_CUBE;
            case "ghast" -> GHAST;
            case "shulker" -> SHULKER;
            case "squid" -> SQUID;
            case "glow_squid" -> GLOW_SQUID;

            case "cod" -> COD;
            case "salmon" -> SALMON;
            case "tropical_fish" -> TROPICAL_FISH;
            case "pufferfish" -> PUFFERFISH;
            case "dolphin" -> DOLPHIN;
            case "axolotl" -> AXOLOTL;
            case "guardian", "elder_guardian" -> GUARDIAN;
            case "silverfish", "endermite" -> CAVE_SPIDER;
            default -> null;
        };
    }

    private static MobAppearance quadruped(String id, String body, String head, String leg) {
        return appearance(id, "entity/quadruped", Map.of("body", body, "head", head, "leg", leg));
    }

    private static MobAppearance humanoid(String id, String head, String torso, String limb) {
        return appearance(id, "entity/humanoid", Map.of("head", head, "torso", torso, "limb", limb));
    }

    private static MobAppearance bird(String id, String body, String head, String wing, String leg) {
        return appearance(
                id, "entity/bird", Map.of("body", body, "head", head, "wing", wing, "leg", leg));
    }

    private static MobAppearance fish(String id, String body, String accent) {
        return appearance(id, "entity/fish", Map.of("body", body, "accent", accent));
    }

    private static MobAppearance cube(String id, String body) {
        return appearance(id, "entity/cube", Map.of("body", body));
    }

    private static MobAppearance appearance(
            String id, String model, Map<String, String> textures) {
        return MobAppearance.of(id, model, textures);
    }
}
