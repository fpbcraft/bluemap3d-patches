from __future__ import annotations

import json
import re
import sys
import tempfile
from unittest.mock import patch
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from release_metadata import load_release  # noqa: E402


class ReleaseMetadataTest(unittest.TestCase):
    def test_repository_metadata_is_valid(self) -> None:
        release = load_release()
        self.assertRegex(release["version"], r"^[0-9]+\.[0-9]+\.[0-9]+")
        self.assertRegex(release["upstreamCommit"], r"^[0-9a-f]{40}$")
        self.assertTrue(release["blueMapVersion"])
        self.assertTrue(release["minecraftVersion"])

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
                load_release(path, apply_version_override=False)

    def test_release_version_can_be_overridden_for_tagged_builds(self) -> None:
        with patch.dict("os.environ", {"RELEASE_VERSION": "2.4.0-rc.1"}):
            release = load_release()
        self.assertEqual("2.4.0-rc.1", release["version"])

    def test_invalid_release_version_override_is_rejected(self) -> None:
        with patch.dict("os.environ", {"RELEASE_VERSION": "latest"}):
            with self.assertRaisesRegex(ValueError, "invalid release version"):
                load_release()


if __name__ == "__main__":
    unittest.main()
