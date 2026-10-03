# Contributing

This repository patches a pinned BlueMap3D upstream for the FPBCRAFT Minecraft 1.21.1 / BlueMap 5.7 baseline. Changes should preserve reproducibility and keep compatibility logic at the narrowest appropriate layer.

## Choose the right extension point

Prefer, in order:

1. a rule in `compat/builtin/` when matching, tinting, aliasing, or namespace policy is enough;
2. a reusable generic capability when the behavior can serve more than one mod;
3. a small specialized adapter for mod-specific runtime data or rendering semantics;
4. an upstream patch or maintained override only when the upstream class itself must change.

Do not add a compatibility shim to a large class merely to preserve an old internal helper API after ownership has moved. Migrate the real call sites instead.

## Patch ownership

- `patches/*.patch` contains deterministic, ordered changes against the pinned upstream commit. Keep patches narrowly scoped and named with the next appropriate numeric prefix.
- `overrides/` contains files maintained as complete source replacements. If you add a new override, wire it into `build.sh`.
- `scripts/transforms/` is for narrow source transformations that are impractical as static patches. Keep `scripts/patch-upstream.py` as orchestration rather than accumulating implementation logic.
- Never edit `.tmp-bluemap3d/` or `dist/` as source; both are generated.

When changing the pinned upstream commit, do that separately from behavior refactors so patch drift is easy to diagnose.

## Development workflow

Use a focused branch such as `fix/...`, `refactor/...`, `feat/...`, or `chore/...`. Keep each PR to one responsibility and avoid unrelated formatting churn.

For behavior changes and refactors, add or strengthen a regression contract before or alongside the structural change. Prefer tests for pure semantics over tests that reproduce a large integration environment.

The authoritative local verification is:

```bash
./build.sh
```

That command runs the Python regression suite, validates compatibility rules, checks every numbered patch with `git apply --check`, builds the patched upstream, runs core tests, builds the native compatibility addon, and packages the distribution.

For a compatibility-rule-only iteration, these faster checks are useful before the full build:

```bash
python3 -m unittest discover -s tests -p 'test_*.py'
python3 scripts/validate-compat.py
python3 scripts/generate-compat-shared.py --check
```

## Releases

`release.json` is the source of truth for the baseline version and supported upstream targets. Ordinary builds derive immutable development versions from the commit SHA. Published releases use exact SemVer tags.

Do not manually manufacture release artifacts or commit generated `dist/` output. Release workflow changes should preserve immutable tag/version behavior and artifact verification.

## Pull requests

PRs should explain:

- the responsibility being changed;
- whether runtime/rendering behavior is intended to change;
- the regression coverage or verification performed;
- any impact on patch ownership, compatibility config, persisted cache formats, or release metadata.

Keep cleanup PRs behavior-preserving unless the behavior change is explicitly part of the PR.
