package dev.duzo.bluemapcopycats;

import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.world.block.BlockNeighborhood;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Explicitly opt-in, bounded diagnostics for a small BlueMap render area.
 * No block-entity payloads or full NBT are logged.
 *
 * JVM args:
 * -Dbluemap.copycats.trace=true
 * -Dbluemap.copycats.trace.center=-907,-389
 * -Dbluemap.copycats.trace.radius=24
 */
public final class CopycatsTrace {
    private static final boolean ENABLED = Boolean.getBoolean("bluemap.copycats.trace");
    private static final int LIMIT = Math.max(1,
            Integer.getInteger("bluemap.copycats.trace.limit", 240));
    private static final int RADIUS = Math.max(1,
            Integer.getInteger("bluemap.copycats.trace.radius", 24));
    private static final int[] CENTER = parseCenter(
            System.getProperty("bluemap.copycats.trace.center", ""));
    private static final AtomicInteger COUNT = new AtomicInteger();

    private CopycatsTrace() {}

    public static boolean enabled(BlockNeighborhood block) {
        if (!ENABLED || block == null) return false;
        if (CENTER == null) return true;
        return Math.abs(block.getX() - CENTER[0]) <= RADIUS
                && Math.abs(block.getZ() - CENTER[1]) <= RADIUS;
    }

    public static void log(BlockNeighborhood block, String phase, String details) {
        if (!enabled(block)) return;
        int line = COUNT.getAndIncrement();
        if (line >= LIMIT) return;
        Logger.global.logDebug("COPYCATS-TRACE phase=" + phase
                + " pos=(" + block.getX() + "," + block.getY() + ","
                + block.getZ() + ") " + details);
        if (line == LIMIT - 1) {
            Logger.global.logDebug("COPYCATS-TRACE limit reached (" + LIMIT
                    + "); increase -Dbluemap.copycats.trace.limit to capture more");
        }
    }

    private static int[] parseCenter(String value) {
        String[] parts = value.split(",");
        if (parts.length != 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
