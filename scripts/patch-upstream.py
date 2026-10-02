from pathlib import Path

from release_metadata import load_release

RELEASE = load_release()
VERSION = RELEASE["version"]
BLUEMAP_TARGET = RELEASE["blueMapVersion"]

def replace(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"expected text not found in {path}: {old!r}")
    p.write_text(s.replace(old, new))

replace(
    "addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java",
    "GEOMETRY_REVISION + 15",
    "GEOMETRY_REVISION + 33",
)
# ShipProvider's old geometry version is a cheap heuristic (bounds + mass + section
# serialized sizes) and can miss real block removal/re-addition. Replace the complete
# method after the base patch with an exact structural hash cached behind Sable's
# authoritative block-change signal.
replace(
    "addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java",
    'if (!"copycats".equals(namespace)) {',
    'if (!CompatRegistry.get().preserveMovingNamespace(namespace)) {',
)

p = Path("addon-sable/src/main/java/dev/duzo/bluemap3d/sable/ShipProvider.java")
ship_source = p.read_text()

# Generic scene persistence needs positive destruction evidence for Sable sub-levels.
# ShipProvider already observes REMOVED vs UNLOADED for its legacy/private cache; retain
# those UUIDs until core's persistence wrapper consumes them.
deleted_field_needle = '    private final Map<String, SceneObject> lastObjects = new ConcurrentHashMap<>();'
if deleted_field_needle not in ship_source:
    raise SystemExit("ShipProvider deleted-id field insertion point not found")
ship_source = ship_source.replace(
    deleted_field_needle,
    deleted_field_needle
        + '\n    private final Map<ServerLevel, Set<String>> deletedShipIds = new ConcurrentHashMap<>();',
    1,
)

ship_start = ship_source.index(
    "    private static long geometryVersion(ServerSubLevel ship, BoundingBox3ic bounds) {")
ship_end = ship_source.index(
    "    /** FNV-1a, sixty-four bits of it. */",
    ship_start)
ship_source = (
    ship_source[:ship_start]
    + '''    private static long geometryVersion(ServerSubLevel ship, BoundingBox3ic bounds) {
        // Exact hash is computed once on initial observation and again only after Sable
        // reports a real plot block change. Ship movement never dirties it.
        // Revision 36 invalidates meshes produced before the stale-topology/lifecycle fixes.
        // Subsequent publishes return to hash-based reuse and only structural changes rebake.
        return mix(ShipGeometryRevisionTracker.structureHash(ship), 36L);
    }

'''
    + ship_source[ship_end:]
)
# Drop tracker state when the ship is genuinely removed (not distance-unloaded).
remove_needle = '''                lastObjects.remove(key);
                pendingSnapshots.remove(objectId);
                publishedVersions.remove(objectId);
                removePersisted(cacheFile(level), key);
'''
if remove_needle not in ship_source:
    raise SystemExit("ShipProvider revision tracker removal insertion point not found")
ship_source = ship_source.replace(
    remove_needle,
    '''                lastObjects.remove(key);
                pendingSnapshots.remove(objectId);
                publishedVersions.remove(objectId);
                ShipGeometryRevisionTracker.clear(uuid);
                deletedShipIds
                        .computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet())
                        .add(objectId);
                removePersisted(cacheFile(level), key);
''',
    1,
)
# Sable snapshot cache format 4 intentionally refuses pre-fix cached ship snapshots.
# Those snapshots may contain child blocks that were assembled away while the old dirty
# hook missed the removal. Current ships repopulate the cache after their corrected mesh
# is published; unloaded ships stay hidden rather than showing a known-stale hull.
ship_source = ship_source.replace(
    "if (state != null && state.ships() != null) {",
    "if (state != null && state.format() >= 4 && state.ships() != null) {",
    1,
)
ship_source = ship_source.replace(
    "new SnapshotFile(1, List.copyOf(saved.values()))",
    "new SnapshotFile(4, List.copyOf(saved.values()))",
    1,
)
deleted_method_needle = '    private void observeContainer(ServerLevel level, ServerSubLevelContainer container) {'
if deleted_method_needle not in ship_source:
    raise SystemExit("ShipProvider deletedObjectIds insertion point not found")
ship_source = ship_source.replace(
    deleted_method_needle,
    '''    @Override
    public Collection<String> deletedObjectIds(ServerLevel level) {
        Set<String> deleted = deletedShipIds.get(level);
        if (deleted == null || deleted.isEmpty()) return List.of();

        List<String> result = List.copyOf(deleted);
        deleted.removeAll(result);
        if (deleted.isEmpty()) deletedShipIds.remove(level, deleted);
        return result;
    }

''' + deleted_method_needle,
    1,
)

p.write_text(ship_source)

p = Path("addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java")
s = p.read_text()

import_needle = 'import dev.duzo.bluemap3d.api.SceneObjectProvider;'
if import_needle not in s:
    raise SystemExit("ContraptionProvider Sable import insertion point not found")
s = s.replace(
    import_needle,
    import_needle
        + '\nimport dev.duzo.bluemap3d.compat.CompatRegistry;'
        + '\nimport dev.ryanhcode.sable.Sable;'
        + '\nimport dev.ryanhcode.sable.api.sublevel.SubLevelContainer;'
        + '\nimport dev.ryanhcode.sable.sublevel.SubLevel;'
        + '\nimport com.simibubi.create.content.contraptions.ControlledContraptionEntity;'
        + '\nimport com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;'
        + '\nimport net.minecraft.world.level.block.entity.BlockEntity;'
        + '\nimport net.minecraft.nbt.ListTag;'
        + '\nimport net.minecraft.nbt.NbtAccounter;'
        + '\nimport net.minecraft.nbt.NbtIo;'
        + '\nimport net.minecraft.nbt.Tag;'
        + '\nimport java.io.IOException;'
        + '\nimport java.io.InputStream;'
        + '\nimport java.io.OutputStream;'
        + '\nimport java.nio.file.Files;'
        + '\nimport java.nio.file.Path;'
        + '\nimport java.nio.file.StandardCopyOption;'
        + '\nimport java.nio.file.StandardOpenOption;',
    1,
)

