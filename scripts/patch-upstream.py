from pathlib import Path

def replace(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"expected text not found in {path}: {old!r}")
    p.write_text(s.replace(old, new))

p = Path("settings.gradle")
s = p.read_text()
needle = 'include("addon-create")'
if needle not in s:
    raise SystemExit("settings.gradle addon insertion point not found")
s = s.replace(
    needle,
    needle + '\ninclude("addon-compat")',
    1,
)
p.write_text(s)

# Create contraptions can exist inside Sable's hidden plot space (Aeronautics propellers).
# Compile against Sable so the Create provider can project those child contraptions through
# their owning ship's pose before publishing them to BlueMap3D.
p = Path("addon-create/build.gradle")
s = p.read_text()
needle = '    compileOnly "net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1"\n'
if needle not in s:
    raise SystemExit("addon-create Sable dependency insertion point not found")
s = s.replace(
    needle,
    needle
        + '\n    compileOnly("dev.ryanhcode.sable:sable-neoforge-${minecraft_version}:${sable_version}") { transitive = false }\n'
        + '    compileOnly "dev.ryanhcode.sable-companion:sable-companion-common-${minecraft_version}:${sable_companion_version}"\n',
    1,
)
p.write_text(s)

replace(
    "addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java",
    "GEOMETRY_REVISION + 15",
    "GEOMETRY_REVISION + 33",
)
replace(
    "addon-sable/src/main/java/dev/duzo/bluemap3d/sable/ShipProvider.java",
    "mix(mix(hash, sections), 15L)",
    "mix(mix(hash, sections), 33L)",
)
replace(
    "addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java",
    'if (!"copycats".equals(namespace)) {',
    'if (!CompatRegistry.get().preserveMovingNamespace(namespace)) {',
)

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

        if (previous == null || previous.version() != next.version()) {
            savePersistentSableCaches();
        }
    }

    private void loadPersistentSableContraptions(ServerLevel level, int maxBlocks) {
        if (!sablePersistentLoaded.add(level.dimension())) return;
        if (!Files.isRegularFile(SABLE_CACHE_FILE)) return;

        try (InputStream input = Files.newInputStream(SABLE_CACHE_FILE)) {
            CompoundTag root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
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
                    ListTag previousEntries =
                            previousRoot.getList("Entries", Tag.TAG_COMPOUND);
                    for (int i = 0; i < previousEntries.size(); i++) {
                        CompoundTag previous = previousEntries.getCompound(i);
                        if (!replacedDimensions.contains(previous.getString("Dimension"))) {
                            entries.add(previous.copy());
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
            root.putInt("Version", 1);
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
    '''    public void clear() {
        savePersistentSableCaches();
        carriageCaches.clear();
        sableContraptionCaches.clear();
        sablePersistentLoaded.clear();
    }''',
    1,
)
p.write_text(s)

# Register the live chain-conveyor overlay provider beside the existing bearing provider.
p = Path("addon-create/src/main/java/dev/duzo/bluemap3d/create/CreateAddon.java")
s = p.read_text()
field_needle = '    private final BearingProvider bearings = new BearingProvider(chunks);'
if field_needle not in s:
    raise SystemExit("CreateAddon chain provider field insertion point not found")
s = s.replace(
    field_needle,
    field_needle
        + '\n    private final ChainConveyorProvider chainConveyors = new ChainConveyorProvider(chunks);'
        + '\n    private final BeltProvider belts = new BeltProvider(chunks);',
    1,
)
register_needle = '        BlueMap3D.register(bearings);'
if register_needle not in s:
    raise SystemExit("CreateAddon chain provider registration point not found")
s = s.replace(
    register_needle,
    register_needle
        + '\n        BlueMap3D.register(chainConveyors);'
        + '\n        BlueMap3D.register(belts);',
    1,
)
clear_needle = '        bearings.clear();'
if clear_needle not in s:
    raise SystemExit("CreateAddon chain provider clear point not found")
s = s.replace(
    clear_needle,
    clear_needle
        + '\n        chainConveyors.clear();'
        + '\n        belts.clear();',
    1,
)
p.write_text(s)

# Translate ModelAttachment.Loop into the existing fixed-size BM3D node trailer.
p = Path("core/src/main/java/dev/duzo/bluemap3d/bake/VolumeMesher.java")
s = p.read_text()
loop_needle = '''            case ModelAttachment.Rate rate -> new BakedMesh.Node(
                    BakedMesh.KIND_RATE, indexStart, indexCount,
                    pivotFor(rate.pivot(), matrix, attachment, volumePivot),
                    axisFor(rate.axis(), matrix),
                    // No radius or period: a constant rate is not a length, so the
                    // transform's scale has nothing to act on.
                    0f, 0f, rate.radiansPerSecond());
'''
if loop_needle not in s:
    raise SystemExit("VolumeMesher loop-motion insertion point not found")
loop_replacement = '''            case ModelAttachment.Rate rate -> new BakedMesh.Node(
                    BakedMesh.KIND_RATE, indexStart, indexCount,
                    pivotFor(rate.pivot(), matrix, attachment, volumePivot),
                    axisFor(rate.axis(), matrix),
                    // No radius or period: a constant rate is not a length, so the
                    // transform's scale has nothing to act on.
                    0f, 0f, rate.radiansPerSecond());
            case ModelAttachment.Loop loop -> new BakedMesh.Node(
                    BakedMesh.KIND_LOOP, indexStart, indexCount,
                    new float[]{0f, 0f, 0f},
                    axisFor(loop.axis(), matrix),
                    0f, loop.period() / 16f * s, loop.blocksPerSecond());
            case ModelAttachment.UvScroll scroll -> new BakedMesh.Node(
                    BakedMesh.KIND_UV_SCROLL, indexStart, indexCount,
                    new float[]{0f, 0f, 0f},
                    new float[]{scroll.axis().x(), scroll.axis().y(), 0f},
                    0f, scroll.phase(), scroll.cyclesPerSecond());
'''
s = s.replace(loop_needle, loop_replacement, 1)
p.write_text(s)

replace(
    "core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js",
    'var BUILD = "core-history-15-special-models";',
    'var BUILD = "core-history-33-config-compat";',
)
replace("gradle.properties", "version=1.0.9", "version=1.1.1")

p = Path("core/src/main/java/dev/duzo/bluemap3d/BlueMap3DMod.java")
s = p.read_text()

import_needle = 'import dev.duzo.bluemap3d.bake.BitsNBobsStrutSource;'
if import_needle not in s:
    raise SystemExit("BlueMap3DMod procedural import insertion point not found")
s = s.replace(
    import_needle,
    import_needle
        + '\nimport dev.duzo.bluemap3d.bake.ProceduralBlockSource;'
        + '\nimport dev.duzo.bluemap3d.bake.ConfiguredRuleSource;'
        + '\nimport dev.duzo.bluemap3d.bake.TrafficCraftSignSource;'
        + '\nimport dev.duzo.bluemap3d.bake.SymmetricSailSource;'
        + '\nimport dev.duzo.bluemap3d.bake.ChainConveyorSource;'
        + '\nimport dev.duzo.bluemap3d.compat.CompatRegistry;',
    1,
)

source_needle = '                sources.add(new BitsNBobsStrutSource(packs));'
if source_needle not in s:
    raise SystemExit("BlueMap3DMod procedural source insertion point not found")
s = s.replace(
    source_needle,
    source_needle
        + '\n                sources.add(new ProceduralBlockSource(packs));'
        + '\n                sources.add(new SymmetricSailSource(packs));'
        + '\n                sources.add(new ChainConveyorSource(packs));'
        + '\n                sources.add(new TrafficCraftSignSource(packs));'
        + '\n                sources.add(new ConfiguredRuleSource(packs));',
    1,
)

needle = 'LOGGER.info("BlueMap3D loaded. Waiting for BlueMap and at least one addon.");'
if needle not in s:
    raise SystemExit("BlueMap3D startup marker insertion point not found")
s = s.replace(
    needle,
    'CompatRegistry.get();\n\n        ' + needle
        + '\n        LOGGER.info("BlueMap3D FPB patches 1.1.1 active; BlueMap target is 5.7.");',
    1,
)
p.write_text(s)

# Browser support for KIND_LOOP. Layout stays fixed-width; v6 only adds the new semantic.
p = Path("core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js")
s = p.read_text()
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