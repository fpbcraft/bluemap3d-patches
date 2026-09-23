package dev.duzo.bluemap3d.players;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * Publishes a compact live player-state feed and installs the browser-side articulated
 * player renderer.
 *
 * <p>This deliberately does not copy or execute Fresh Animations assets. The browser rig
 * implements the same gameplay-state vocabulary so it can reproduce the important visual
 * cues without making EMF/ETF or an ARR resource pack a server dependency.
 */
@Mod(PlayerAddon.MOD_ID)
public final class PlayerAddon {

    public static final String MOD_ID = "bluemap3d_players";

    private static final Logger LOGGER = LoggerFactory.getLogger("BlueMap3D/Players");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private static final int PUBLISH_TICKS = 2;
    private static final long INTERVAL_MS = PUBLISH_TICKS * 50L;
    private static final String BASE = "assets/bluemap3d";
    private static final String SCRIPT_URL = BASE + "/bluemap3d.players.js";
    private static final String FEED_URL = BASE + "/players3d.json";

    private volatile BlueMapAPI blueMap;
    private volatile Path webRoot;
    private int tickCounter;

    public PlayerAddon() {
        NeoForge.EVENT_BUS.register(this);
        BlueMapAPI.onEnable(this::onBlueMapEnable);
        BlueMapAPI.onDisable(api -> onBlueMapDisable());
    }

    private synchronized void onBlueMapEnable(BlueMapAPI api) {
        try {
            Path root = api.getWebApp().getWebRoot().toAbsolutePath().normalize();
            copyResource(root);
            writeFeed(root, new Feed(1, INTERVAL_MS, List.of()));
            api.getWebApp().registerScript(SCRIPT_URL);
            blueMap = api;
            webRoot = root;
            LOGGER.info("Animated player renderer registered with BlueMap");
        } catch (IOException | RuntimeException error) {
            blueMap = null;
            webRoot = null;
            LOGGER.error("Could not install animated player renderer", error);
        }
    }

    private synchronized void onBlueMapDisable() {
        blueMap = null;
        webRoot = null;
        tickCounter = 0;
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter < PUBLISH_TICKS) {
            return;
        }
        tickCounter = 0;

        BlueMapAPI api = blueMap;
        Path root = webRoot;
        if (api == null || root == null) {
            return;
        }