trace_needle = '    private final Set<String> unrotatable = ConcurrentHashMap.newKeySet();'
if trace_needle not in s:
    raise SystemExit("ContraptionProvider Sable trace insertion point not found")
s = s.replace(
    trace_needle,
    trace_needle
        + '\n\n    /** Contraption classes already reported as projected out of a Sable ship. */'
        + '\n    private final Set<String> sableProjected = ConcurrentHashMap.newKeySet();'
        + '\n\n    /** Last known child contraptions for Sable ships, retained when their live entity unloads. */'
        + '\n    private final Map<ServerLevel, Map<String, SableContraptionCache>> sableContraptionCaches = new ConcurrentHashMap<>();'
        + '\n\n    /** Positive deletion evidence consumed by generic scene persistence. */'
        + '\n    private final Map<ServerLevel, Set<String>> deletedContraptionIds = new ConcurrentHashMap<>();'
        + '\n\n    /** ControlledContraptionEntity controller position, used only to invalidate disassembled cached rotors. */'
        + '\n    private static final Field CONTROLLER_POS_FIELD = controllerPosField();'
        + '\n\n    /** Persistent last-known Sable child snapshots, separate from user-editable compatibility config. */'
        + '\n    private static final Path SABLE_CACHE_FILE = Path.of("config", "bluemap3d", "cache", "sable-child-contraptions.nbt");'
        + '\n    private final Set<ResourceKey<Level>> sablePersistentLoaded = ConcurrentHashMap.newKeySet();',
    1,
)

pose_needle = '''        Vec3 position = entity.getAnchorVec().add(PIVOT);
        Quaternionf rotation = rotationOf(entity);'''
if pose_needle not in s:
    raise SystemExit("ContraptionProvider pose insertion point not found")
pose_replacement = '''        Vec3 position = entity.getAnchorVec().add(PIVOT);
        Quaternionf rotation = rotationOf(entity);

        // Aeronautics propeller bearings are ordinary Create contraption entities, but
        // when mounted on a Sable ship their entity coordinates live in Sable's hidden
        // plot. The ship itself is published separately in world space, so publishing
        // this raw Create transform strands the entire rotor in the hidden plot.
        //
        // Compose child -> plot (Create) with plot -> world (Sable):
        //   worldPos = shipPose.transformPosition(createAnchor)
        //   worldRot = shipOrientation * createRotation
        SubLevel containingSubLevel = Sable.HELPER.getContaining(entity);
        if (containingSubLevel != null) {
            var pose = containingSubLevel.logicalPose();
            Vec3 localPosition = position;
            Quaternionf localRotation = new Quaternionf(rotation);
            rememberSableContraption(
                    level, entity, geometry, localPosition, localRotation, containingSubLevel);

            position = pose.transformPosition(localPosition);

            var parentRotation = pose.orientation();
            rotation = new Quaternionf(
                    (float) parentRotation.x(), (float) parentRotation.y(),
                    (float) parentRotation.z(), (float) parentRotation.w())
                    .mul(rotation);

            String traceKey = entity.getClass().getName();
            if (sableProjected.add(traceKey)) {
                LOGGER.info(
                        "SABLE-CONTRAPTION-DIAG type={} entity={} sublevel={} localPos={} worldPos={}",
                        traceKey, entity.getUUID(), containingSubLevel.getUniqueId(),
                        localPosition, position);
            }
        }'''
s = s.replace(pose_needle, pose_replacement, 1)

objects_needle = '''        List<? extends AbstractContraptionEntity> entities = level.getEntities(
                EntityTypeTest.forClass(AbstractContraptionEntity.class),
                e -> !(e instanceof CarriageContraptionEntity));
        for (AbstractContraptionEntity entity : entities) {
            SceneObject object = toSceneObject(level, entity, maxBlocks);
            if (object != null) {
                out.add(object);
            }
        }

        out.addAll(trainCarriages(level, maxBlocks));'''
if objects_needle not in s:
    raise SystemExit("ContraptionProvider Sable cache object-loop insertion point not found")
s = s.replace(objects_needle, '''        loadPersistentSableContraptions(level, maxBlocks);

        List<? extends AbstractContraptionEntity> entities = level.getEntities(
                EntityTypeTest.forClass(AbstractContraptionEntity.class),
                e -> !(e instanceof CarriageContraptionEntity));
        Set<String> liveSableContraptions = new HashSet<>();
        for (AbstractContraptionEntity entity : entities) {
            SubLevel liveSubLevel = Sable.HELPER.getContaining(entity);
            if (liveSubLevel != null) {
                liveSableContraptions.add(sableContraptionKey(entity, liveSubLevel));
            }

            SceneObject object = toSceneObject(level, entity, maxBlocks);
            if (object != null) {
                out.add(object);
            }
        }

        appendCachedSableContraptions(level, out, liveSableContraptions);
        out.addAll(trainCarriages(level, maxBlocks));''', 1)


# Persist Sable child contraptions after their Create entity unloads due player distance.
cache_anchor = '''    /**
     * Walks {@code Create.RAILWAYS.trains} rather than any entity list, so a train carries
'''
if cache_anchor not in s:
    raise SystemExit("ContraptionProvider Sable cache method insertion point not found")
