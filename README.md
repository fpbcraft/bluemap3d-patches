# BlueMap3D Patches

Compatibility patches and BlueMap addons used by the FPBCRAFT Minecraft 1.21.1 server.

## Supported baseline

- Minecraft 1.21.1
- NeoForge
- **BlueMap 5.7**
- BlueMap3D upstream commit `f9a027de06f49384b86867b5c58b3630d29b1c9f`

The project intentionally targets BlueMap 5.7. Do not assume compatibility with the later BlueMap 5.23 experiments.

## What this repository builds

The build produces four installable artifacts:

1. **Patched BlueMap3D bundle**
   - persistent Sable/Create moving objects
   - projects Create child contraptions inside Sable ships (including Aeronautics propeller bearings) from hidden plot space into the ship's world pose
   - live Create chain-conveyor overlays driven by the real server kinetic speed; both parallel chain runs animate together, zero RPM is stationary, and the animation rate follows Create's chain travel rate
   - live Create mechanical-belt surfaces driven by each belt's real signed kinetic speed; unpowered belts stay static and powered belts scroll in the same direction/rate as Create
   - Create train/bogey compatibility
   - Copycats+ and Create: Connected copied-material rendering on moving contraptions
   - Bits & Bobs girder struts on moving objects
   - diagonal fence/wall model support for moving objects

2. **BlueMap Copycats Compat addon**
   - installed in `config/bluemap/packs/`
   - static Copycats+ support
   - static Create: Connected support
   - Bits & Bobs girder struts
   - connected/diagonal fence and wall support while preserving each block's original material/model

3. **BlueMap TrafficCraft Compat addon**
   - installed in `config/bluemap/packs/`
   - restores TrafficCraft's client-side painted-block tinting from block-entity NBT
   - supports road patterns/slopes, barriers, cones, bollards, barrels, guardrails, reflectors, paint buckets and colorable sign/light bases
   - uses TrafficCraft's exact paint palette and per-block default colors
   - renders TrafficCraft traffic-sign artwork from `SignTexture`
   - explicitly imports TrafficCraft's BER-only built-in sign PNGs into BlueMap's texture gallery
   - loads custom sign PNG data from `world/data/trafficcraft_signs/*.nbt`
   - supports built-in sign textures and custom `misc` sign reverse-side backing
   - the patched BlueMap3D bundle applies the same sign artwork to moving contraptions/ships

4. **BlueMap Foliage Compat addon**
   - installed in `config/bluemap/packs/`
   - restores fixed client-side foliage colors that BlueMap 5.7 otherwise replaces with generic biome foliage tint
   - covers Quark blossom leaves/carpets/hedges, including Sunny Trumpet
   - covers Dynamic Trees cherry/azalea, plus the fixed birch and spruce foliage colors

The BlueMap addon compiles directly against BlueMap **5.7**.

## Build

Requires Java 21, Git, Bash and Python 3.

```bash
./build.sh
```

Artifacts are written to `dist/`.

The build clones the pinned BlueMap3D upstream commit, applies `patches/0015-base.patch`, overlays the maintained Java sources from `overrides/`, adds the native BlueMap compatibility addon, and then builds both artifacts.

## Layout

```text
patches/
  0015-base.patch            Base BlueMap3D patch set

overrides/
  core/...                   Maintained BlueMap3D source overrides
  addon-create/...           Live Create provider overrides (chain conveyors)

addon-copycats/
  src/...                    Native BlueMap 5.7 static-terrain addon

addon-trafficcraft/
  src/...                    TrafficCraft paint + dynamic sign compatibility

addon-foliage/
  src/...                    Quark / Dynamic Trees foliage-color compatibility

build.sh                     Reproducible assembly/build script
.github/workflows/build.yml  CI build
```

## Installation

- Put the generated `bluemap3d-bundle-*.jar` in the server's normal mods directory.
- Put `bluemap-copycats-compat-*.jar` in `config/bluemap/packs/`.
- Put `bluemap-trafficcraft-compat-*.jar` in `config/bluemap/packs/`.
- Put `bluemap-foliage-compat-*.jar` in `config/bluemap/packs/`.
- Restart BlueMap/the server and force-update affected static map regions when changing static terrain compatibility.

## Notes

The native addons use BlueMap 5.7 core APIs and a small amount of BlueMap 5.7 internal resource-pack state to preserve original models while adding compatibility behavior. The Create machinery overlays are additive: BlueMap's static chain/belt geometry remains underneath as a fallback, while BlueMap3D reads the live Create block entities and animates only when the kinetic network is moving. Chain conveyors use repeated loop geometry; belts use isolated per-node UV scrolling so the belt itself stays fixed while its texture moves. A BlueMap upgrade should therefore be treated as an explicit compatibility migration, not an automatic version bump.
