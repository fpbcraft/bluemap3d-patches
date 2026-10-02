#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FILE = ROOT / "release.json"
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
SHA = re.compile(r"^[0-9a-f]{40}$")
REQUIRED = ("version", "upstreamCommit", "blueMapVersion", "minecraftVersion")


def load_release(
    path: Path | None = None,
    *,
    apply_version_override: bool = True,
) -> dict[str, str]:
    source = path or DEFAULT_FILE
    data = json.loads(source.read_text())
    if not isinstance(data, dict):
        raise ValueError("release metadata must be a JSON object")

    override = os.environ.get("RELEASE_VERSION", "").strip()
    if apply_version_override and override:
        data["version"] = override

    missing = [key for key in REQUIRED if not isinstance(data.get(key), str) or not data[key].strip()]
    if missing:
        raise ValueError(f"release metadata missing string value(s): {', '.join(missing)}")
    if not SEMVER.fullmatch(data["version"]):
        raise ValueError(f"invalid release version: {data['version']!r}")
    if not SHA.fullmatch(data["upstreamCommit"]):
        raise ValueError(f"invalid upstream commit: {data['upstreamCommit']!r}")

    return {key: data[key].strip() for key in REQUIRED}


def main() -> None:
    if sys.argv[1:] == ["--base-version"]:
        print(load_release(apply_version_override=False)["version"])
        return

    data = load_release()
    if sys.argv[1:] == ["--lines"]:
        for key in REQUIRED:
            print(data[key])
        return
    if sys.argv[1:] == ["--json"]:
        print(json.dumps(data, sort_keys=True))
        return
    raise SystemExit("usage: release_metadata.py --base-version | --lines | --json")


if __name__ == "__main__":
    main()
