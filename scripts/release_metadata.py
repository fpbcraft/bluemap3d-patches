#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

from release_version import development_version, git_tags

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FILE = ROOT / "release.json"
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
SHA = re.compile(r"^[0-9a-f]{40}$")
REQUIRED = ("version", "upstreamCommit", "blueMapVersion", "minecraftVersion")


def _effective_version(base: str) -> str:
    override = os.environ.get("BLUEMAP3D_VERSION", "").strip()
    if override:
        if not SEMVER.fullmatch(override):
            raise ValueError(f"invalid BLUEMAP3D_VERSION: {override!r}")
        return override

    sha = subprocess.check_output(
        ["git", "rev-parse", "HEAD"],
        cwd=ROOT,
        text=True,
    ).strip()
    return development_version(git_tags(ROOT), fallback=base, sha=sha)


def load_release(path: Path | None = None) -> dict[str, str]:
    source = path or DEFAULT_FILE
    data = json.loads(source.read_text())
    if not isinstance(data, dict):
        raise ValueError("release metadata must be a JSON object")

    missing = [key for key in REQUIRED if not isinstance(data.get(key), str) or not data[key].strip()]
    if missing:
        raise ValueError(f"release metadata missing string value(s): {', '.join(missing)}")
    if not SEMVER.fullmatch(data["version"]):
        raise ValueError(f"invalid release version: {data['version']!r}")
    if not SHA.fullmatch(data["upstreamCommit"]):
        raise ValueError(f"invalid upstream commit: {data['upstreamCommit']!r}")

    result = {key: data[key].strip() for key in REQUIRED}
    if path is None:
        result["version"] = _effective_version(result["version"])
    return result


def main() -> None:
    data = load_release()
    if sys.argv[1:] == ["--lines"]:
        for key in REQUIRED:
            print(data[key])
        return
    if sys.argv[1:] == ["--json"]:
        print(json.dumps(data, sort_keys=True))
        return
    raise SystemExit("usage: release_metadata.py --lines | --json")


if __name__ == "__main__":
    main()