cache_methods = '''    private void rememberSableContraption(
            ServerLevel level,
            AbstractContraptionEntity entity,
            CarriageGeometry geometry,
            Vec3 localPosition,
            Quaternionf localRotation,
            SubLevel subLevel) {
        String objectId = level.dimension().location().getNamespace()
                + "/" + level.dimension().location().getPath()
                + "/" + entity.getUUID();

        BlockPos controllerPos = controllerPosOf(entity);
        String cacheKey = sableContraptionKey(entity, subLevel);
        SableContraptionCache next = new SableContraptionCache(
                objectId,
                geometry.volume(),
                geometry.version(),
                localPosition,
                new Quaternionf(localRotation),
                subLevel.getUniqueId(),
                controllerPos,
                level.dimension(),
                entity.getContraption().writeNBT(level.registryAccess(), false));

        SableContraptionCache previous = sableContraptionCaches
                .computeIfAbsent(level, ignored -> new ConcurrentHashMap<>())
                .put(cacheKey, next);

        if (previous != null && !previous.objectId().equals(next.objectId())) {
            // Reassembly creates a new Create entity UUID for the same logical bearing.
            // Generic persistence otherwise keeps the previous UUID forever because a
            // missing object is intentionally treated as possibly unloaded.
            markContraptionDeleted(level, previous.objectId());
        }

        if (previous == null
                || previous.version() != next.version()
                || !previous.objectId().equals(next.objectId())) {
            savePersistentSableCaches();
        }
    }

    private void loadPersistentSableContraptions(ServerLevel level, int maxBlocks) {
        if (!sablePersistentLoaded.add(level.dimension())) return;
        if (!Files.isRegularFile(SABLE_CACHE_FILE)) return;

        try (InputStream input = Files.newInputStream(SABLE_CACHE_FILE)) {
            CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            if (root.getInt("Version") < 2) {
                // v1 predates positive deletion/reassembly semantics and may contain
                // ghost Create entity UUIDs. Do not restore it; current live children
                // repopulate a clean v2 cache.
                LOGGER.info(
                        "Ignoring legacy Sable child cache v{}; current children will repopulate",
                        root.getInt("Version"));
                return;
            }
            ListTag entries = root.getList("Entries", Tag.TAG_COMPOUND);
            String dimensionId = level.dimension().location().toString();
            Map<String, SableContraptionCache> levelCache = sableContraptionCaches
                    .computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());

            int restored = 0;
            for (int i = 0; i < entries.size(); i++) {
                CompoundTag entry = entries.getCompound(i);
                if (!dimensionId.equals(entry.getString("Dimension"))) continue;
                if (!entry.hasUUID("SubLevel")
                        || !entry.contains("Contraption", Tag.TAG_COMPOUND)) {
                    continue;
                }

                String key = entry.getString("Key");
                if (key.isBlank() || levelCache.containsKey(key)) continue;

                CompoundTag contraptionNbt = entry.getCompound("Contraption").copy();
                Contraption contraption = Contraption.fromNBT(level, contraptionNbt, false);
                CarriageGeometry geometry = buildGeometry(
                        contraption, maxBlocks, "persisted Sable child " + key);
                if (geometry == null) continue;

                BlockPos controllerPos = entry.contains("ControllerPos", Tag.TAG_LONG)
                        ? BlockPos.of(entry.getLong("ControllerPos"))
                        : null;
                Vec3 localPosition = new Vec3(
                        entry.getDouble("PosX"),
                        entry.getDouble("PosY"),
                        entry.getDouble("PosZ"));
                Quaternionf localRotation = new Quaternionf(
                        entry.getFloat("RotX"),
                        entry.getFloat("RotY"),
                        entry.getFloat("RotZ"),
                        entry.getFloat("RotW"));

                levelCache.put(key, new SableContraptionCache(
                        entry.getString("ObjectId"),
                        geometry.volume(),
                        geometry.version(),
                        localPosition,
                        localRotation,
                        entry.getUUID("SubLevel"),
                        controllerPos,
                        level.dimension(),
                        contraptionNbt));
                restored++;
            }

            if (restored > 0) {
                LOGGER.info(
                        "Restored {} persisted Sable child contraption(s) for {}",
                        restored, dimensionId);
            }
        } catch (IOException | RuntimeException error) {
            LOGGER.warn(
                    "Could not restore persisted Sable child contraptions from {}",
                    SABLE_CACHE_FILE, error);
        }
    }

    private void savePersistentSableCaches() {
        try {
            Files.createDirectories(SABLE_CACHE_FILE.getParent());

            ListTag entries = new ListTag();
            Set<String> replacedDimensions = new HashSet<>();
            for (ServerLevel cachedLevel : sableContraptionCaches.keySet()) {
                replacedDimensions.add(cachedLevel.dimension().location().toString());
            }

            // Preserve dimensions that this process has not materialized into memory yet.
            // Once a dimension has a live cache map, the in-memory state is authoritative
            // so removals/disassemblies are allowed to delete its persisted entries.
            if (Files.isRegularFile(SABLE_CACHE_FILE)) {
                try (InputStream input = Files.newInputStream(SABLE_CACHE_FILE)) {
                    CompoundTag previousRoot =
                            NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
                    // Never carry v1 entries forward: they are exactly the snapshots that
                    // can contain now-invalid child UUIDs.
                    if (previousRoot.getInt("Version") >= 2) {
                        ListTag previousEntries =
                                previousRoot.getList("Entries", Tag.TAG_COMPOUND);
                        for (int i = 0; i < previousEntries.size(); i++) {
                            CompoundTag previous = previousEntries.getCompound(i);
                            if (!replacedDimensions.contains(previous.getString("Dimension"))) {
                                entries.add(previous.copy());
                            }
                        }
                    }
                } catch (IOException | RuntimeException error) {
                    LOGGER.warn(
                            "Could not preserve untouched dimensions from Sable child cache",
                            error);
                }
            }

            for (Map.Entry<ServerLevel, Map<String, SableContraptionCache>> levelEntry
                    : sableContraptionCaches.entrySet()) {
                for (Map.Entry<String, SableContraptionCache> cacheEntry
                        : levelEntry.getValue().entrySet()) {
                    SableContraptionCache cached = cacheEntry.getValue();
                    if (cached.contraptionNbt() == null
                            || cached.contraptionNbt().isEmpty()) {
                        continue;
                    }

                    CompoundTag entry = new CompoundTag();
                    entry.putString("Dimension", cached.dimension().location().toString());
                    entry.putString("Key", cacheEntry.getKey());
                    entry.putString("ObjectId", cached.objectId());
                    entry.putUUID("SubLevel", cached.subLevelId());
                    if (cached.controllerPos() != null) {
                        entry.putLong("ControllerPos", cached.controllerPos().asLong());
                    }
                    entry.putDouble("PosX", cached.localPosition().x);
                    entry.putDouble("PosY", cached.localPosition().y);
                    entry.putDouble("PosZ", cached.localPosition().z);
                    entry.putFloat("RotX", cached.localRotation().x);
                    entry.putFloat("RotY", cached.localRotation().y);
                    entry.putFloat("RotZ", cached.localRotation().z);
                    entry.putFloat("RotW", cached.localRotation().w);
                    entry.put("Contraption", cached.contraptionNbt().copy());
                    entries.add(entry);
                }
            }

            CompoundTag root = new CompoundTag();
            root.putInt("Version", 2);
            root.put("Entries", entries);

            Path temporary = SABLE_CACHE_FILE.resolveSibling(
                    SABLE_CACHE_FILE.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(
                    temporary,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                NbtIo.writeCompressed(root, output);
            }
            Files.move(
                    temporary,
                    SABLE_CACHE_FILE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException error) {
            LOGGER.warn(
                    "Could not persist Sable child contraption cache to {}",
                    SABLE_CACHE_FILE, error);
        }
    }

    private void appendCachedSableContraptions(
            ServerLevel level,
            List<SceneObject> out,
            Set<String> liveIds) {
        Map<String, SableContraptionCache> cache = sableContraptionCaches.get(level);
        if (cache == null || cache.isEmpty()) return;

        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) return;

        for (Map.Entry<String, SableContraptionCache> entry : new ArrayList<>(cache.entrySet())) {
            if (liveIds.contains(entry.getKey())) continue;

            SableContraptionCache cached = entry.getValue();
            SubLevel subLevel = container.getSubLevel(cached.subLevelId());
            if (subLevel == null || subLevel.isRemoved()) {
                cache.remove(entry.getKey());
                markContraptionDeleted(level, cached.objectId());
                savePersistentSableCaches();
                continue;
            }

            // If the owning bearing is available and reports that it is no longer running,
            // this was a real disassembly rather than a distance-based entity unload.
            if (cached.controllerPos() != null) {
                BlockEntity controller = level.getBlockEntity(cached.controllerPos());
                if (controller instanceof MechanicalBearingBlockEntity bearing
                        && !bearing.isRunning()) {
                    cache.remove(entry.getKey());
                    markContraptionDeleted(level, cached.objectId());
                    savePersistentSableCaches();
                    continue;
                }
            }

            var pose = subLevel.logicalPose();
            Vec3 worldPosition = pose.transformPosition(cached.localPosition());
            var parentRotation = pose.orientation();
            Quaternionf worldRotation = new Quaternionf(
                    (float) parentRotation.x(), (float) parentRotation.y(),
                    (float) parentRotation.z(), (float) parentRotation.w())
                    .mul(new Quaternionf(cached.localRotation()));

            out.add(sceneObjectOf(
                    cached.objectId(),
                    cached.volume(),
                    cached.version(),
                    worldPosition,
                    worldRotation,
                    cached.dimension()));
        }
    }

    private static String sableContraptionKey(
            AbstractContraptionEntity entity,
            SubLevel subLevel) {
        BlockPos controller = controllerPosOf(entity);
        String child = controller == null
                ? entity.getUUID().toString()
                : Long.toUnsignedString(controller.asLong());
        return subLevel.getUniqueId() + "/" + child;
    }

    private static BlockPos controllerPosOf(AbstractContraptionEntity entity) {
        if (CONTROLLER_POS_FIELD == null || !(entity instanceof ControlledContraptionEntity)) {
            return null;
        }
        try {
            Object value = CONTROLLER_POS_FIELD.get(entity);
            return value instanceof BlockPos pos ? pos.immutable() : null;
        } catch (IllegalAccessException error) {
            return null;
        }
    }

    private static Field controllerPosField() {
        try {
            Field field = ControlledContraptionEntity.class.getDeclaredField("controllerPos");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException error) {
            LOGGER.warn("Could not access ControlledContraptionEntity.controllerPos; "
                    + "cached Sable child contraptions will only expire with their sublevel", error);
            return null;
        }
    }

    private record SableContraptionCache(
            String objectId,
            BlockVolume volume,
            long version,
            Vec3 localPosition,
            Quaternionf localRotation,
            UUID subLevelId,
            BlockPos controllerPos,
            ResourceKey<Level> dimension,
            CompoundTag contraptionNbt) {
    }

'''
s = s.replace(cache_anchor, cache_methods + cache_anchor, 1)

