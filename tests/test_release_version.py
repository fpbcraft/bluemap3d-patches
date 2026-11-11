from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from release_version import (  # noqa: E402
    development_version,
    next_version,
    normalize_version,
    version_from_tag,
)


class ReleaseVersionTest(unittest.TestCase):
    def test_normalizes_exact_versions(self) -> None:
        self.assertEqual("1.2.3", normalize_version("1.2.3"))
        self.assertEqual("1.2.3-beta.1", normalize_version("v1.2.3-beta.1"))
        with self.assertRaisesRegex(ValueError, "invalid semantic version"):
            normalize_version("latest")

    def test_release_tags_require_v_prefix(self) -> None:
        self.assertEqual("1.2.3", version_from_tag("v1.2.3"))
        with self.assertRaisesRegex(ValueError, "must start with 'v'"):
            version_from_tag("1.2.3")

    def test_development_version_uses_short_commit_sha(self) -> None:
        self.assertEqual(
            "1.2.3-dev.a1b2c3d4",
            development_version("1.2.3", "a1b2c3d4e5f67890"),
        )
        with self.assertRaisesRegex(ValueError, "stable semantic version"):
            development_version("1.2.3-rc.1", "a1b2c3d4")
        with self.assertRaisesRegex(ValueError, "invalid git SHA"):
            development_version("1.2.3", "not-a-sha")

    def test_patch_minor_and_major_bumps_use_highest_stable_baseline(self) -> None:
        tags = ["v1.2.2", "v1.3.0-beta.1", "not-a-release", "v1.1.9"]
        self.assertEqual("1.2.4", next_version(tags, "patch", "1.2.3"))
        self.assertEqual("1.3.0", next_version(tags, "minor", "1.2.3"))
        self.assertEqual("2.0.0", next_version(tags, "major", "1.2.3"))

    def test_existing_tag_can_advance_beyond_repository_default(self) -> None:
        self.assertEqual(
            "2.4.8",
            next_version(["v2.4.7"], "patch", "1.9.9"),
        )

    def test_exact_version_overrides_bump_and_can_be_prerelease(self) -> None:
        self.assertEqual(
            "3.0.0-rc.1",
            next_version(["v2.0.0"], "patch", "2.0.0", "v3.0.0-rc.1"),
        )


if __name__ == "__main__":
    unittest.main()
