# Releases

BlueMap3D Patches uses semantic Git tags as the stable release namespace.

## Version rules

Stable versions such as `1.2.3` are reserved for GitHub Releases backed by the matching
`v1.2.3` tag. Normal CI/development builds are always versioned uniquely from the latest
semantic tag and the commit being built, for example:

```text
1.1.12-dev.g1a2b3c4
```

The configured version in release.json (`version`) is a bootstrap/default value. Once semantic tags exist,
the latest `vMAJOR.MINOR.PATCH` tag drives release bumps and development-version sequencing.

A release workflow will not replace an existing GitHub Release. If a published release needs a
fix, create a new semantic version.

## Manual release

In GitHub:

1. Open **Actions**.
2. Select **Release**.
3. Choose **Run workflow** from `main`.
4. Select `patch`, `minor`, `major`, or `exact`.
5. For `exact`, enter a stable `MAJOR.MINOR.PATCH` newer than the latest semantic tag.

The workflow resolves the target version, runs the full build/test suite, and only after a
successful build creates the annotated Git tag. It then creates the GitHub Release with generated
release notes, checksums, and the BlueMap3D bundle JAR, `bluemap-compat-<version>.jar`, and the versioned distribution ZIP.

This ordering prevents a failed build from reserving a release tag.

## Tag-driven release

You may instead create and push a stable semantic tag manually:

```bash
git tag -a v1.2.3 -m "Release v1.2.3"
git push origin v1.2.3
```

Pushing `v*` triggers the same Release workflow. The tag must be a valid stable semantic version,
its commit must be reachable from `main`, and a GitHub Release with that tag must not already
exist.

## Immutability

- stable versions are emitted only by the Release workflow;
- non-release CI builds carry a `-dev.g<short-sha>` suffix;
- release assets are never uploaded with `--clobber`;
- an existing GitHub Release causes the workflow to fail rather than replace its assets;
- manual releases refuse an already-existing semantic tag.

The release version is injected into the checkout at build time; creating a release does not
require a version-bump commit on `main`.
