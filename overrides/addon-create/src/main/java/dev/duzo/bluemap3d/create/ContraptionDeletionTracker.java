package dev.duzo.bluemap3d.create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Positive deletion evidence for ordinary Create contraption entities.
 *
 * <p>Scene persistence intentionally keeps an object when it merely disappears from a
 * provider pass, because its chunk may have unloaded. Create also removes an entity when a
 * contraption is genuinely destroyed or disassembled, though, and those two cases must not
 * be treated alike. {@code ContraptionRemovalMixin} records only destructive removal
 * reasons here; {@link ContraptionProvider#deletedObjectIds(ServerLevel)} drains them on
 * the next publish.
 */
public final class ContraptionDeletionTracker {

    private static final Map<ServerLevel, Set<String>> DELETED = new ConcurrentHashMap<>();

    private ContraptionDeletionTracker() {}

    public static void record(AbstractContraptionEntity entity) {
        if (entity == null || entity instanceof CarriageContraptionEntity) return;
        if (!(entity.level() instanceof ServerLevel level)) return;

        ResourceLocation dim = level.dimension().location();
        record(level, dim.getNamespace() + "/" + dim.getPath() + "/" + entity.getUUID());
    }

    static void record(ServerLevel level, String objectId) {
        if (level == null || objectId == null || objectId.isBlank()) return;
        DELETED.computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet()).add(objectId);
    }

    static Collection<String> drain(ServerLevel level) {
        Set<String> deleted = DELETED.get(level);
        if (deleted == null || deleted.isEmpty()) return List.of();

        List<String> result = List.copyOf(deleted);
        deleted.removeAll(result);
        if (deleted.isEmpty()) DELETED.remove(level, deleted);
        return result;
    }

    static void clear() {
        DELETED.clear();
    }
}
