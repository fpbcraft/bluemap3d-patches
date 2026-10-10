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