        publish(event.getServer(), api, root);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        Path root = webRoot;
        if (root != null) {
            try {
                writeFeed(root, new Feed(1, INTERVAL_MS, List.of()));
            } catch (IOException ignored) {
                // Server is already stopping; a stale feed will be replaced next start.
            }
        }
        onBlueMapDisable();
    }

    private void publish(MinecraftServer server, BlueMapAPI api, Path root) {
        List<PlayerState> players = new ArrayList<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            // Match what another player can actually see. Invisibility potions and
            // server-side invisible states should not be defeated by the web map.
            if (player.isInvisible()) {
                continue;
            }

            List<String> maps;
            try {
                maps = api.getWorld(player.serverLevel())
                        .map(world -> world.getMaps().stream().map(BlueMapMap::getId).toList())
                        .orElse(List.of());
            } catch (RuntimeException error) {
                LOGGER.debug("Could not resolve BlueMap maps for {}", player.getScoreboardName());
                continue;
            }
            if (maps.isEmpty()) {
                continue;
            }

            SkinInfo skin = skinInfo(player);
            var velocity = player.getDeltaMovement();
            boolean usingItem = player.isUsingItem();
            InteractionHand usedHand = usingItem ? player.getUsedItemHand() : null;

            players.add(new PlayerState(
                    player.getStringUUID(),
                    player.getScoreboardName(),
                    maps,
                    player.getX(),
                    player.getY(),
                    player.getZ(),
                    player.getYRot(),
                    player.getYHeadRot(),
                    player.getXRot(),
                    velocity.x,
                    velocity.y,
                    velocity.z,
                    player.onGround(),
                    player.isSprinting(),
                    player.isCrouching(),
                    player.isSwimming(),
                    player.isInWater(),
                    player.onClimbable(),
                    player.isFallFlying(),
                    player.isPassenger(),
                    player.isSleeping(),
                    usingItem,
                    usedHand == null ? "" : usedHand.name().toLowerCase(Locale.ROOT),
                    player.getAttackAnim(1.0F) > 0.001F,
                    player.getPose().name().toLowerCase(Locale.ROOT),
                    player.getMainArm().name().toLowerCase(Locale.ROOT),
                    skin.slim(),
                    skin.url()
            ));
        }

        try {
            writeFeed(root, new Feed(1, INTERVAL_MS, List.copyOf(players)));
        } catch (IOException error) {
            LOGGER.warn("Could not publish animated player state", error);
        }
    }

    private static SkinInfo skinInfo(ServerPlayer player) {
        try {
            Property property = player.getGameProfile().getProperties().get("textures").stream()
                    .findFirst()
                    .orElse(null);
            if (property == null || property.value() == null || property.value().isBlank()) {
                return SkinInfo.EMPTY;
            }

            String json = new String(
                    Base64.getDecoder().decode(property.value()),
                    StandardCharsets.UTF_8
            );
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonObject textures = root.getAsJsonObject("textures");
            if (textures == null || !textures.has("SKIN")) {
                return SkinInfo.EMPTY;
            }

            JsonObject skin = textures.getAsJsonObject("SKIN");
            String url = validatedSkinUrl(skin.get("url").getAsString());
            boolean slim = skin.has("metadata")
                    && skin.get("metadata").isJsonObject()
                    && "slim".equalsIgnoreCase(
                            skin.getAsJsonObject("metadata").get("model").getAsString()
                    );
            return new SkinInfo(url, slim);
        } catch (RuntimeException error) {
            LOGGER.debug("Invalid skin metadata for {}", player.getScoreboardName());
            return SkinInfo.EMPTY;
        }
    }

    private static String validatedSkinUrl(String value) {
        URI uri = URI.create(value);
        String path = uri.getRawPath();
        if (!"textures.minecraft.net".equalsIgnoreCase(uri.getHost())
                || path == null
                || !path.matches("/texture/[0-9a-fA-F]+")) {
            throw new IllegalArgumentException("Unexpected Minecraft skin URL");
        }
        return "https://textures.minecraft.net" + path;
    }

    private static void copyResource(Path root) throws IOException {
        Path target = safeResolve(root, SCRIPT_URL);
        Files.createDirectories(target.getParent());
        try (InputStream in = PlayerAddon.class.getResourceAsStream("/web/bluemap3d.players.js")) {
            if (in == null) {
                throw new IOException("bluemap3d.players.js is missing from the addon jar");
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeFeed(Path root, Feed feed) throws IOException {
        Path target = safeResolve(root, FEED_URL);
        Files.createDirectories(target.getParent());

        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                GSON.toJson(feed),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        try {
            Files.move(
                    temporary,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path safeResolve(Path root, String relative) {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Refusing to write outside BlueMap web root");
        }
        return target;
    }

    private record Feed(int version, long intervalMs, List<PlayerState> players) {
    }

    private record PlayerState(
            String uuid,
            String name,
            List<String> maps,
            double x,
            double y,
            double z,
            float bodyYaw,
            float headYaw,
            float pitch,
            double vx,
            double vy,
            double vz,
            boolean onGround,
            boolean sprinting,
            boolean crouching,
            boolean swimming,
            boolean inWater,
            boolean climbing,
            boolean fallFlying,
            boolean passenger,
            boolean sleeping,
            boolean usingItem,
            String usedHand,
            boolean swinging,
            String pose,
            String mainArm,
            boolean slim,
            String skinUrl
    ) {
    }

    private record SkinInfo(String url, boolean slim) {
        private static final SkinInfo EMPTY = new SkinInfo(null, false);
    }
}
