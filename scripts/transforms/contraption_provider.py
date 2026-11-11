from pathlib import Path


TARGET = Path(
    "addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java"
)


def _replace_file(old: str, new: str) -> None:
    source = TARGET.read_text()
    if old not in source:
        raise SystemExit(f"expected text not found in {TARGET}: {old!r}")
    TARGET.write_text(source.replace(old, new))


def apply() -> None:
    """Apply the FPB Create/Sable integration to upstream ContraptionProvider."""
    _replace_file(
        "GEOMETRY_REVISION + 15",
        "GEOMETRY_REVISION + 33",
    )

    # ShipProvider's old geometry version is a cheap heuristic (bounds + mass + section
    # serialized sizes) and can miss real block removal/re-addition. Replace the complete
    # method after the base patch with an exact structural hash cached behind Sable's
    # authoritative block-change signal.
    # Material wrapper namespaces are now selected explicitly in base patch 0015.
    # Do not replace their NBT capture condition with a broad namespace filter.

    source = TARGET.read_text()
    source = _apply_sable_integration(source)
    source = _apply_terrain_invalidation(source)
    source = _apply_train_snapshot_cleanup(source)
    TARGET.write_text(source)



def _apply_train_snapshot_cleanup(s: str) -> str:
    """Prune only disbanded or shortened trains, never unloaded carriage entities."""
    needle = """    @Override
    public Collection<String> deletedObjectIds(ServerLevel level) {
        return ContraptionDeletionTracker.drain(level);
    }"""
    if s.count(needle) != 1:
        raise SystemExit("ContraptionProvider train persistence cleanup anchor not found")
    method = r'''    /**
     * Train carriage entity DISCARDED events are not proof of deletion: Create destroys
     * those entities whenever their chunks stop ticking, but the train continues to
     * exist in Create.RAILWAYS.trains. Conversely, a disassembled train no longer has
     * a registry entry and its previously persisted scene meshes must not remain as
     * phantom locomotives after BlueMap's terrain tiles are refreshed.
     */
    @Override
    public boolean isDefinitelyDeleted(ServerLevel level, String objectId) {
        if (level.getServer() == null || level.getServer().getTickCount() < 400
                || Create.RAILWAYS == null || objectId == null) {
            // Give Create time to restore its saved train registry on startup.
            return false;
        }

        ResourceLocation dimension = level.dimension().location();
        String prefix = dimension.getNamespace() + "/" + dimension.getPath() + "/";
        if (!objectId.startsWith(prefix)) return false;

        String suffix = objectId.substring(prefix.length());
        int slash = suffix.indexOf('/');
        if (slash <= 0 || suffix.indexOf('/', slash + 1) >= 0) {
            // Non-train contraption ids have no carriage-index suffix.
            return false;
        }

        try {
            UUID trainId = UUID.fromString(suffix.substring(0, slash));
            int carriageIndex = Integer.parseInt(suffix.substring(slash + 1));
            if (carriageIndex < 0) return false;

            Train train = Create.RAILWAYS.trains.get(trainId);
            return train == null || carriageIndex >= train.carriages.size();
        } catch (IllegalArgumentException invalidId) {
            return false;
        }
    }

'''
    return s.replace(needle, method + needle, 1)

def _apply_sable_integration(s: str) -> str:
    """Add Sable projection, child persistence, and positive deletion semantics."""
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
                LOGGER.debug(
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
        return ContraptionDeletionTracker.drain(level);
    }

    private void markContraptionDeleted(ServerLevel level, String objectId) {
        ContraptionDeletionTracker.record(level, objectId);
    }

    public void clear() {
        savePersistentSableCaches();
        carriageCaches.clear();
        sableContraptionCaches.clear();
        ContraptionDeletionTracker.clear();
        sablePersistentLoaded.clear();
        terrainFootprints.clear();
        terrainPersistTicks.clear();
    }''',
        1,
    )
    return s


def _apply_terrain_invalidation(s: str) -> str:
    """Track Create assembly footprints and refresh BlueMap terrain on transitions."""
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
            + '\n\n    /** Last game tick whose changed Create terrain was persisted for BlueMap. */'
            + '\n    private final Map<ServerLevel, Long> terrainPersistTicks = new ConcurrentHashMap<>();'

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
    private void persistTerrainOnce(ServerLevel level) {
        long tick = level.getGameTime();
        Long previous = terrainPersistTicks.put(level, tick);
        if (previous != null && previous.longValue() == tick) return;

        try {
            // BlueMap's terrain renderer reads MCA region files, not the live ServerLevel.
            // Create has already removed/returned the blocks in memory at this point, so
            // persist once for this level/tick before asking BlueMap to re-render tiles.
            level.save(null, true, false);
        } catch (RuntimeException error) {
            terrainPersistTicks.remove(level, tick);
            LOGGER.warn(
                    "Could not persist Create terrain changes before BlueMap refresh in {}",
                    level.dimension().location(), error);
        }
    }

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

    private void refreshFootprint(ServerLevel level, Footprint footprint) {
        if (footprint == null) return;

        persistTerrainOnce(level);

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
    return s
