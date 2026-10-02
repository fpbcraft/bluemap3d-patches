# Patch architecture

The build reconstructs the FPB BlueMap3D variant from a pinned upstream commit. Changes are
owned by one of three mechanisms, in preference order:

1. **Whole-file overrides** for files that are maintained as FPB-owned implementations.
2. **Numbered Git patches** for stable structural changes to upstream-owned files.
3. **`scripts/patch-upstream.py`** only for transformations that still need dynamic values or
   that are too entangled to migrate safely in one step.

## Patch series

Patches in `patches/` are applied in filename order. Every patch is checked with
`git apply --check` before it is applied, so upstream drift fails the build at the patch that
no longer matches.

Current ownership:

- `0015-base.patch`: historical base integration patch.
- `0020-build-and-provider-wiring.patch`: module inclusion, Create/Sable compile and mixin
  wiring, Create provider registration, and core test wiring.
- `0030-core-runtime-wiring.patch`: generic persistent-provider lifecycle hooks, static
  model-source registration, and dynamic model attachment translation.
- `0040-sable-persistence.patch`: Sable deletion evidence, cache migration, and exact
  structure-based geometry revisioning.

## Remaining Python transformations

The large remaining transformations are behavioral and should be migrated separately, with
regression coverage around each migration:

- `ContraptionProvider.java`: Sable projection, dynamic topology and compatibility behavior.
- `BlueMap3DMod.java`: release-version startup marker only.
- `SceneObjectTracker.java`: geometry-version/live-scale publication semantics.
- `bluemap3d.core.js`: live/replay dynamic-node runtime.
- release-version/build-marker substitutions that depend on `release.json`.

The next migrations should favor cohesive behavior slices rather than mechanically converting
every string replacement into a patch.
