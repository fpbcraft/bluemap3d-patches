from __future__ import annotations

import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class PatchArchitectureTest(unittest.TestCase):
    def test_patch_series_is_ordered_and_well_named(self) -> None:
        patches = sorted((ROOT / "patches").glob("*.patch"))
        self.assertGreaterEqual(len(patches), 2)
        self.assertEqual(
            [path.name for path in patches],
            sorted(path.name for path in patches),
        )
        for path in patches:
            self.assertRegex(path.name, r"^\d{4}-[a-z0-9-]+\.patch$")

    def test_patch_hunk_line_counts_match_headers(self) -> None:
        header_re = re.compile(
            r"^@@ -\\d+(?:,(\\d+))? \\+\\d+(?:,(\\d+))? @@"
        )

        for path in sorted((ROOT / "patches").glob("*.patch")):
            lines = path.read_text().splitlines()
            index = 0
            while index < len(lines):
                match = header_re.match(lines[index])
                if match is None:
                    index += 1
                    continue

                expected_old = int(match.group(1) or 1)
                expected_new = int(match.group(2) or 1)
                old_count = 0
                new_count = 0
                header_line = index + 1
                index += 1

                while index < len(lines):
                    line = lines[index]
                    if line.startswith("@@ ") or line.startswith("diff --git "):
                        break
                    if line.startswith("\\ No newline at end of file"):
                        index += 1
                        continue
                    if line.startswith("+"):
                        new_count += 1
                    elif line.startswith("-"):
                        old_count += 1
                    elif line.startswith(" "):
                        old_count += 1
                        new_count += 1
                    else:
                        self.fail(
                            f"{path.name}:{index + 1}: malformed hunk line {line!r}"
                        )
                    index += 1

                self.assertEqual(
                    (old_count, new_count),
                    (expected_old, expected_new),
                    f"{path.name}:{header_line}: hunk line counts do not match header",
                )

    def test_build_checks_each_patch_before_applying_it(self) -> None:
        build = (ROOT / "build.sh").read_text()
        self.assertIn('for patch in "$ROOT"/patches/*.patch; do', build)
        self.assertIn('git apply --check "$patch"', build)
        self.assertIn('git apply "$patch"', build)

    def test_migrated_wiring_is_not_owned_by_python_transformer(self) -> None:
        script = (ROOT / "scripts" / "patch-upstream.py").read_text()
        migrated_paths = (
            'Path("settings.gradle")',
            'Path("addon-create/build.gradle")',
            'Path("addon-create/src/main/resources/META-INF/neoforge.mods.toml")',
            'Path("addon-sable/src/main/resources/META-INF/neoforge.mods.toml")',
            'Path("addon-create/src/main/java/dev/duzo/bluemap3d/create/CreateAddon.java")',
            'Path("core/build.gradle")',
            'Path("core/src/main/java/dev/duzo/bluemap3d/api/BlueMap3D.java")',
            'Path("core/src/main/java/dev/duzo/bluemap3d/bake/VolumeMesher.java")',
            'Path("addon-sable/src/main/java/dev/duzo/bluemap3d/sable/ShipProvider.java")',
            'Path("core/src/main/java/dev/duzo/bluemap3d/runtime/SceneObjectTracker.java")',
            'Path("core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js")',
        )
        for path in migrated_paths:
            with self.subTest(path=path):
                self.assertNotIn(path, script)

        self.assertNotIn("BlueMap3DMod procedural import insertion point", script)
        self.assertNotIn("BlueMap3DMod procedural source insertion point", script)

    def test_copycat_material_renderer_precedes_resource_pack_placeholder(self) -> None:
        patch = (ROOT / "patches/0015-base.patch").read_text()
        copied = patch.index("sources.add(new CopycatsShapeSource(packs))")
        ordinary = patch.index("sources.add(packs)", copied)
        self.assertLess(copied, ordinary)
        build = (ROOT / "build.sh").read_text()
        self.assertIn("CopycatsShapeSource.java", build)

    def test_create_copycat_material_metadata_is_kept_in_carriage_snapshot(self) -> None:
        patch = (ROOT / "patches/0015-base.patch").read_text()
        self.assertIn('("create".equals(namespace) || "create_connected".equals(namespace))', patch)
        self.assertIn('path.contains("copycat")', patch)
        self.assertIn('("railways".equals(namespace) && path.startsWith("copycat_"))', patch)
        self.assertIn('renderDataOf(updateTag, entry.getValue())', patch)

    def test_region_refresh_invalidates_loaded_hires_tiles_and_repeats_feed(self) -> None:
        queue = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/publish/TileRefreshQueue.java").read_text()
        transform = (ROOT / "scripts/transforms/terrain_reload.py").read_text()
        build = (ROOT / "build.sh").read_text()

        self.assertIn("FULL_MAP_REFRESH_SENTINEL", queue)
        self.assertIn("new Vector2i(FULL_MAP_REFRESH_SENTINEL, FULL_MAP_REFRESH_SENTINEL)", queue)
        self.assertIn("var loaded = Array.from(manager.tiles.values())", transform)
        self.assertIn("manager.tryLoadTile(tx, tz)", transform)
        self.assertIn("terrain_reload.py", build)

        drain = queue.split("public Map<String, List<int[]>> drainUndelivered()", 1)[1]
        drain = drain.split("public int version()", 1)[0]
        self.assertNotIn("undelivered.clear()", drain)

    def test_train_snapshots_are_pruned_only_on_authoritative_deletion(self) -> None:
        interface = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/api/SceneObjectProvider.java").read_text()
        persistent = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/api/PersistentSceneObjectProvider.java").read_text()
        transform = (ROOT / "scripts/transforms/contraption_provider.py").read_text()

        self.assertIn("isDefinitelyDeleted(ServerLevel level, String objectId)", interface)
        self.assertIn("delegate.isDefinitelyDeleted(level, cached.id())", persistent)
        self.assertIn("SceneObjectPersistenceStore.remove(id(), cached.id())", persistent)
        self.assertIn("source = _apply_train_snapshot_cleanup(source)", transform)
        self.assertIn("Create.RAILWAYS.trains.get(trainId)", transform)
        self.assertIn("carriageIndex >= train.carriages.size()", transform)
        self.assertIn("getTickCount() < 400", transform)
        self.assertIn("suffix.indexOf('/', slash + 1) >= 0", transform)

        # Carriage DISCARDED is normal during chunk unload, not deletion evidence.
        removal = (ROOT / "overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionDeletionTracker.java").read_text()
        self.assertIn("entity instanceof CarriageContraptionEntity", removal)

    def test_copycats_byte_moving_ct_samples_individual_material_parts(self) -> None:
        source = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/bake/CopycatsSpecialSource.java").read_text()
        self.assertIn('case "copycats:copycat_byte_panel" -> bytePanel(state, metadata)', source)
        self.assertIn('case "copycats:copycat_byte" -> byteQuads(state, metadata)', source)
        self.assertIn('private void addPart(Map<Integer,BlockState> parts', source)
        self.assertIn('base.stateAtOffset(bx-base.x(), by-base.y(), bz-base.z())', source)
        self.assertIn('parts.getOrDefault(cellKey(', source)
        self.assertIn('models.quadsFor(context.withState(material))', source)

    def test_copycats_diagnostics_are_opt_in_bounded_and_cover_both_renderers(self) -> None:
        root = ROOT / "addon-compat/src/main/java/dev/duzo"
        tracer = (root / "bluemapcopycats/CopycatsTrace.java").read_text()
        terrain = (root / "bluemapcopycats/CopycatsTerrainRenderer.java").read_text()
        model = (root / "bluemapcopycats/CopycatsAppearanceResolver.java").read_text()
        ct = (root / "bluemapctm/CopiedMaterialConnectedTextures.java").read_text()
        moving = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/bake/CopycatsSpecialSource.java").read_text()
        shape = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/bake/CopycatsShapeSource.java").read_text()
        self.assertIn('Boolean.getBoolean("bluemap.copycats.trace")', tracer)
        self.assertIn('"bluemap.copycats.trace.center"', tracer)
        self.assertIn('AtomicInteger', tracer)
        self.assertIn('CopycatsTrace.log(block, "ENTITY"', terrain)
        self.assertIn('CopycatsTrace.log(block, "GEOMETRY"', terrain)
        self.assertIn('CopycatsTrace.log(block, "ATLAS"', model)
        self.assertIn('CopycatsTrace.log(owner, "CT"', ct)
        self.assertIn('COPYCATS-MOVING-TRACE phase=SOURCE', moving)
        self.assertIn('COPYCATS-MOVING-TRACE phase=SHAPE', shape)
        resolver = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/bake/ConnectedTextureResolver.java").read_text()
        self.assertIn('COPYCATS-MOVING-TRACE phase={}', resolver)
        self.assertIn('"reason=no-face-metadata"', resolver)
        self.assertIn('"CT-NOMATCH"', resolver)
        self.assertIn('"CT-MISSING-SHEET"', resolver)

    def test_default_create_copycats_and_static_orientation_regressions(self) -> None:
        addon = ROOT / "addon-compat/src/main/java/dev/duzo/bluemapcopycats"
        materials = (addon / "CopycatsMaterialResolver.java").read_text()
        renderer = (addon / "CopycatsTerrainRenderer.java").read_text()
        appearance = (addon / "CopycatsAppearanceResolver.java").read_text()
        moving = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/bake/CopycatsShapeSource.java").read_text()
        self.assertIn("createPanelOrStepMaterial(", materials)
        self.assertIn('new CopycatsMaterial("create:copycat_base", Map.of())', materials)
        self.assertIn('entity == null && !isCreatePanelOrStep(id)', renderer)
        self.assertIn('CopycatsMaterialResolver.createPanelOrStepMaterial(entity)', renderer)
        self.assertIn('CopycatsStaticFacing.panel(property("facing"))', renderer)
        self.assertIn('CopycatsStaticFacing.verticalStep(property("facing"))', renderer)
        self.assertIn('"create:block/copycat_base"', appearance)
        self.assertIn('fallback=self-default', appearance)
        self.assertIn('boolean createPanelOrStep = isCreatePanelOrStep(state)', moving)
        self.assertIn('ResourceLocation.fromNamespaceAndPath("create", "copycat_base")', moving)
        self.assertIn('metadata == null ? "<null>" : metadata.getAllKeys()', moving)
        facing = (addon / "CopycatsStaticFacing.java").read_text()
        self.assertIn('transform.rotateX(90).rotateY(180)', facing)
        self.assertIn('transform.rotateZ(90).rotateY(180)', facing)
        self.assertIn('rotateY(yRotation(facing) + 180)', facing)
        self.assertIn("class CopycatsTerrainOrientationTest", (
            ROOT / "addon-compat/src/test/java/dev/duzo/bluemapcopycats/CopycatsTerrainOrientationTest.java"
        ).read_text())

    def test_create_panel_and_step_render_in_static_and_moving_paths(self) -> None:
        static_dispatch = (ROOT / "addon-compat/src/main/java/dev/duzo/bluemapcopycats/ConnectedTerrainDispatch.java").read_text()
        static_renderer = (ROOT / "addon-compat/src/main/java/dev/duzo/bluemapcopycats/CopycatsTerrainRenderer.java").read_text()
        moving = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/bake/CopycatsShapeSource.java").read_text()

        # Resource pack order must not suppress Create's static material renderer.
        self.assertIn('"create:copycat_panel".equals(id)', static_dispatch)
        self.assertIn('"create:copycat_step".equals(id)', static_dispatch)
        self.assertIn('states.put(entry.getValue(), createCopycatDispatch)', static_dispatch)
        self.assertIn('entry.getValue().setResource(createCopycatDispatch)', static_dispatch)
        self.assertIn('paths.containsKey(id)', static_dispatch)
        self.assertIn('"static Create copycat panels/steps routed %s id(s)"', static_dispatch)
        self.assertIn('case "create:copycat_panel" -> createPanel(entity)', static_renderer)
        self.assertIn('case "create:copycat_step" -> createStep(entity)', static_renderer)
        self.assertIn('cuboid(out, transform, 0, 0, 0, 16, 3, 16, material)', static_renderer)

        # Create's panel shape is CASING_3PX, not a one-pixel wafer. Evaluate the
        # actual state shape first; still show the block if shape lookup fails.
        self.assertIn("Create's CopycatPanelBlock uses AllShapes.CASING_3PX", moving)
        self.assertIn('return createPanelFallback(property(state, "facing"))', moving)
        self.assertIn('return createStepFallback(property(state, "facing"), property(state, "half"))', moving)
        self.assertIn('double p = 3.0 / 16.0', moving)
        self.assertIn('case "north" -> List.of(new AABB(0, 0, 0, 1, 1, p))', moving)
        self.assertIn('phase=CREATE-SHAPE', moving)
        self.assertIn('skip=missing-material-nbt', moving)
        self.assertIn('skip=unusable-copied-material', moving)

    def test_static_copycats_and_railways_compatibility_is_wired(self) -> None:
        generator = (ROOT / "scripts/generate-static-resources.py").read_text()
        registry = (ROOT / "addon-compat/src/main/java/dev/duzo/bluemapcopycats/BlueMapCopycatsCompatAddon.java").read_text()
        renderer = (ROOT / "addon-compat/src/main/java/dev/duzo/bluemapcopycats/CopycatsTerrainRenderer.java").read_text()
        appearance = (ROOT / "addon-compat/src/main/java/dev/duzo/bluemapcopycats/CopycatsAppearanceResolver.java").read_text()
        ctm_dispatch = (ROOT / "addon-compat/src/main/java/dev/duzo/bluemapctm/ConnectedTextureTerrainDispatch.java").read_text()
        ctm_renderer = (ROOT / "addon-compat/src/main/java/dev/duzo/bluemapctm/ConnectedTextureTerrainRenderer.java").read_text()

        self.assertIn('write_dispatch("create", ["copycat_panel", "copycat_step"]', generator)
        self.assertIn('railways_windows = ("round_pane", "single_pane", "two_pane", "four_pane")', generator)
        self.assertIn('"cullingIdentical": False', generator)
        self.assertIn('new Key("create", "copycat")', registry)
        self.assertIn('Registry.class.getDeclaredField("entries")', registry)
        self.assertIn('CopycatsTerrainBlockEntity.class', registry)
        self.assertIn('case "create:copycat_panel" -> createPanel(entity)', renderer)
        self.assertIn('case "create:copycat_step" -> createStep(entity)', renderer)
        self.assertIn('model.applyParent(resourcePack)', appearance)
        self.assertIn('model.applyParent(resourcePack)', ctm_dispatch)
        self.assertIn('modelResource.applyParent(resourcePack)', ctm_renderer)
        self.assertIn('keepsTransparentWindowFaces(', ctm_renderer)

    def test_create_removal_tracking_is_wired_into_distribution(self) -> None:
        build = (ROOT / "build.sh").read_text()
        mixins = (ROOT / "overrides/addon-create/src/main/resources/bluemap3d_create.mixins.json").read_text()
        transform = (ROOT / "scripts/transforms/contraption_provider.py").read_text()

        self.assertIn("ContraptionDeletionTracker.java", build)
        self.assertIn("ContraptionRemovalMixin.java", build)
        self.assertIn('"ContraptionRemovalMixin"', mixins)
        self.assertIn("ContraptionDeletionTracker.drain(level)", transform)
        self.assertIn("ContraptionDeletionTracker.record(level, objectId)", transform)

    def test_terrain_refresh_waits_for_saved_world_and_completed_render(self) -> None:
        build = (ROOT / "build.sh").read_text()
        transform = (ROOT / "scripts/transforms/contraption_provider.py").read_text()
        queue = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/publish/TileRefreshQueue.java").read_text()

        self.assertIn("TileRefreshQueue.java", build)
        self.assertIn("level.save(null, true, false)", transform)
        self.assertIn("terrainPersistTicks", transform)
        self.assertIn("scheduleMapUpdateTask(map, regions, true)", queue)
        self.assertIn("worldRegionFor(pos)", queue)
        self.assertIn("Math.floorDiv(pos.getX(), 512)", queue)
        self.assertIn("pendingTiles", queue)
        self.assertIn("deliverCompletedRenders()", queue)
        self.assertIn("renderQueueSize() != 0", queue)

    def test_train_assembly_queues_the_original_carriage_footprint(self) -> None:
        transform = (ROOT / "scripts/transforms/contraption_provider.py").read_text()

        self.assertIn("entry.assemblyFootprint = assemblyFootprintOf(live.getContraption())", transform)
        self.assertIn("entry.assemblyFootprint = assemblyFootprintOf(contraption)", transform)
        self.assertIn("terrainContraptions.add(objectId)", transform)
        self.assertIn("trackTerrainFootprint(", transform)
        self.assertIn(
            "entry.assemblyFootprint,\n"
            "                        footprintOf(object)",
            transform,
        )
        self.assertIn(
            "BlockPos world = contraption.anchor.offset(local);",
            transform,
        )

    def test_create_persistence_and_diagnostics_are_transition_based(self) -> None:
        persistence = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/api/PersistentSceneObjectProvider.java").read_text()
        removal = (ROOT / "overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/mixin/ContraptionRemovalMixin.java").read_text()
        transform = (ROOT / "scripts/transforms/contraption_provider.py").read_text()

        self.assertIn("CONTRAPTION-SOURCE-DIAG", persistence)
        self.assertIn("ObjectSource.LIVE", persistence)
        self.assertIn("ObjectSource.PERSISTED", persistence)
        self.assertIn("previous == source", persistence)
        self.assertIn("CONTRAPTION-REMOVAL-DIAG", removal)
        self.assertIn("reason != Entity.RemovalReason.DISCARDED", removal)
        self.assertIn("dimensionPrefix + objectId", persistence)

        # Provider enumeration is used both by BlueMap3D publishing and Player History.
        # It must never persist the Minecraft level on every poll. Terrain persistence
        # belongs only to refreshFootprint(), which runs on assembly/disassembly transitions.
        self.assertEqual(transform.count("persistTerrainOnce(level);"), 1)
        self.assertNotIn("diagnoseAssemblyWorldState", transform)
        self.assertNotIn("diagnoseNativeEntityRenderer", transform)
        self.assertNotIn("CONTRAPTION-WORLD-DIAG", transform)
        self.assertNotIn("CONTRAPTION-ENTITY-RENDERER-DIAG", transform)

    def test_mca_region_boundaries_use_floor_division(self) -> None:
        queue = (ROOT / "overrides/core/src/main/java/dev/duzo/bluemap3d/publish/TileRefreshQueue.java").read_text()

        self.assertIn("Math.floorDiv(pos.getX(), 512)", queue)
        self.assertIn("Math.floorDiv(pos.getZ(), 512)", queue)

        cases = {
            0: 0,
            511: 0,
            512: 1,
            -1: -1,
            -512: -1,
            -513: -2,
        }
        for block, region in cases.items():
            self.assertEqual(block // 512, region)

    def test_patch_driver_stays_small_and_delegates_create_transform(self) -> None:
        script = (ROOT / "scripts" / "patch-upstream.py").read_text()
        transform = (ROOT / "scripts" / "transforms" / "contraption_provider.py").read_text()

        self.assertLess(len(script.splitlines()), 100)
        self.assertIn("patch_contraption_provider()", script)
        self.assertNotIn("ContraptionProvider.java", script)
        self.assertIn("ContraptionProvider.java", transform)


if __name__ == "__main__":
    unittest.main()