clear_needle = '''    public void clear() {
        carriageCaches.clear();
    }'''
if clear_needle not in s:
    raise SystemExit("ContraptionProvider clear() insertion point not found")
s = s.replace(
    clear_needle,
    '''    @Override
    public Collection<String> deletedObjectIds(ServerLevel level) {
        Set<String> deleted = deletedContraptionIds.get(level);
        if (deleted == null || deleted.isEmpty()) return List.of();

        List<String> result = List.copyOf(deleted);
        deleted.removeAll(result);
        return result;
    }

    private void markContraptionDeleted(ServerLevel level, String objectId) {
        if (objectId == null || objectId.isBlank()) return;
        deletedContraptionIds
                .computeIfAbsent(level, ignored -> ConcurrentHashMap.newKeySet())
                .add(objectId);
    }

    public void clear() {
        savePersistentSableCaches();
        carriageCaches.clear();
        sableContraptionCaches.clear();
        deletedContraptionIds.clear();
        sablePersistentLoaded.clear();
        terrainFootprints.clear();
    }''',
    1,
)

# A Create assembly removes its blocks from the world immediately, but BlueMap may still
# have those blocks baked into an old terrain tile. Track the moving object's footprint so
# we can invalidate the source tile once on assembly and the destination tile once on
# disassembly. This is deliberately transition-based: movement itself does not modify world
# blocks, so rerendering under a moving train every publish would just waste the map queue.
if 'import dev.duzo.bluemap3d.api.BlueMap3D;' not in s:
    s = s.replace(
        'import dev.duzo.bluemap3d.api.SceneObjectProvider;',
        'import dev.duzo.bluemap3d.api.SceneObjectProvider;\n'
        'import dev.duzo.bluemap3d.api.BlueMap3D;',
        1,
    )

