# BlueMap3D Patches

BlueMap 5.7 / BlueMap3D compatibility for the FPBCRAFT Minecraft 1.21.1 NeoForge server.

The repository is intentionally split between **declarative compatibility rules** and
**code adapters**:

- add a JSON rule when a mod only needs matching, tinting, namespace policy, or another
  supported generic behavior;
- add Java only when a mod introduces a genuinely new rendering/runtime concept.

## Supported baseline

The compatibility targets and baseline semantic version live in [`release.json`](release.json).
Normal development/CI builds derive an immutable version from that baseline and the commit,
for example `1.1.11-dev.a1b2c3d4`. Published releases use the exact `vMAJOR.MINOR.PATCH`
tag version. The current baseline targets Minecraft 1.21.1, BlueMap 5.7, and a pinned
BlueMap3D upstream commit. Do not assume compatibility with other targets unless
`release.json` says so.

## Artifacts

The build produces two installable JARs:

1. **`bluemap3d-bundle-*.jar`** — patched BlueMap3D bundle
   - persistent Sable/Create moving objects
   - persistent Sable child contraptions (including Aeronautics propeller/sail assemblies), retained across distance unloads and server restarts
   - optional Create chain-conveyor and mechanical-belt animations (disabled by default)
   - live Create: Simulated physics ropes, using the server strand points and streamed segment transforms
   - live Create: Simulated springs, mirroring the renderer's Bézier curve across world/Sable endpoints
   - generic restore-on-launch for persistent scene providers (position, rotation, scale and mesh version)
   - generic history eligibility for persistent scene providers
   - trains/bogeys
   - moving Copycats / Create Connected material support
   - procedural and dynamic-texture adapters
   - config-driven moving tint rules

2. **`bluemap-compat-*.jar`** — unified native BlueMap compatibility addon
   - config-driven wildcard tint and model-alias rules
   - hot-reloaded server-local compatibility rules
   - Copycats+ / Create Connected copied-material adapter
   - Bits & Bobs girders and connected/diagonal fence-wall adapter
   - TrafficCraft block-entity decoding and dynamic sign textures

The former Copycats, foliage and TrafficCraft compatibility artifacts are consolidated into
`bluemap-compat`. Specialized Java still exists where needed, but it is organized as an
adapter inside the single addon rather than published as another JAR.

## Config-driven compatibility

Built-in rules live in `compat/builtin/`. Server-local additions and overrides go in:

```text
config/bluemap3d/compat/*.json
```

On startup the bundle now creates:

- `supported-defaults.generated.json` — regenerated reference showing the exact built-in
  rules and feature flags shipped by the installed build; the loader intentionally ignores it.
- `local.json` — created once and intended for your own additions/overrides.

External active JSON files hot-reload approximately every five seconds. Existing static map
tiles still need to be rerendered after a visual rule changes.

Rules support:

- wildcard block matching (`*`, `?`);
- multiple include patterns and exclusions;
- blockstate property matching;
- priorities;
- stable rule IDs, allowing a local rule to replace a built-in rule;
- separate `terrain` and `moving` scopes;
- no tint / fixed RGB / NBT-or-adapter-backed palette tint;
- wildcard moving-model namespace include/exclude policy;
- moving runtime feature flags, including `simulated.ropeRendering` and `simulated.springRendering` (enabled by default).

Example:

```json
{
  "schemaVersion": 1,
  "id": "my-mod",
  "moving": {
    "modelNamespaces": {
      "include": ["my_mod", "my_mod_*"],
      "exclude": []
    }
  },
  "rules": [
    {
      "id": "my-mod-autumn-leaves",
      "priority": 100,
      "scope": ["terrain", "moving"],
      "match": {
        "blocks": ["my_mod:*_autumn_leaves"]
      },
      "tint": {
        "type": "none"
      }
    }
  ]
}
```

See [compat/README.md](compat/README.md) and [compat/schema.json](compat/schema.json).

### Built-in migrations

The first config migration deliberately covers real existing compatibility code:

- Quark blossom foliage uses three wildcard families instead of individual IDs.
- Dynamic Trees fixed/untinted foliage rules are declarative.
- TrafficCraft's 1,292 generated asphalt/concrete pattern IDs are represented by four
  wildcard patterns, with palette data in JSON rather than Java.
- `copycats` and `create_connected` moving-model namespace exceptions are config,
  not literals in the Create provider.

Create: Simulated ropes use a small optional runtime adapter backed by the reusable
`DynamicModelSegment` scene-object capability. Each physics edge keeps one stable mesh
while BlueMap3D streams midpoint, orientation and scale. Rope/spring stretching therefore
does not re-bake geometry, and a small overlap prevents cracks between adjacent segments.
The Simulated-specific adapters are reflection-based, so Simulated is not a
hard dependency of the bundle.

TrafficCraft signs remain a small adapter because BlueMap 5.7 cannot express dynamic
server-side sign PNG loading as data. Copycats remains specialized because its renderer
decodes per-part copied materials and custom geometry.

## Repository layout

```text
compat/
  schema.json
  README.md
  builtin/
    core.json
    foliage.json
    trafficcraft.json

addon-compat/
  src/main/java/...           generic BlueMap rule engine + small adapters
  src/main/resources/...      BlueMap addon metadata

overrides/
  core/...                    maintained BlueMap3D source overrides
  addon-create/...            live Create machinery providers

patches/
  0015-base.patch             pinned upstream base patch set

scripts/
  patch-upstream.py           deterministic upstream source modifications
  generate-static-resources.py
  package-dist.py

build.sh                      orchestration only
.github/workflows/build.yml   CI
```

