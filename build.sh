#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
WORK="$ROOT/.tmp-bluemap3d"
DIST="$ROOT/dist"
UPSTREAM_COMMIT="f9a027de06f49384b86867b5c58b3630d29b1c9f"
VERSION="1.0.24"

rm -rf "$WORK" "$DIST"
mkdir -p "$WORK" "$DIST"

git clone https://github.com/duzos/bluemap3d.git "$WORK/src"
cd "$WORK/src"
git checkout "$UPSTREAM_COMMIT"

git apply "$ROOT/patches/0015-base.patch"

# Maintained moving/live compatibility overrides.
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/CopycatsSpecialSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/CopycatsSpecialSource.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/BitsNBobsStrutSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/BitsNBobsStrutSource.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/ResourcePackSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/ResourcePackSource.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/ProceduralBlockSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/ProceduralBlockSource.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/TrafficCraftTintSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/TrafficCraftTintSource.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/TrafficCraftSignSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/TrafficCraftSignSource.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/SymmetricSailSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/SymmetricSailSource.java

# Native BlueMap 5.7 static-terrain addons. Keep these separate from bundleJar.
cp -R "$ROOT/addon-copycats" ./addon-copycats
cp -R "$ROOT/addon-trafficcraft" ./addon-trafficcraft
cp -R "$ROOT/addon-foliage" ./addon-foliage

python3 - <<'PY'
from pathlib import Path

def replace(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"expected text not found in {path}: {old!r}")
    p.write_text(s.replace(old, new))

p = Path("settings.gradle")
s = p.read_text()
needle = 'include("addon-create")'
if needle not in s:
    raise SystemExit("settings.gradle addon insertion point not found")
s = s.replace(
    needle,
    needle + '\ninclude("addon-copycats")\ninclude("addon-trafficcraft")\ninclude("addon-foliage")',
    1,
)
p.write_text(s)

replace(
    "addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java",
    "GEOMETRY_REVISION + 15",
    "GEOMETRY_REVISION + 29",
)
replace(
    "addon-sable/src/main/java/dev/duzo/bluemap3d/sable/ShipProvider.java",
    "mix(mix(hash, sections), 15L)",
    "mix(mix(hash, sections), 29L)",
)
replace(
    "addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java",
    'if (!"copycats".equals(namespace)) {',
    'if (!"copycats".equals(namespace) && !"create_connected".equals(namespace)) {',
)
replace(
    "core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js",
    'var BUILD = "core-history-15-special-models";',
    'var BUILD = "core-history-29-foliage-sails-signs";',
)
replace("gradle.properties", "version=1.0.9", "version=1.0.24")

p = Path("core/src/main/java/dev/duzo/bluemap3d/BlueMap3DMod.java")
s = p.read_text()

import_needle = 'import dev.duzo.bluemap3d.bake.BitsNBobsStrutSource;'
if import_needle not in s:
    raise SystemExit("BlueMap3DMod procedural import insertion point not found")
s = s.replace(
    import_needle,
    import_needle
        + '\nimport dev.duzo.bluemap3d.bake.ProceduralBlockSource;'
        + '\nimport dev.duzo.bluemap3d.bake.TrafficCraftTintSource;'
        + '\nimport dev.duzo.bluemap3d.bake.TrafficCraftSignSource;'
        + '\nimport dev.duzo.bluemap3d.bake.SymmetricSailSource;',
    1,
)

source_needle = '                sources.add(new BitsNBobsStrutSource(packs));'
if source_needle not in s:
    raise SystemExit("BlueMap3DMod procedural source insertion point not found")
s = s.replace(
    source_needle,
    source_needle
        + '\n                sources.add(new ProceduralBlockSource(packs));'
        + '\n                sources.add(new SymmetricSailSource(packs));'
        + '\n                sources.add(new TrafficCraftSignSource(packs));'
        + '\n                sources.add(new TrafficCraftTintSource(packs));',
    1,
)

needle = 'LOGGER.info("BlueMap3D loaded. Waiting for BlueMap and at least one addon.");'
if needle not in s:
    raise SystemExit("BlueMap3D startup marker insertion point not found")
s = s.replace(
    needle,
    needle + '\n        LOGGER.info("BlueMap3D FPB patches 1.0.24 active; BlueMap target is 5.7.");',
    1,
)
p.write_text(s)
PY

# Generate exhaustive static dispatch resources at build time.
python3 - <<'PY'
from pathlib import Path
import json

root = Path("addon-copycats/src/main/resources/assets")

