#!/usr/bin/env python3
from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "compat" / "shared" / "SharedCompatRules.java.in"
TARGETS = {
    ROOT / "addon-compat" / "src" / "main" / "java" / "dev" / "duzo" / "bluemapcompat" / "SharedCompatRules.java":
        "dev.duzo.bluemapcompat",
    ROOT / "overrides" / "core" / "src" / "main" / "java" / "dev" / "duzo" / "bluemap3d" / "compat" / "SharedCompatRules.java":
        "dev.duzo.bluemap3d.compat",
}


def rendered(package: str) -> str:
    return TEMPLATE.read_text().replace("__PACKAGE__", package)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()

    stale: list[Path] = []
    for path, package in TARGETS.items():
        expected = rendered(package)
        if args.check:
            if not path.is_file() or path.read_text() != expected:
                stale.append(path.relative_to(ROOT))
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(expected)

    if stale:
        joined = ", ".join(map(str, stale))
        raise SystemExit(f"generated compatibility source is stale: {joined}")


if __name__ == "__main__":
    main()
