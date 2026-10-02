# Release process

The release process is intentionally reproducible from a clean checkout.

## 1. Set release metadata

Update `release.json` when the release version or compatibility baseline changes.

The file must contain non-empty string values for:

- `version` — semantic version accepted by `scripts/release_metadata.py`;
- `upstreamCommit` — full 40-character upstream BlueMap3D commit SHA;
- `blueMapVersion`;
- `minecraftVersion`.

Do not update the pinned upstream commit as part of an unrelated release unless the upstream
change has been reviewed against the patch series and full build.

## 2. Build from a clean checkout

Run:

```bash
./build.sh
```

The build must complete the Python tests, compatibility validation, upstream patch application,
core tests, bundle build, compatibility-addon build, and distribution packaging.

A patch that no longer applies must be fixed deliberately; do not weaken `git apply --check`
or silently skip a patch.

## 3. Inspect the distribution

The generated `dist/` directory should contain the installable artifacts and provenance files,
including:

- `bluemap3d-bundle-*.jar`;
- `bluemap-compat-<version>.jar`;
- `BUILD_INFO.json`;
- `MANIFEST.json`;
- `UPSTREAM.txt`;
- `UPSTREAM-LICENSE.txt`;
- BlueMap and Minecraft target files;
- `bluemap3d-patches-<version>.zip`.

Verify that `BUILD_INFO.json` contains the expected release metadata and the patch-repository
commit. `MANIFEST.json` records the size and SHA-256 digest of every packaged file present when
the manifest is generated.

## 4. Verify CI

The `Build BlueMap3D patches` workflow runs for pull requests, pushes to `main`, and manual
workflow dispatches. It uploads the contents of `dist/` as a versioned Actions artifact.

Before publishing, verify that the build for the intended release commit is green and download
the artifact produced by that commit rather than mixing files from different runs.

## 5. Installation smoke test

For FPBCRAFT, validate at minimum:

- the bundle JAR loads as a normal mod;
- the unified BlueMap compatibility JAR loads from `config/bluemap/packs/`;
- BlueMap starts without duplicate/legacy compatibility addons;
- representative static compatibility rules render;
- moving Create/Sable objects appear;
- persistent scene objects survive a normal restart;
- optional Simulated integration still behaves correctly when Simulated is present;
- the bundle still loads when optional Simulated classes are absent.

If a cache-format migration changed, include an upgrade test from the previous released cache.

## 6. Publish

This repository does not currently create GitHub Releases automatically.

If publishing a GitHub Release:

1. use the exact commit whose CI artifact was verified;
2. create the release/tag manually;
3. attach the versioned distribution ZIP or the verified installable artifacts;
4. describe compatibility-target changes, required file removals, cache migrations, and any
   external asset-pack requirement.

Do not rebuild from an uncommitted working tree for publication.

## 7. Rollback

A rollback should restore both code and installation state:

- reinstall the previously verified bundle and compatibility addon together;
- restore any required external asset pack;
- check whether the newer release performed a one-way cache migration before reusing runtime
  cache files with an older build.

Runtime caches under `config/bluemap3d/cache/` are generated state, not source configuration.