terrain_field_needle = (
    '    private final Set<ResourceKey<Level>> sablePersistentLoaded = '
    'ConcurrentHashMap.newKeySet();'
)
if terrain_field_needle not in s:
    raise SystemExit("ContraptionProvider terrain-footprint field insertion point not found")
s = s.replace(
    terrain_field_needle,
    terrain_field_needle
        + '\n\n    /** Last world footprint for ordinary Create contraptions currently reported live. */'
        + '\n    private final Map<ServerLevel, Map<String, Footprint>> terrainFootprints = new ConcurrentHashMap<>();'
        + '\n\n    /** Inclusive X/Z footprint of a contraption in the real world. */'
        + '\n    private record Footprint(int minX, int minZ, int maxX, int maxZ, int y) {}'
        + '\n\n    private static final int MAX_TERRAIN_REFRESH_SAMPLES = 64;',
    1,
)

objects_live_needle = '''        Set<String> liveSableContraptions = new HashSet<>();
        for (AbstractContraptionEntity entity : entities) {
            SubLevel liveSubLevel = Sable.HELPER.getContaining(entity);
            if (liveSubLevel != null) {
                liveSableContraptions.add(sableContraptionKey(entity, liveSubLevel));
            }

            SceneObject object = toSceneObject(level, entity, maxBlocks);
            if (object != null) {
                out.add(object);
            }
        }

        appendCachedSableContraptions(level, out, liveSableContraptions);
        out.addAll(trainCarriages(level, maxBlocks));'''
if objects_live_needle not in s:
    raise SystemExit("ContraptionProvider terrain object-loop insertion point not found")
s = s.replace(
    objects_live_needle,
    '''        Set<String> liveSableContraptions = new HashSet<>();
        Set<String> terrainContraptions = new HashSet<>();
        for (AbstractContraptionEntity entity : entities) {
            SubLevel liveSubLevel = Sable.HELPER.getContaining(entity);
            if (liveSubLevel != null) {
                liveSableContraptions.add(sableContraptionKey(entity, liveSubLevel));
            }

            SceneObject object = toSceneObject(level, entity, maxBlocks);
            if (object != null) {
                out.add(object);
                // Sable child contraptions live in a hidden plot, not ordinary world
                // chunks. Refreshing their projected world footprint would erase real
                // terrain, so only track ordinary Create assemblies here.
                if (liveSubLevel == null) {
                    terrainContraptions.add(object.id());
                    trackTerrainFootprint(
                            level,
                            object.id(),
                            assemblyFootprintOf(entity.getContraption()),
                            footprintOf(object));
                }
            }
        }

        appendCachedSableContraptions(level, out, liveSableContraptions);
        out.addAll(trainCarriages(level, maxBlocks, terrainContraptions));
        sweepTerrainFootprints(level, terrainContraptions);''',
    1,
)

train_signature = '    private Collection<SceneObject> trainCarriages(ServerLevel level, int maxBlocks) {'
if train_signature not in s:
    raise SystemExit("ContraptionProvider trainCarriages signature not found")
s = s.replace(
    train_signature,
    '    private Collection<SceneObject> trainCarriages(ServerLevel level, int maxBlocks, '
    'Set<String> terrainContraptions) {',
    1,
)

train_add_needle = '''                String objectId = dim.getNamespace() + "/" + dim.getPath() + "/" + train.id + "/" + index;
                out.add(sceneObjectOf(objectId, entry.volume, entry.version, position, rotation, level.dimension()));'''
if train_add_needle not in s:
    raise SystemExit("ContraptionProvider train footprint insertion point not found")
s = s.replace(
    train_add_needle,
    '''                String objectId = dim.getNamespace() + "/" + dim.getPath() + "/" + train.id + "/" + index;
                SceneObject object = sceneObjectOf(
                        objectId, entry.volume, entry.version, position, rotation, level.dimension());
                out.add(object);
                terrainContraptions.add(objectId);
                trackTerrainFootprint(
                        level,
                        objectId,
                        entry.assemblyFootprint,
                        footprintOf(object));''',
    1,
)

live_geometry_needle = '''                entry.volume = geometry.volume();
                entry.version = geometry.version();
                entry.seeded = true;'''
if live_geometry_needle not in s:
    raise SystemExit("ContraptionProvider live carriage assembly footprint insertion point not found")
s = s.replace(
    live_geometry_needle,
    '''                entry.volume = geometry.volume();
                entry.version = geometry.version();
                entry.assemblyFootprint = assemblyFootprintOf(live.getContraption());
                entry.seeded = true;''',
    1,
)

cold_geometry_needle = '''        if (geometry != null) {
            entry.volume = geometry.volume();
            entry.version = geometry.version();
        }'''
if cold_geometry_needle not in s:
    raise SystemExit("ContraptionProvider cold carriage assembly footprint insertion point not found")
s = s.replace(
    cold_geometry_needle,
    '''        if (geometry != null) {
            entry.volume = geometry.volume();
            entry.version = geometry.version();
            entry.assemblyFootprint = assemblyFootprintOf(contraption);
        }''',
    1,
)

cache_field_needle = '''    private static final class CarriageCache {
        boolean seeded;
        BlockVolume volume;
        long version;
        float initialYawDegrees;
    }'''
if cache_field_needle not in s:
    raise SystemExit("ContraptionProvider CarriageCache insertion point not found")
s = s.replace(
    cache_field_needle,
    '''    private static final class CarriageCache {
        boolean seeded;
        BlockVolume volume;
        long version;
        float initialYawDegrees;
        Footprint assemblyFootprint;
    }''',
    1,
)

terrain_methods_anchor = '''    /** Builds the {@link SceneObject} both contraption paths return, differing only in id. */'''
if terrain_methods_anchor not in s:
    raise SystemExit("ContraptionProvider terrain helper insertion point not found")
