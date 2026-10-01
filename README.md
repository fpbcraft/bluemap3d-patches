# BlueMap3D Patches

BlueMap 5.7 / BlueMap3D compatibility for the FPBCRAFT Minecraft 1.21.1 NeoForge server.

The repository is intentionally split between **declarative compatibility rules** and
**code adapters**:

- add a JSON rule when a mod only needs matching, tinting, namespace policy, or another
  supported generic behavior;
- add Java only when a mod introduces a genuinely new rendering/runtime concept.

## Supported baseline

- Minecraft 1.21.1
- NeoForge
- BlueMap **5.7**
- BlueMap3D upstream commit `f9a027de06f49384b86867b5c58b3630d29b1c9f`

Do not assume compatibility with the later BlueMap 5.23 experiments.

## Artifacts

The build produces two installable JARs:

1. **`bluemap3d-bundle-*.jar`** — patched BlueMap3D bundle
   - persistent Sable/Create moving objects
   - persistent Sable child contraptions (including Aeronautics propeller/sail assemblies), retained across distance unloads and server restarts
   - optional Create chain-conveyor and mechanical-belt animations (disabled by default)
   - live Create: Simulated physics ropes, rendered from the server-authoritative rope points
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
- moving runtime feature flags.

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

Requires Java 21, Git, Bash and Python 3.

```bash
./build.sh
```

Artifacts are written to `dist/`.

The build always clones the pinned upstream BlueMap3D commit, applies the base patch,
copies maintained overrides and shared compatibility rules, runs the deterministic patch
script, builds, and packages the result.

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

Create: Simulated ropes are enabled by default through the `simulated.ropeRendering`
runtime feature. Each physics interval is published as a rigid BlueMap3D object, so the
rope mesh is cached while its position and orientation use the normal live interpolation
path. Inactive strands keep their last server-side points until Simulated removes the
strand, which also provides the distance-unloaded fallback without force-loading chunks.

BlueMap3D stores last-known Sable child-contraption snapshots in
`config/bluemap3d/cache/sable-child-contraptions.nbt`. This is generated runtime cache
data, not user configuration. It allows Aeronautics/Create child contraptions to remain
visible after their live entity unloads and after a normal server restart without
force-loading the Sable plot.

## Design rule

Prefer this order when adding support for another mod:

1. **Wildcard config rule** — if an existing generic concept is enough.
2. **Reusable generic capability** — if the concept is broadly useful across mods.
3. **Small adapter** — only for mod-specific runtime data or rendering semantics.

The goal is to avoid an addon-per-mod architecture.


## Migrating from 1.0.28

Remove the old native addon JARs before installing 1.1.1:

```text
config/bluemap/packs/bluemap-copycats-compat-1.0.28.jar
config/bluemap/packs/bluemap-trafficcraft-compat-1.0.28.jar
config/bluemap/packs/bluemap-foliage-compat-1.0.28.jar
```

Replace them with the single `bluemap-compat-1.1.1.jar`. Keeping the old addons installed
would register duplicate renderer/block-entity hooks.


## External BlueMap asset pack

The existing `fpbcraft-bluemap-1.21.1-aeronautics-deep-seas-weathering.zip` is still
needed for now.

The compatibility addon replaces rendering/tint/decoder behavior, but it does not currently
extract arbitrary nested third-party assets into BlueMap's resource-pack index. BlueMap 5.7
scans top-level JARs in `mods/`; the FPBCRAFT pack also exposes Aeronautics' nested
`aeronautics`, `simulated` and `offroad` assets, plus Deep Seas and Immersive Weathering
assets in a form BlueMap can consume.

Do not remove that pack yet. A future compatibility capability can replace it by explicitly
indexing/extracting nested mod resources.
