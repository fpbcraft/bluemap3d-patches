package dev.duzo.bluemap3d.entities;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.duzo.bluemap3d.bake.ConnectedTextureDiagnostics;
import dev.duzo.bluemap3d.bake.EntityModelSource;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Diagnostic commands for auditing entity compatibility on a real modpack. */
final class SurfaceMobCommands {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private SurfaceMobCommands() {
    }

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal("bluemap3d")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("dump-mobs")
                                .executes(context -> dump(context.getSource())))
                        .then(Commands.literal("dump-connected-textures")
                                .executes(context -> ConnectedTextureDiagnostics.dump(
                                        context.getSource())))
                        .then(Commands.literal("diagnose-mob")
                                .then(Commands.argument("entity", StringArgumentType.word())
                                        .executes(context -> diagnose(
                                                context.getSource(),
                                                StringArgumentType.getString(
                                                        context, "entity"))))));
    }

    private static int diagnose(
            net.minecraft.commands.CommandSourceStack source, String entityText) {
        ResourceLocation entityId = ResourceLocation.tryParse(entityText);
        if (entityId == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(entityId)) {
            source.sendFailure(Component.literal("Unknown entity id: " + entityText));
            return 0;
        }

        Map<String, String> metadata = Map.of();
        String liveEntity = "none";
        outer:
        for (var level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Mob mob)) continue;
                ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType());
                if (!entityId.equals(typeId)) continue;

                metadata = SurfaceMobProvider.diagnosticMetadata(mob);
                liveEntity = mob.getClass().getName() + " @ " + level.dimension().location();
                break outer;
            }
        }

        List<String> lines = new ArrayList<>(
                EntityModelSource.diagnoseEntity(entityId, metadata));
        lines.add(1, "liveEntity=" + liveEntity);
        Path output = source.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("bluemap3d")
                .resolve("mob-diagnostic-"
                        + entityId.getNamespace() + "-"
                        + entityId.getPath().replace('/', '_') + ".txt")
                .toAbsolutePath()
                .normalize();

        try {
            Files.createDirectories(output.getParent());
            Files.write(
                    output,
                    lines,
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            source.sendFailure(Component.literal(
                    "Could not write mob diagnostic: " + error.getMessage()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "Wrote entity model diagnostic for " + entityId + " to " + output),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private static int dump(net.minecraft.commands.CommandSourceStack source) {
        Path output = source.getServer()
                .getWorldPath(LevelResource.ROOT)
                .resolve("bluemap3d")
                .resolve("mob-registry.json")
                .toAbsolutePath()
                .normalize();

        Map<ResourceLocation, ResourceLocation> eggsByEntity = new HashMap<>();
        for (SpawnEggItem egg : SpawnEggItem.eggs()) {
            EntityType<?> type = egg.getType(egg.getDefaultInstance());
            ResourceLocation entityId = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            ResourceLocation eggId = BuiltInRegistries.ITEM.getKey(egg);
            if (entityId != null && eggId != null) {
                eggsByEntity.put(entityId, eggId);
            }
        }

        List<String> spawnEggItems = BuiltInRegistries.ITEM.keySet().stream()
                .filter(id -> id.getPath().endsWith("_spawn_egg"))
                .map(ResourceLocation::toString)
                .sorted()
                .toList();

        Map<String, Integer> spawnEggItemsByNamespace = new java.util.TreeMap<>();
        for (String item : spawnEggItems) {
            ResourceLocation id = ResourceLocation.tryParse(item);
            if (id != null) {
                spawnEggItemsByNamespace.merge(id.getNamespace(), 1, Integer::sum);
            }
        }

        List<EntityEntry> entities = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
            ResourceLocation egg = eggsByEntity.get(id);
            entities.add(new EntityEntry(
                    id.toString(),
                    id.getNamespace(),
                    egg == null ? null : egg.toString()));
        }
        entities.sort(Comparator.comparing(EntityEntry::id));

        List<SpawnEggEntry> spawnEggs = eggsByEntity.entrySet().stream()
                .map(entry -> new SpawnEggEntry(
                        entry.getValue().toString(),
                        entry.getKey().toString()))
                .sorted(Comparator.comparing(SpawnEggEntry::entity))
                .toList();

        Map<String, Integer> entitiesByNamespace = new java.util.TreeMap<>();
        Map<String, Integer> eggsByNamespace = new java.util.TreeMap<>();
        for (EntityEntry entity : entities) {
            entitiesByNamespace.merge(entity.namespace(), 1, Integer::sum);
            if (entity.spawnEgg() != null) {
                eggsByNamespace.merge(entity.namespace(), 1, Integer::sum);
            }
        }

        DumpFile file = new DumpFile(
                2,
                entities.size(),
                spawnEggs.size(),
                spawnEggItems.size(),
                entitiesByNamespace,
                eggsByNamespace,
                spawnEggItemsByNamespace,
                entities,
                spawnEggs,
                spawnEggItems);

        try {
            Files.createDirectories(output.getParent());
            Files.writeString(
                    output,
                    GSON.toJson(file) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            source.sendFailure(Component.literal(
                    "Could not write mob registry: " + error.getMessage()));
            return 0;
        }

        source.sendSuccess(
                () -> Component.literal(
                        "Wrote " + entities.size() + " entity types, "
                                + spawnEggs.size() + " mapped spawn eggs and "
                                + spawnEggItems.size() + " spawn-egg items to "
                                + output),
                false);
        return Command.SINGLE_SUCCESS;
    }

    private record EntityEntry(String id, String namespace, String spawnEgg) {
    }

    private record SpawnEggEntry(String item, String entity) {
    }

    private record DumpFile(
            int format,
            int entityCount,
            int spawnEggCount,
            int spawnEggItemCount,
            Map<String, Integer> entitiesByNamespace,
            Map<String, Integer> spawnEggsByNamespace,
            Map<String, Integer> spawnEggItemsByNamespace,
            List<EntityEntry> entities,
            List<SpawnEggEntry> spawnEggs,
            List<String> spawnEggItems) {
    }
}
