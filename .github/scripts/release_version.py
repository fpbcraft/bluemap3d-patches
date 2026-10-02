#!/usr/bin/env python3
from __future__ import annotations

import argparse
import re
import subprocess

SEMVER = re.compile(
    r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)"
    r"(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?"
    r"(?:\+([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?$"
)
STABLE_TAG = re.compile(r"^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$")


def normalize_version(value: str) -> str:
    version = value.strip()
    if version.startswith("v"):
        version = version[1:]
    if not SEMVER.fullmatch(version):
        raise SystemExit(f"Invalid semantic version: {value!r}")
    return version


def latest_stable_tag() -> str | None:
    output = subprocess.check_output(
        ["git", "tag", "--list", "v*"], text=True, stderr=subprocess.DEVNULL
    )
    candidates: list[tuple[tuple[int, int, int], str]] = []
    for raw in output.splitlines():
        match = STABLE_TAG.fullmatch(raw.strip())
        if match is None:
            continue
        candidates.append(
            (
                tuple(int(match.group(index)) for index in range(1, 4)),
                raw.strip(),
            )
        )
    return max(candidates, default=None, key=lambda item: item[0])[1] if candidates else None


def stable_parts(version: str) -> tuple[int, int, int]:
    match = SEMVER.fullmatch(normalize_version(version))
    assert match is not None
    if match.group(4) is not None or match.group(5) is not None:
        raise SystemExit("Automatic major/minor/patch increments require a stable base version")
    return tuple(int(match.group(index)) for index in range(1, 4))


def write_outputs(version: str, tag: str, previous_tag: str | None, path: str) -> None:
    prerelease = "true" if "-" in version else "false"
    with open(path, "a", encoding="utf-8") as output:
        output.write(f"version={version}\n")
        output.write(f"tag={tag}\n")
        output.write(f"previous_tag={previous_tag or ''}\n")
        output.write(f"prerelease={prerelease}\n")


def command_next(args: argparse.Namespace) -> None:
    previous_tag = latest_stable_tag()

    if args.increment == "custom":
        if not args.custom.strip():
            raise SystemExit("--custom is required when --increment=custom")
        version = normalize_version(args.custom)
    else:
        base = previous_tag[1:] if previous_tag else normalize_version(args.fallback)
        major, minor, patch = stable_parts(base)
        if args.increment == "major":
            major, minor, patch = major + 1, 0, 0
        elif args.increment == "minor":
            minor, patch = minor + 1, 0
        elif args.increment == "patch":
            patch += 1
        else:
            raise SystemExit(f"Unsupported increment: {args.increment}")
        version = f"{major}.{minor}.{patch}"

    tag = f"v{version}"
    existing = subprocess.run(
        ["git", "rev-parse", "--quiet", "--verify", f"refs/tags/{tag}"],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
        check=False,
    )
    if existing.returncode == 0:
        raise SystemExit(f"Tag already exists: {tag}")

    write_outputs(version, tag, previous_tag, args.github_output)


def command_validate(args: argparse.Namespace) -> None:
    tag = args.tag.strip()
    if not tag.startswith("v"):
        raise SystemExit(f"Release tags must start with 'v': {tag!r}")
    version = normalize_version(tag)
    write_outputs(version, f"v{version}", latest_stable_tag(), args.github_output)


def main() -> None:
    parser = argparse.ArgumentParser()
    subparsers = parser.add_subparsers(dest="command", required=True)

    next_parser = subparsers.add_parser("next")
    next_parser.add_argument(
        "--increment",
        choices=("patch", "minor", "major", "custom"),
        required=True,
    )
    next_parser.add_argument("--custom", default="")
    next_parser.add_argument("--fallback", required=True)
    next_parser.add_argument("--github-output", required=True)
    next_parser.set_defaults(func=command_next)

    validate_parser = subparsers.add_parser("validate")
    validate_parser.add_argument("--tag", required=True)
    validate_parser.add_argument("--github-output", required=True)
    validate_parser.set_defaults(func=command_validate)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