terrain_methods = r'''    private void trackTerrainFootprint(
            ServerLevel level,
            String objectId,
            Footprint assemblyFootprint,
            Footprint currentFootprint) {
        if (currentFootprint == null) return;

        Map<String, Footprint> footprints =
                terrainFootprints.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());
        Footprint previous = footprints.put(objectId, currentFootprint);
        if (previous == null) {
            // First sighting means "assembled" for the renderer. The blocks that need
            // removing from BlueMap are where Create captured them, not wherever the
            // contraption has already moved by the time the publish interval fires.
            refreshFootprint(level, assemblyFootprint != null ? assemblyFootprint : currentFootprint);
        }
    }

    private void sweepTerrainFootprints(ServerLevel level, Set<String> present) {
        Map<String, Footprint> footprints = terrainFootprints.get(level);
        if (footprints == null || footprints.isEmpty()) return;

        for (Map.Entry<String, Footprint> entry : Set.copyOf(footprints.entrySet())) {
            if (present.contains(entry.getKey())) continue;

            footprints.remove(entry.getKey());
            // Create puts the blocks back at the contraption's last pose when it
            // disassembles. Re-render the last footprint so the terrain copy returns.
            refreshFootprint(level, entry.getValue());
        }
        if (footprints.isEmpty()) terrainFootprints.remove(level);
    }

    /**
     * Exact source footprint from Create's captured block map.
     *
     * Contraption.addBlock stores each key as globalPos - anchor, so adding anchor back
     * reconstructs the positions that were removed from the chunk at assembly time.
     */
    private static Footprint assemblyFootprintOf(Contraption contraption) {
        if (contraption == null || contraption.anchor == null
                || contraption.getBlocks() == null || contraption.getBlocks().isEmpty()) {
            return null;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos local : contraption.getBlocks().keySet()) {
            BlockPos world = contraption.anchor.offset(local);
            minX = Math.min(minX, world.getX());
            minY = Math.min(minY, world.getY());
            minZ = Math.min(minZ, world.getZ());
            maxX = Math.max(maxX, world.getX());
            maxZ = Math.max(maxZ, world.getZ());
        }
        return minX == Integer.MAX_VALUE
                ? null
                : new Footprint(minX, minZ, maxX, maxZ, minY);
    }

    /** World-space AABB of the currently moving volume, used when it disappears. */
    private static Footprint footprintOf(SceneObject object) {
        if (object == null || object.geometry() == null) return null;

        BlockVolume volume = object.geometry();
        BlockPos min = volume.min();
        BlockPos max = volume.max();
        Vec3 pivot = volume.pivot();
        Vec3 position = object.position();
        Quaternionf rotation = new Quaternionf(object.rotation());

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        float[] xs = {(float) min.getX(), (float) max.getX() + 1f};
        float[] ys = {(float) min.getY(), (float) max.getY() + 1f};
        float[] zs = {(float) min.getZ(), (float) max.getZ() + 1f};

        for (float x : xs) {
            for (float y : ys) {
                for (float z : zs) {
                    Vector3f offset = new Vector3f(
                            x - (float) pivot.x,
                            y - (float) pivot.y,
                            z - (float) pivot.z);
                    rotation.transform(offset);
                    double worldX = position.x + offset.x;
                    double worldY = position.y + offset.y;
                    double worldZ = position.z + offset.z;
                    minX = Math.min(minX, worldX);
                    minY = Math.min(minY, worldY);
                    minZ = Math.min(minZ, worldZ);
                    maxX = Math.max(maxX, worldX);
                    maxZ = Math.max(maxZ, worldZ);
                }
            }
        }

        return new Footprint(
                (int) Math.floor(minX),
                (int) Math.floor(minZ),
                (int) Math.ceil(maxX),
                (int) Math.ceil(maxZ),
                (int) Math.floor(minY));
    }

    private static void refreshFootprint(ServerLevel level, Footprint footprint) {
        if (footprint == null) return;

        // Sample at half BlueMap's usual 32-block hires tile width. Core coalesces
        // repeated positions down to tile coordinates before scheduling renders.
        int step = 16;
        int columns = Math.max(1, (footprint.maxX() - footprint.minX()) / step + 1);
        int rows = Math.max(1, (footprint.maxZ() - footprint.minZ()) / step + 1);
        if ((long) columns * rows > MAX_TERRAIN_REFRESH_SAMPLES) {
            BlueMap3D.refreshArea(
                    level, new BlockPos(footprint.minX(), footprint.y(), footprint.minZ()));
            BlueMap3D.refreshArea(
                    level, new BlockPos(footprint.maxX(), footprint.y(), footprint.maxZ()));
            return;
        }

        for (int x = footprint.minX(); x <= footprint.maxX(); x += step) {
            for (int z = footprint.minZ(); z <= footprint.maxZ(); z += step) {
                BlueMap3D.refreshArea(level, new BlockPos(x, footprint.y(), z));
            }
        }
        BlueMap3D.refreshArea(
                level, new BlockPos(footprint.maxX(), footprint.y(), footprint.maxZ()));
    }

'''
s = s.replace(terrain_methods_anchor, terrain_methods + terrain_methods_anchor, 1)

p.write_text(s)

replace(
    "core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js",
    'var BUILD = "core-history-15-special-models";',
    'var BUILD = "core-history-43-rope-removal";',
)
replace("gradle.properties", "version=1.0.9", f"version={VERSION}")

# Release metadata remains dynamic; structural BlueMap3DMod wiring lives in 0030.
p = Path("core/src/main/java/dev/duzo/bluemap3d/BlueMap3DMod.java")
s = p.read_text()
needle = 'LOGGER.info("BlueMap3D loaded. Waiting for BlueMap and at least one addon.");'
if needle not in s:
    raise SystemExit("BlueMap3D startup marker insertion point not found")
s = s.replace(
    needle,
    needle
        + f'\n        LOGGER.info("BlueMap3D FPB patches {VERSION} active; BlueMap target is {BLUEMAP_TARGET}.");',
    1,
)
p.write_text(s)

# Stream optional per-object scale alongside position/rotation. This is used by flexible
# rope/spring segments so their length can change without rebuilding their mesh.
p = Path("core/src/main/java/dev/duzo/bluemap3d/runtime/SceneObjectTracker.java")
ts = p.read_text()

