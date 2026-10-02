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

    def test_patch_driver_stays_small_and_delegates_create_transform(self) -> None:
        script = (ROOT / "scripts" / "patch-upstream.py").read_text()
        transform = (ROOT / "scripts" / "transforms" / "contraption_provider.py").read_text()

        self.assertLess(len(script.splitlines()), 100)
        self.assertIn("patch_contraption_provider()", script)
        self.assertNotIn("ContraptionProvider.java", script)
        self.assertIn("ContraptionProvider.java", transform)


if __name__ == "__main__":
    unittest.main()