copycats = [
    "wrapped_copycat",
    "copycat_block", "copycat_beam", "copycat_board",
    "copycat_wooden_button", "copycat_stone_button",
    "copycat_byte", "copycat_byte_panel",
    "copycat_fence", "copycat_fence_gate", "copycat_ghost_block",
    "copycat_half_layer", "copycat_vertical_half_layer", "copycat_stacked_half_layer",
    "copycat_half_panel", "copycat_ladder", "copycat_layer",
    "copycat_wooden_pressure_plate", "copycat_stone_pressure_plate",
    "copycat_heavy_weighted_pressure_plate", "copycat_light_weighted_pressure_plate",
    "copycat_slab", "copycat_slice", "copycat_corner_slice",
    "copycat_stairs", "copycat_vertical_stairs",
    "copycat_trapdoor", "copycat_iron_trapdoor",
    "copycat_vertical_slice", "copycat_vertical_step", "copycat_wall",
    "copycat_slope", "copycat_vertical_slope", "copycat_slope_layer",
    "copycat_shaft", "copycat_cogwheel", "copycat_large_cogwheel",
    "copycat_fluid_pipe", "copycat_glass_fluid_pipe",
    "copycat_door", "copycat_iron_door",
    "copycat_pane", "copycat_sliding_door", "copycat_folding_door",
    "copycat_flat_pane",
]

connected = [
    "copycat_slab", "copycat_block", "copycat_beam", "copycat_vertical_step",
    "copycat_stairs", "wrapped_copycat_stairs",
    "copycat_fence", "wrapped_copycat_fence",
    "copycat_wall", "wrapped_copycat_wall",
    "copycat_fence_gate", "wrapped_copycat_fence_gate",
    "copycat_board",
]

def write_dispatch(namespace, names, renderer):
    blockstates = root / namespace / "blockstates"
    blockstates.mkdir(parents=True, exist_ok=True)
    payload = {
        "variants": {
            "": {
                "renderer": renderer,
                "model": "bluemap_copycats:block/placeholder",
            }
        }
    }
    for name in names:
        (blockstates / f"{name}.json").write_text(json.dumps(payload, indent=2) + "\n")

    properties = {
        f"{namespace}:{name}": {
            "occluding": False,
            "culling": False,
            "cullingIdentical": False,
        }
        for name in names
    }
    (root / namespace / "blockProperties.json").write_text(
        json.dumps(properties, indent=2) + "\n"
    )

write_dispatch("copycats", copycats, "bluemap_copycats:terrain")
write_dispatch("create_connected", connected, "bluemap_copycats:terrain")
write_dispatch(
    "bits_n_bobs",
    ["girder_strut", "weathered_girder_strut", "cable_girder_strut"],
    "bluemap_copycats:bits_n_bobs_girder",
)
PY

./gradlew clean bundleJar :addon-copycats:build :addon-trafficcraft:build :addon-foliage:build

cp build/libs/bluemap3d-bundle-*.jar "$DIST/"

ADDON_JAR="$(find addon-copycats/build/libs -maxdepth 1 -type f -name '*.jar' \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n 1)"
if [[ -z "$ADDON_JAR" ]]; then
  echo "addon-copycats main jar not found" >&2
  exit 1
fi
cp "$ADDON_JAR" "$DIST/bluemap-copycats-compat-$VERSION.jar"

TRAFFICCRAFT_ADDON_JAR="$(find addon-trafficcraft/build/libs -maxdepth 1 -type f -name '*.jar' \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n 1)"
if [[ -z "$TRAFFICCRAFT_ADDON_JAR" ]]; then
  echo "addon-trafficcraft main jar not found" >&2
  exit 1
fi
cp "$TRAFFICCRAFT_ADDON_JAR" "$DIST/bluemap-trafficcraft-compat-$VERSION.jar"

FOLIAGE_ADDON_JAR="$(find addon-foliage/build/libs -maxdepth 1 -type f -name '*.jar' \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n 1)"
if [[ -z "$FOLIAGE_ADDON_JAR" ]]; then
  echo "addon-foliage main jar not found" >&2
  exit 1
fi
cp "$FOLIAGE_ADDON_JAR" "$DIST/bluemap-foliage-compat-$VERSION.jar"

mkdir -p "$DIST/source-addon-copycats"
cp -R addon-copycats/* "$DIST/source-addon-copycats/"
mkdir -p "$DIST/source-addon-trafficcraft"
cp -R addon-trafficcraft/* "$DIST/source-addon-trafficcraft/"
mkdir -p "$DIST/source-addon-foliage"
cp -R addon-foliage/* "$DIST/source-addon-foliage/"
printf '%s\n' "$UPSTREAM_COMMIT" > "$DIST/UPSTREAM.txt"
printf '%s\n' "BlueMap 5.7" > "$DIST/BLUEMAP_TARGET.txt"

cd "$ROOT"
python3 - <<'PY'
from pathlib import Path
import hashlib, json, zipfile

dist = Path("dist")
manifest = {}
for p in sorted(dist.iterdir()):
    if p.is_file():
        manifest[p.name] = {
            "size": p.stat().st_size,
            "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
        }
(dist / "MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n")

with zipfile.ZipFile(dist / "bluemap3d-patches-1.0.24.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(dist.rglob("*")):
        if p.is_file() and p.name != "bluemap3d-patches-1.0.24.zip":
            z.write(p, p.relative_to(dist))
PY

ls -lah "$DIST"