# A changed geometryVersion means the previous mesh has stale topology. Keeping it in the
# feed while the replacement bakes creates a deterministic duplicate window when a Sable
# child contraption appears before the parent hull rebake finishes. Resolve an already
# published target-version mesh first; otherwise hide the object until that version is ready.
stale_needle = '''            if (current == null) {
                String existing = publisher.existingMeshUrl(providerId, object.id(), version);
                if (existing != null) {
                    current = new Ready(version, existing);
                    ready.put(key, current);
                    // Keep mesh-archive consumers in sync even though no bake happened.
                    BlueMap3D.meshPublished(providerId, object.id(), version, existing);
                }
            }

            if ((current == null || current.version() != version)
                    && object.canBakeGeometry()) {
                requestBake(providerId, object, key, version);
            }
            if (current != null) {
'''
if stale_needle not in ts:
    raise SystemExit("SceneObjectTracker stale topology insertion point not found")
ts = ts.replace(
    stale_needle,
    '''            if (current == null || current.version() != version) {
                String existing = publisher.existingMeshUrl(providerId, object.id(), version);
                if (existing != null) {
                    current = new Ready(version, existing);
                    ready.put(key, current);
                    BlueMap3D.meshPublished(providerId, object.id(), version, existing);
                }
            }

            if ((current == null || current.version() != version)
                    && object.canBakeGeometry()) {
                requestBake(providerId, object, key, version);
            }

            // Never publish known-stale topology. The browser drops the old object now
            // and it reappears only once the requested geometry version is actually ready.
            if (current != null && current.version() != version) {
                current = null;
            }
            if (current != null) {
''',
    1,
)

row_needle = '''                rows.add(new Row(providerId, object.id(), object.label(),
                        object.dimension().location().toString(),
                        current.meshUrl(), object.position(), object.rotation()));
'''
if row_needle not in ts:
    raise SystemExit("SceneObjectTracker live-scale row insertion point not found")
ts = ts.replace(
    row_needle,
    '''                rows.add(new Row(providerId, object.id(), object.label(),
                        object.dimension().location().toString(),
                        current.meshUrl(), object.position(), object.rotation(), object.scale()));
''',
    1,
)

json_needle = '''                json.name("rot").beginArray()
                        .value(round(rotation.x))
                        .value(round(rotation.y))
                        .value(round(rotation.z))
                        .value(round(rotation.w))
                        .endArray();
                json.endObject();
'''
if json_needle not in ts:
    raise SystemExit("SceneObjectTracker live-scale JSON insertion point not found")
json_replacement = '''                json.name("rot").beginArray()
                        .value(round(rotation.x))
                        .value(round(rotation.y))
                        .value(round(rotation.z))
                        .value(round(rotation.w))
                        .endArray();
                org.joml.Vector3f scale = row.scale();
                json.name("scale").beginArray()
                        .value(round(scale.x))
                        .value(round(scale.y))
                        .value(round(scale.z))
                        .endArray();
                json.endObject();
'''
ts = ts.replace(json_needle, json_replacement, 1)

record_needle = '''    private record Row(String provider, String id, String label, String dimension,
                       String meshUrl, Vec3 position, Quaternionf rotation) {
    }
'''
if record_needle not in ts:
    raise SystemExit("SceneObjectTracker live-scale record insertion point not found")
ts = ts.replace(
    record_needle,
    '''    private record Row(String provider, String id, String label, String dimension,
                       String meshUrl, Vec3 position, Quaternionf rotation,
                       org.joml.Vector3f scale) {
    }
''',
    1,
)
p.write_text(ts)

# Browser support for KIND_LOOP. Layout stays fixed-width; v6 only adds the new semantic.
p = Path("core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js")
s = p.read_text()
scale_sample_needle = '''            var sample = {
                t: now,
                pos: row.pos,
                rot: row.rot
            };
'''
if scale_sample_needle not in s:
    raise SystemExit("web live-scale sample insertion point not found")
s = s.replace(
    scale_sample_needle,
    '''            var sample = {
                t: now,
                pos: row.pos,
                rot: row.rot,
                scale: row.scale || [1, 1, 1]
            };
''',
    1,
)

scale_transform_needle = '''        mesh.quaternion
            .set(from.rot[0], from.rot[1], from.rot[2], from.rot[3])
            .slerp(
                _scratch.set(to.rot[0], to.rot[1], to.rot[2], to.rot[3]),
                alpha
            );

        if (entry.nodeGroups) {
'''
if scale_transform_needle not in s:
    raise SystemExit("web live-scale transform insertion point not found")
s = s.replace(
    scale_transform_needle,
    '''        mesh.quaternion
            .set(from.rot[0], from.rot[1], from.rot[2], from.rot[3])
            .slerp(
                _scratch.set(to.rot[0], to.rot[1], to.rot[2], to.rot[3]),
                alpha
            );

        var fromScale = from.scale || [1, 1, 1];
        var toScale = to.scale || [1, 1, 1];
        mesh.scale.set(
            fromScale[0] + (toScale[0] - fromScale[0]) * alpha,
            fromScale[1] + (toScale[1] - fromScale[1]) * alpha,
            fromScale[2] + (toScale[2] - fromScale[2]) * alpha
        );

        if (entry.nodeGroups) {
''',
    1,
)

kind_needle = '    var KIND_RATE = 3;'
if kind_needle not in s:
    raise SystemExit("web loop kind insertion point not found")
s = s.replace(
    kind_needle,
    kind_needle + '\n    var KIND_LOOP = 4;\n    var KIND_UV_SCROLL = 5;',
    1,
)

s = s.replace(
    'if (version < 1 || version > 5) {',
    'if (version < 1 || version > 7) {',
    1,
)

uv_attr_needle = '''            nodeGeometry.setAttribute("position", position);
            nodeGeometry.setAttribute("uv", geometry.attributes.uv);
            nodeGeometry.setAttribute("color", geometry.attributes.color);
'''
if uv_attr_needle not in s:
    raise SystemExit("web UV node attribute insertion point not found")
