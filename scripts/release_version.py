#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess
from collections.abc import Iterable

SEMVER = re.compile(r"^(?:v)?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")
SHA = re.compile(r"^[0-9a-fA-F]{7,40}$")


def parse_version(value: str) -> tuple[int, int, int]:
    match = SEMVER.fullmatch(value.strip())
    if match is None:
        raise ValueError(f"expected stable semantic version MAJOR.MINOR.PATCH, got {value!r}")
    return tuple(int(group) for group in match.groups())  # type: ignore[return-value]


def format_version(version: tuple[int, int, int]) -> str:
    return ".".join(str(part) for part in version)


def latest_version(tags: Iterable[str]) -> tuple[int, int, int] | None:
    versions = []
    for tag in tags:
        try:
            versions.append(parse_version(tag))
        except ValueError:
            continue
    return max(versions) if versions else None


def next_version(base: tuple[int, int, int], bump: str) -> tuple[int, int, int]:
    major, minor, patch = base
    if bump == "patch":
        return major, minor, patch + 1
    if bump == "minor":
        return major, minor + 1, 0
    if bump == "major":
        return major + 1, 0, 0
    raise ValueError(f"unsupported bump: {bump}")


def resolve_manual(
    tags: Iterable[str],
    *,
    bump: str,
    fallback: str,
    exact: str | None = None,
) -> tuple[str, str]:
    tag_list = list(tags)
    latest = latest_version(tag_list)
    base = latest if latest is not None else parse_version(fallback)

    if bump == "exact":
        if not exact:
            raise ValueError("exact release requires --exact MAJOR.MINOR.PATCH")
        target = parse_version(exact)
        if latest is not None and target <= latest:
            raise ValueError(
                f"exact release {format_version(target)} must be newer than latest tag "
                f"v{format_version(latest)}"
            )
    else:
        target = next_version(base, bump)

    version = format_version(target)
    tag = f"v{version}"
    if tag in tag_list:
        raise ValueError(f"tag already exists: {tag}")
    return version, tag


def development_version(tags: Iterable[str], *, fallback: str, sha: str) -> str:
    if not SHA.fullmatch(sha.strip()):
        raise ValueError(f"expected git commit sha, got {sha!r}")

    latest = latest_version(tags)
    target = next_version(latest, "patch") if latest is not None else parse_version(fallback)
    return f"{format_version(target)}-dev.g{sha.strip().lower()[:8]}"


def git_tags() -> list[str]:
    result = subprocess.run(
        ["git", "tag", "--list", "v*"],
        check=True,
        text=True,
        capture_output=True,
    )
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--tag")
    parser.add_argument("--bump", choices=("patch", "minor", "major", "exact"))
    parser.add_argument("--fallback")
    parser.add_argument("--exact")
    parser.add_argument("--development", action="store_true")
    parser.add_argument("--sha")
    args = parser.parse_args()

    if args.tag:
        version = format_version(parse_version(args.tag))
        print(version)
        print(f"v{version}")
        return

    if args.development:
        if not args.fallback or not args.sha:
            parser.error("development resolution requires --fallback and --sha")
        print(development_version(git_tags(), fallback=args.fallback, sha=args.sha))
        return

    if not args.bump or not args.fallback:
        parser.error("manual resolution requires --bump and --fallback")

    version, tag = resolve_manual(
        git_tags(),
        bump=args.bump,
        fallback=args.fallback,
        exact=args.exact,
    )
    print(version)
    print(tag)


if __name__ == "__main__":
    main()
