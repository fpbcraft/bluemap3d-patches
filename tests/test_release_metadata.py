from __future__ import annotations

import json
import re
import sys
import tempfile
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
                load_release(path)


if __name__ == "__main__":
    unittest.main()
