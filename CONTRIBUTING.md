# Contributing

This repository reconstructs an FPBCRAFT BlueMap3D build from a pinned upstream commit. Treat
reproducibility and behavior preservation as first-class requirements.

## Supported baseline

`release.json` is the source of truth for:

- release version;
- pinned upstream BlueMap3D commit;
- BlueMap target;
- Minecraft target.

Do not duplicate those values in build scripts or source unless the value is intentionally part
of runtime behavior.

## Local build

Requirements:

- Java 21;
- Git;
- Bash;
- Python 3.

Run the canonical build from the repository root:

```bash
./build.sh
```

The build runs the Python regression tests and compatibility-rule validator before cloning the
pinned upstream commit, applying the patch series, copying maintained overrides, running the
remaining deterministic transforms, building the Java artifacts, and packaging `dist/`.

A change is not ready to merge if `./build.sh` fails from a clean checkout.

## Choosing the right extension point

Prefer the smallest mechanism that expresses the behavior:

1. JSON compatibility rule for matching, aliases, tinting, namespace policy, or feature flags.
2. Reusable generic capability when the behavior is useful beyond one mod.
3. Small Java adapter for genuinely mod-specific runtime data.
4. Whole-file override for source that FPBCRAFT intentionally owns.
5. Numbered Git patch for stable structural edits to upstream-owned source.
6. Dedicated transform only when the source is still too entangled for the mechanisms above.

See [docs/patch-architecture.md](docs/patch-architecture.md) for patch ownership rules.

Do not add new logic to `scripts/patch-upstream.py` unless it is orchestration or depends on
release metadata. New behavioral transforms belong in a dedicated transform module and should
have an explicit path toward a patch or maintained override.

## Compatibility changes

For JSON compatibility work:

- update or add a document under `compat/builtin/`;
- keep rule IDs stable when modifying an existing behavior;
- validate against `compat/schema.json`;
- prefer wildcard rules over enumerating generated block IDs;
- document a new generic capability in `compat/README.md`.

Use Java only when the server needs information that cannot be represented by the compatibility
schema, such as block-entity data, dynamic textures, optional runtime integration, or custom
geometry.

## Tests and regression contracts

Changes should add the narrowest regression contract that protects the behavior being modified.

Important existing contracts include:

- release metadata and patch-architecture tests;
- compatibility schema/rule validation;
- shared compatibility-engine tests;
- scene persistence policy tests;
- full reconstructed upstream Gradle build.

Avoid changing runtime behavior merely to make a cleanup test easier. Cleanup/refactor PRs should
remain behavior-neutral unless the PR explicitly documents a behavior change.

## Pull requests

Keep PRs cohesive and independently reviewable. State:

- what responsibility is moving or behavior is changing;
- whether runtime behavior is intended to change;
- which build/test contracts cover the change;
- whether `release.json`, compatibility rules, cache formats, or install artifacts are affected.

When a sequence of PRs is stacked, prefer merge commits for parent PRs or rebase the child onto
the resulting `main` immediately after its parent is merged. Squashing a parent rewrites commit
ancestry and otherwise causes the child to include/conflict with already-merged changes.

## Releases

Follow [docs/release-process.md](docs/release-process.md). The repository CI currently builds and
uploads an Actions artifact; it does not automatically publish a GitHub Release.
