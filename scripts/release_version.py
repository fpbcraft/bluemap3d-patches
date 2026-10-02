#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
from collections.abc import Iterable

SEMVER = re.compile(
    r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
STABLE = re.compile(r"^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")


def normalize_version(value: str) -> str:
    version = value.strip()
    if version.startswith("v"):
        version = version[1:]
    if not SEMVER.fullmatch(version):
        raise ValueError(f"invalid semantic version: {value!r}")
    return version


def version_from_tag(tag: str) -> str:
    if not tag.startswith("v"):
        raise ValueError(f"release tag must start with 'v': {tag!r}")
    return normalize_version(tag)


def stable_tuple(value: str) -> tuple[int, int, int] | None:
    version = value[1:] if value.startswith("v") else value
    match = STABLE.fullmatch(version)
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def next_version(
    tags: Iterable[str],
    bump: str,
    fallback: str,
    exact: str | None = None,
) -> str:
    if exact and exact.strip():
        return normalize_version(exact)

    if bump not in {"patch", "minor", "major"}:
        raise ValueError(f"invalid bump: {bump!r}")

    fallback_tuple = stable_tuple(normalize_version(fallback))
    if fallback_tuple is None:
        raise ValueError("fallback version must be a stable semantic version")

    candidates = [fallback_tuple]
    candidates.extend(
        parsed
        for tag in tags
        if (parsed := stable_tuple(tag)) is not None
    )
    major, minor, patch = max(candidates)

    if bump == "major":
        return f"{major + 1}.0.0"
    if bump == "minor":
        return f"{major}.{minor + 1}.0"
    return f"{major}.{minor}.{patch + 1}"


def repository_tags() -> list[str]:
    output = subprocess.check_output(
        ["git", "tag", "--list", "v*"],
        text=True,
    )
    return [line.strip() for line in output.splitlines() if line.strip()]


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    from_tag = subparsers.add_parser("from-tag")
    from_tag.add_argument("tag")

    next_release = subparsers.add_parser("next")
    next_release.add_argument("--bump", choices=("patch", "minor", "major"), required=True)
    next_release.add_argument("--fallback", required=True)
    next_release.add_argument("--exact", default="")

    args = parser.parse_args()

    try:
        if args.command == "from-tag":
            print(version_from_tag(args.tag))
        else:
            print(
                next_version(
                    repository_tags(),
                    args.bump,
                    args.fallback,
                    args.exact,
                )
            )
    except ValueError as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