## Build

Requires Java 21, Git, Bash and Python 3. The build runs the stdlib Python test suite and
compatibility-rule validation before patching upstream.

```bash
./build.sh
```

Artifacts are written to `dist/`. `BUILD_INFO.json` records the release metadata and
patch-repository commit, while `MANIFEST.json` records SHA-256 hashes. The distribution
also includes the pinned upstream LGPL license.

The build always clones the pinned upstream BlueMap3D commit, applies the base patch,
copies maintained overrides and shared compatibility rules, runs the deterministic patch
script, builds, and packages the result.

### Releases

`.github/workflows/release.yml` publishes immutable GitHub Releases. Pushing a
`vMAJOR.MINOR.PATCH` (or SemVer prerelease) tag builds and publishes that exact version.
The workflow can also be run manually from the default branch: choose a patch/minor/major
bump, or provide an exact SemVer. Manual releases build successfully before the workflow
creates the tag and release, and refuse to reuse an existing tag/version.

Non-release builds never reuse the release version: they include the short commit SHA as a
`-dev.<sha>` suffix. Re-running the same commit intentionally produces the same artifact
identity; a different commit produces a different version.

## Installation

Install the bundle as a normal mod:

```text
mods/
  bluemap3d-bundle-*.jar
```

Install the unified native BlueMap addon in:

```text
config/bluemap/packs/
  bluemap-compat-*.jar
```

Restart BlueMap/the server after changing addon JARs. External JSON compatibility rules
do not require a JAR rebuild or server restart, but static terrain needs a rerender to
reflect visual changes.

BlueMap3D stores last-known Sable child-contraption snapshots in
`config/bluemap3d/cache/sable-child-contraptions.nbt`. This is generated runtime cache
data, not user configuration. It allows Aeronautics/Create child contraptions to remain
visible after their live entity unloads and after a normal server restart without
force-loading the Sable plot.

## Generic scene-object lifecycle

`SceneObjectProvider` now defaults to `SceneObjectLifecycle.PERSISTENT`. Core, rather
than each integration, owns last-known-state persistence. The generated cache is:

```text
config/bluemap3d/cache/scene-objects.json
```

Persistent snapshots include provider/object identity, dimension, label, geometry version,
position, rotation and scale. A snapshot is committed only after the matching BM3D mesh
has been published, so startup restoration can reuse that mesh with
`canBakeGeometry() == false` without force-loading chunks, entities or Sable plots.

A provider returning no object is treated as temporary unavailability, not deletion.
Providers report positive destruction/disassembly through `deletedObjectIds()`. Providers
that are intentionally transient can opt into `LIVE_ONLY`; the Create belt and conveyor
animation overlays do this.

Sable sub-level removal is one such positive lifecycle event. A sub-level removed with
`SubLevelRemovalReason.REMOVED` is deleted from both the Sable snapshot cache and generic
scene persistence; `UNLOADED` remains restorable. Generic scene cache format v4 performs
a one-time purge of older `sable_ships` snapshots so orphan ghosts created by previous
builds are removed automatically on upgrade.

Create: Simulated ropes use the same distinction. `ServerLevelRopeManager.removeStrand()`
is used for both chunk unload and destruction, so BlueMap3D deliberately does not treat it
as deletion. Instead, `RopeStrandHolderBehavior.destroyRope()` queues positive destruction
of that rope UUID. The next provider publish declares the rope's `<uuid>/` child family
authoritatively empty, removing every persisted segment and knot. Scene cache format v5
purges pre-fix rope snapshots once so existing rope ghosts are cleaned automatically.

On the first upgrade from an older build, core can seed the generic cache from the previous
`entities3d.json` feed. The older Sable-child cache is retained temporarily as a migration
source, not as the primary persistence architecture.

History consumers can inspect the same lifecycle and record every provider whose lifecycle
has `recordHistory() == true`, so new persistent Create/Sable object types do not require
a new history allow-list entry.

## Design rule

Prefer this order when adding support for another mod:

1. **Wildcard config rule** — if an existing generic concept is enough.
2. **Reusable generic capability** — if the concept is broadly useful across mods.
3. **Small adapter** — only for mod-specific runtime data or rendering semantics.

The goal is to avoid an addon-per-mod architecture.


## Migrating from 1.0.28

Remove the old native addon JARs before installing the current release:

```text
config/bluemap/packs/bluemap-copycats-compat-1.0.28.jar
config/bluemap/packs/bluemap-trafficcraft-compat-1.0.28.jar
config/bluemap/packs/bluemap-foliage-compat-1.0.28.jar
```

Replace them with the single `bluemap-compat-<version>.jar`. Keeping the old addons installed
would register duplicate renderer/block-entity hooks.


## External BlueMap asset pack

The existing `fpbcraft-bluemap-1.21.1-aeronautics-deep-seas-weathering.zip` is still
needed for now.

The Simulated rope adapter uses `simulated:block/rope/rope`, so this pack currently also
supplies the rope model/texture to BlueMap's asset index.

The compatibility addon replaces rendering/tint/decoder behavior, but it does not currently
extract arbitrary nested third-party assets into BlueMap's resource-pack index. BlueMap 5.7
scans top-level JARs in `mods/`; the FPBCRAFT pack also exposes Aeronautics' nested
`aeronautics`, `simulated` and `offroad` assets, plus Deep Seas and Immersive Weathering
assets in a form BlueMap can consume.

Do not remove that pack yet. A future compatibility capability can replace it by explicitly
indexing/extracting nested mod resources.