uv_attr_replacement = '''            nodeGeometry.setAttribute("position", position);
            if (node.kind === KIND_UV_SCROLL) {
                var sourceUv = geometry.attributes.uv;
                var uvCopy = new Float32Array(sourceUv.array);
                nodeGeometry.setAttribute("uv", new THREE.BufferAttribute(uvCopy, 2));

                var seenUv = Object.create(null);
                var uvVertices = [];
                var uvBase = [];
                var minU = Infinity, maxU = -Infinity;
                var minV = Infinity, maxV = -Infinity;
                var uvEnd = node.indexStart + node.indexCount;
                for (var uvIndex = node.indexStart; uvIndex < uvEnd; uvIndex++) {
                    var vertexIndex = index.array[uvIndex];
                    if (seenUv[vertexIndex] === true) {
                        continue;
                    }
                    seenUv[vertexIndex] = true;
                    var baseU = sourceUv.array[vertexIndex * 2];
                    var baseV = sourceUv.array[vertexIndex * 2 + 1];
                    uvVertices.push(vertexIndex);
                    uvBase.push(baseU, baseV);
                    minU = Math.min(minU, baseU);
                    maxU = Math.max(maxU, baseU);
                    minV = Math.min(minV, baseV);
                    maxV = Math.max(maxV, baseV);
                }
                node.uvVertices = uvVertices;
                node.uvBase = uvBase;
                node.uvSpanU = isFinite(minU) ? maxU - minU : 0;
                node.uvSpanV = isFinite(minV) ? maxV - minV : 0;
            } else {
                nodeGeometry.setAttribute("uv", geometry.attributes.uv);
            }
            nodeGeometry.setAttribute("color", geometry.attributes.color);
'''
s = s.replace(uv_attr_needle, uv_attr_replacement, 1)

material_needle = '    var materialCache = Object.create(null);'
if material_needle not in s:
    raise SystemExit("web UV helper insertion point not found")
uv_helper = '''    function scrollNodeUvs(node, geometry, phase) {
        if (!node.uvVertices || !geometry.attributes.uv) {
            return;
        }

        phase = phase - Math.floor(phase);
        var du = node.axis.x * node.uvSpanU * phase;
        var dv = node.axis.y * node.uvSpanV * phase;
        var array = geometry.attributes.uv.array;

        for (var i = 0; i < node.uvVertices.length; i++) {
            var vertexIndex = node.uvVertices[i];
            array[vertexIndex * 2] = node.uvBase[i * 2] + du;
            array[vertexIndex * 2 + 1] = node.uvBase[i * 2 + 1] + dv;
        }

        geometry.attributes.uv.needsUpdate = true;
    }

'''
s = s.replace(material_needle, uv_helper + material_needle, 1)

live_rate = '''                } else if (node.kind === KIND_RATE) {
                    /* Driven by wall-clock time, not by "value" (the odometer) - a
'''
if live_rate not in s:
    raise SystemExit("web live loop insertion point not found")
live_loop = '''                } else if (node.kind === KIND_UV_SCROLL) {
                    group.position.set(0, 0, 0);
                    group.quaternion.set(0, 0, 0, 1);
                    var uvMesh = group.children[0];
                    if (uvMesh && uvMesh.geometry) {
                        scrollNodeUvs(
                            node,
                            uvMesh.geometry,
                            node.period + node.rate * performance.now() / 1000);
                    }
                } else if (node.kind === KIND_LOOP) {
                    var loopNow = performance.now();
                    var loopLast = entry.rateLastTime[i];
                    var loopDt = loopLast === null ? 0 : (loopNow - loopLast) / 1000;
                    if (loopDt < 0 || loopDt > MAX_RATE_DT_SECONDS) {
                        loopDt = 0;
                    }
                    entry.rateLastTime[i] = loopNow;
                    entry.rateAngles[i] += node.rate * loopDt;
                    var loopPhase = node.period > 0
                        ? entry.rateAngles[i] % node.period : 0;
                    group.position.set(
                        node.axis.x * loopPhase,
                        node.axis.y * loopPhase,
                        node.axis.z * loopPhase);
                    group.quaternion.set(0, 0, 0, 1);
                } else if (node.kind === KIND_RATE) {
                    /* Driven by wall-clock time, not by "value" (the odometer) - a
'''
s = s.replace(live_rate, live_loop, 1)

replay_rate = '''            } else if (node.kind === KIND_RATE) {
                var angle = node.rate * timeSeconds;
'''
if replay_rate in s:
    replay_loop = '''            } else if (node.kind === KIND_UV_SCROLL) {
                group.position.set(0, 0, 0);
                group.quaternion.set(0, 0, 0, 1);
                var uvMesh = group.children[0];
                if (uvMesh && uvMesh.geometry) {
                    scrollNodeUvs(node, uvMesh.geometry, node.period + node.rate * timeSeconds);
                }
            } else if (node.kind === KIND_LOOP) {
                var loopPhase = node.period > 0
                    ? (node.rate * timeSeconds) % node.period : 0;
                group.position.set(
                    node.axis.x * loopPhase,
                    node.axis.y * loopPhase,
                    node.axis.z * loopPhase);
                group.quaternion.set(0, 0, 0, 1);
            } else if (node.kind === KIND_RATE) {
                var angle = node.rate * timeSeconds;
'''
    s = s.replace(replay_rate, replay_loop, 1)

s = s.replace(
    'if (nodes[i].kind === KIND_RATE) {',
    'if (nodes[i].kind === KIND_RATE || nodes[i].kind === KIND_LOOP) {',
    1,
)

# The loop may translate by almost one full period beyond its baked pose. Inflate
# the animated child sphere so frustum culling never drops an endpoint mid-cycle.
sphere_needle = '''            nodeGeometry.boundingSphere = new THREE.Sphere(node.pivot.clone(), Math.sqrt(maxDistSq));
'''
if sphere_needle not in s:
    raise SystemExit("web loop bounding sphere insertion point not found")
sphere_replacement = '''            var nodeRadius = Math.sqrt(maxDistSq);
            if (node.kind === KIND_LOOP && node.period > 0) {
                nodeRadius += node.period;
            }
            nodeGeometry.boundingSphere = new THREE.Sphere(node.pivot.clone(), nodeRadius);
'''
s = s.replace(sphere_needle, sphere_replacement, 1)
p.write_text(s)