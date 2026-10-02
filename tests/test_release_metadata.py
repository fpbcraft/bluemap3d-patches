from __future__ import annotations

import json
import os
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from release_metadata import build_release, load_release  # noqa: E402


class ReleaseMetadataTest(unittest.TestCase):
    def test_repository_metadata_is_valid(self) -> None:
        release = load_release()
        self.assertRegex(release["version"], r"^[0-9]+\.[0-9]+\.[0-9]+")
        self.assertRegex(release["upstreamCommit"], r"^[0-9a-f]{40}$")
        self.assertTrue(release["blueMapVersion"])
        self.assertTrue(release["minecraftVersion"])

    def test_build_metadata_defaults_to_commit_qualified_version(self) -> None:
        release = build_release()
        source = load_release()
        self.assertRegex(
            release["version"],
            rf"^{re.escape(source['version'])}-dev\.[0-9a-f]+$",
        )

    def test_exact_release_version_can_be_injected(self) -> None:
        previous = os.environ.get("BLUEMAP3D_VERSION")
        os.environ["BLUEMAP3D_VERSION"] = "9.8.7"
        try:
            self.assertEqual(build_release()["version"], "9.8.7")
        finally:
            if previous is None:
                os.environ.pop("BLUEMAP3D_VERSION", None)
            else:
                os.environ["BLUEMAP3D_VERSION"] = previous

    def test_invalid_version_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "release.json"
            path.write_text(
                json.dumps(
                    {
                        "version": "latest",
                        "upstreamCommit": "f" * 40,
                        "blueMapVersion": "5.7",
                        "minecraftVersion": "1.21.1",
                    }
                )
            )
            with self.assertRaisesRegex(ValueError, "invalid release version"):
                load_release(path)


if __name__ == "__main__":
    unittest.main()
