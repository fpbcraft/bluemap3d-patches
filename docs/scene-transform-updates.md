# Dirty scene transforms

Live root transforms are cached by mesh identity and accepted pose endpoints. A stationary object or an already-rendered interpolation alpha no longer recomposes its root matrix every frame. Root matrices are updated explicitly rather than through Three's per-frame matrixAutoUpdate. Only that root block is gated: rate/loop/UV and travel-driven child animations still run through the existing path.

Instance group lookup maps are weakly cached per accepted sample. Settled groups skip matrix writes and buffer uploads across equal snapshots, including newly parsed equivalent arrays. Mixed groups avoid rewriting unchanged instances. Changed matrices use a contiguous updateRange supported by the pinned Three version, unioned with any still-pending range so background/poll updates are not lost before a GPU upload. Newly allocated/replaced meshes and changing instance counts invalidate the relevant state. Latest feed poses keep being collected for every object/dimension.

Diagnostics add cumulative root writes/skips, instance matrix writes/uploads and skipped group frames under window.__bluemap3d.stats().transformWork. These are work counters, not FPS measurements. Compatible mob instancing, worker-based geometry decode and network transport redesign are not part of this change.

Focused tests cover equal snapshots, motion/scale/mesh replacement, count changes, pending-range unions, and the actual generated writeTransform/writeInstanceTransforms functions. The generated runtime tests verify a continuously animated node still advances on a stationary root and a mixed group uploads only its moving instance range. They run in build.sh after all runtime transforms, before Java compilation. All patches/transforms apply to the pinned upstream commit and generated JavaScript passes syntax checking. Full artifact build, dense-scene visual parity and matched before/after CPU/GPU measurement remain pending when opening this draft. No performance speedup is claimed from counters alone.

## Validation after the initial draft

The complete build.sh pipeline passes from pinned upstream, including generated-runtime tests, core/entity/compat Java tests and bundle/compat packaging. GitHub build CI passes at the implementation commit.

`benchmarks/scene-transforms.cjs` compares generated main (`2c5171dc`, core-history-44) with the candidate (core-history-45), using the real pinned Three.js 0.147 implementation. It exercises 6,749 stationary root objects and 5,000 stationary instances, includes forced world-matrix propagation, warms up 30 frames, then measures five trials of 60 frames. Final representative world/instance matrices are asserted equal. Each side uses its actual runtime matrixAutoUpdate policy.

| Local CPU metric | Before | After |
|---|---:|---:|
| Median CPU time per fixture frame | 3.715 ms | 1.136 ms |
| Instance buffer update signals in 300 measured frames | 300 | 0 |

The local static-scene transform fixture uses about 69% less CPU. This is **not** a production FPS, moving-scene or GPU timing result: it intentionally isolates stationary work and includes no rendering, feed parsing or geometry loading. Actual generated-runtime regression tests separately verify continuous animation and mixed moving/static instance correctness. Dense-scene visual parity, animated camera paths and GPU measurements remain release validation.

Run with BASELINE_CORE and CANDIDATE_CORE pointing to generated bluemap3d.core.js files and THREE_MODULE pointing to the pinned three/build/three.cjs, then `node benchmarks/scene-transforms.cjs`.
