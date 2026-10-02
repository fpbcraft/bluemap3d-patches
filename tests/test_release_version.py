from __future__ import annotations

import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

import release_version  # noqa: E402


class ReleaseVersionTest(unittest.TestCase):
    def test_parse_stable_semver_and_optional_v_prefix(self) -> None:
        self.assertEqual((1, 2, 3), release_version.parse_version("1.2.3"))
        self.assertEqual((1, 2, 3), release_version.parse_version("v1.2.3"))
        with self.assertRaises(ValueError):
            release_version.parse_version("1.2")
        with self.assertRaises(ValueError):
            release_version.parse_version("1.2.3-beta.1")

    def test_latest_tag_ignores_non_semver_tags(self) -> None:
        self.assertEqual(
            (2, 0, 0),
            release_version.latest_version(["v1.9.9", "preview", "v2.0.0"]),
        )

    def test_semantic_bumps_use_latest_tag(self) -> None:
        tags = ["v1.2.3", "v1.4.0"]
        self.assertEqual(
            ("1.4.1", "v1.4.1"),
            release_version.resolve_manual(tags, bump="patch", fallback="0.1.0"),
        )
        self.assertEqual(
            ("1.5.0", "v1.5.0"),
            release_version.resolve_manual(tags, bump="minor", fallback="0.1.0"),
        )
        self.assertEqual(
            ("2.0.0", "v2.0.0"),
            release_version.resolve_manual(tags, bump="major", fallback="0.1.0"),
        )

    def test_fallback_is_used_before_first_release_tag(self) -> None:
        self.assertEqual(
            ("0.8.28", "v0.8.28"),
            release_version.resolve_manual([], bump="patch", fallback="0.8.27"),
        )

    def test_exact_release_must_advance_latest_tag(self) -> None:
        self.assertEqual(
            ("1.3.0", "v1.3.0"),
            release_version.resolve_manual(
                ["v1.2.3"], bump="exact", fallback="0.1.0", exact="1.3.0"
            ),
        )
        with self.assertRaises(ValueError):
            release_version.resolve_manual(
                ["v1.2.3"], bump="exact", fallback="0.1.0", exact="1.2.3"
            )

    def test_development_build_uses_next_patch_and_short_sha(self) -> None:
        self.assertEqual(
            "1.4.1-dev.gabcdef12",
            release_version.development_version(
                ["v1.2.3", "v1.4.0"],
                fallback="0.1.0",
                sha="ABCDEF1234567890ABCDEF1234567890ABCDEF12",
            ),
        )

    def test_development_build_uses_fallback_before_first_tag(self) -> None:
        self.assertEqual(
            "0.8.27-dev.g12345678",
            release_version.development_version(
                [],
                fallback="0.8.27",
                sha="1234567890abcdef1234567890abcdef12345678",
            ),
        )

    def test_development_build_rejects_non_commit_sha(self) -> None:
        with self.assertRaises(ValueError):
            release_version.development_version([], fallback="1.0.0", sha="dirty")


if __name__ == "__main__":
    unittest.main()
