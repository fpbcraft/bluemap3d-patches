#!/usr/bin/env python3
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_FILE = ROOT / "release.json"
SEMVER = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
SHA = re.compile(r"^[0-9a-f]{40}$")
REQUIRED = ("version", "upstreamCommit", "blueMapVersion", "minecraftVersion")


def validate_version(version: str) -> str:
    value = version.strip()
    if not SEMVER.fullmatch(value):
        raise ValueError(f"invalid release version: {version!r}")
    return value


def load_release(path: Path | None = None) -> dict[str, str]:
    source = path or DEFAULT_FILE
    data = json.loads(source.read_text())
    if not isinstance(data, dict):
        raise ValueError("release metadata must be a JSON object")

    missing = [key for key in REQUIRED if not isinstance(data.get(key), str) or not data[key].strip()]
    if missing:
        raise ValueError(f"release metadata missing string value(s): {', '.join(missing)}")

    version = validate_version(data["version"])
    if not SHA.fullmatch(data["upstreamCommit"]):
        raise ValueError(f"invalid upstream commit: {data['upstreamCommit']!r}")

    result = {key: data[key].strip() for key in REQUIRED}
    result["version"] = version
    return result


def current_short_sha() -> str:
    value = subprocess.check_output(
        ["git", "rev-parse", "--short=8", "HEAD"],
        cwd=ROOT,
        text=True,
        stderr=subprocess.DEVNULL,
    ).strip()
    if not re.fullmatch(r"[0-9a-f]{7,40}", value):
        raise ValueError("could not resolve current Git commit")
    return value


def build_release(path: Path | None = None) -> dict[str, str]:
    release = load_release(path)
    override = os.environ.get("BLUEMAP3D_VERSION", "").strip()
    if override:
        release["version"] = validate_version(override)
    elif path is None:
        release["version"] = f"{release['version']}-dev.{current_short_sha()}"
    return release


def main() -> None:
    data = build_release()
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
