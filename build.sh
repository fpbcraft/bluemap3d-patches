#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
WORK="$ROOT/.tmp-bluemap3d"
DIST="$ROOT/dist"
UPSTREAM_COMMIT="f9a027de06f49384b86867b5c58b3630d29b1c9f"
VERSION="1.0.28"

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
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/ChainConveyorSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/ChainConveyorSource.java
mkdir -p core/src/main/java/dev/duzo/bluemap3d/api
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/api/ModelAttachment.java" \
   core/src/main/java/dev/duzo/bluemap3d/api/ModelAttachment.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/BakedMesh.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/BakedMesh.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/Bm3dWriter.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/Bm3dWriter.java
mkdir -p addon-create/src/main/java/dev/duzo/bluemap3d/create
cp "$ROOT/overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/ChainConveyorProvider.java" \
   addon-create/src/main/java/dev/duzo/bluemap3d/create/ChainConveyorProvider.java
cp "$ROOT/overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/BeltProvider.java" \
   addon-create/src/main/java/dev/duzo/bluemap3d/create/BeltProvider.java

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

# Create contraptions can exist inside Sable's hidden plot space (Aeronautics propellers).
# Compile against Sable so the Create provider can project those child contraptions through
# their owning ship's pose before publishing them to BlueMap3D.
p = Path("addon-create/build.gradle")
s = p.read_text()
needle = '    compileOnly "net.createmod.ponder:ponder-neoforge:1.0.82+mc1.21.1"\n'
if needle not in s:
    raise SystemExit("addon-create Sable dependency insertion point not found")
s = s.replace(
    needle,
    needle
        + '\n    compileOnly("dev.ryanhcode.sable:sable-neoforge-${minecraft_version}:${sable_version}") { transitive = false }\n'
        + '    compileOnly "dev.ryanhcode.sable-companion:sable-companion-common-${minecraft_version}:${sable_companion_version}"\n',
    1,
)
p.write_text(s)

replace(
    "addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java",
    "GEOMETRY_REVISION + 15",
    "GEOMETRY_REVISION + 32",
)
replace(
    "addon-sable/src/main/java/dev/duzo/bluemap3d/sable/ShipProvider.java",
    "mix(mix(hash, sections), 15L)",
    "mix(mix(hash, sections), 32L)",
)
replace(
    "addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java",
    'if (!"copycats".equals(namespace)) {',
    'if (!"copycats".equals(namespace) && !"create_connected".equals(namespace)) {',
)

p = Path("addon-create/src/main/java/dev/duzo/bluemap3d/create/ContraptionProvider.java")
s = p.read_text()

import_needle = 'import dev.duzo.bluemap3d.api.SceneObjectProvider;'
if import_needle not in s:
    raise SystemExit("ContraptionProvider Sable import insertion point not found")
s = s.replace(
    import_needle,
    import_needle
        + '\nimport dev.ryanhcode.sable.Sable;'
        + '\nimport dev.ryanhcode.sable.sublevel.SubLevel;',
    1,
)

trace_needle = '    private final Set<String> unrotatable = ConcurrentHashMap.newKeySet();'
if trace_needle not in s:
    raise SystemExit("ContraptionProvider Sable trace insertion point not found")
s = s.replace(
    trace_needle,
    trace_needle
        + '\n\n    /** Contraption classes already reported as projected out of a Sable ship. */'
        + '\n    private final Set<String> sableProjected = ConcurrentHashMap.newKeySet();',
    1,
)

pose_needle = '''        Vec3 position = entity.getAnchorVec().add(PIVOT);
        Quaternionf rotation = rotationOf(entity);'''
if pose_needle not in s:
    raise SystemExit("ContraptionProvider pose insertion point not found")
pose_replacement = '''        Vec3 position = entity.getAnchorVec().add(PIVOT);
        Quaternionf rotation = rotationOf(entity);

        // Aeronautics propeller bearings are ordinary Create contraption entities, but
        // when mounted on a Sable ship their entity coordinates live in Sable's hidden
        // plot. The ship itself is published separately in world space, so publishing
        // this raw Create transform strands the entire rotor in the hidden plot.
        //
        // Compose child -> plot (Create) with plot -> world (Sable):
        //   worldPos = shipPose.transformPosition(createAnchor)
        //   worldRot = shipOrientation * createRotation
        SubLevel containingSubLevel = Sable.HELPER.getContaining(entity);
        if (containingSubLevel != null) {
            var pose = containingSubLevel.logicalPose();
            Vec3 localPosition = position;
            position = pose.transformPosition(localPosition);

            var parentRotation = pose.orientation();
            rotation = new Quaternionf(
                    (float) parentRotation.x(), (float) parentRotation.y(),
                    (float) parentRotation.z(), (float) parentRotation.w())
                    .mul(rotation);

            String traceKey = entity.getClass().getName();
            if (sableProjected.add(traceKey)) {
                LOGGER.info(
                        "SABLE-CONTRAPTION-DIAG type={} entity={} sublevel={} localPos={} worldPos={}",
                        traceKey, entity.getUUID(), containingSubLevel.getUniqueId(),
                        localPosition, position);
            }
        }'''
s = s.replace(pose_needle, pose_replacement, 1)
p.write_text(s)

# Register the live chain-conveyor overlay provider beside the existing bearing provider.
p = Path("addon-create/src/main/java/dev/duzo/bluemap3d/create/CreateAddon.java")
s = p.read_text()
field_needle = '    private final BearingProvider bearings = new BearingProvider(chunks);'
if field_needle not in s:
    raise SystemExit("CreateAddon chain provider field insertion point not found")
s = s.replace(
    field_needle,
    field_needle
        + '\n    private final ChainConveyorProvider chainConveyors = new ChainConveyorProvider(chunks);'
        + '\n    private final BeltProvider belts = new BeltProvider(chunks);',
    1,
)
register_needle = '        BlueMap3D.register(bearings);'
if register_needle not in s:
    raise SystemExit("CreateAddon chain provider registration point not found")
s = s.replace(
    register_needle,
    register_needle
        + '\n        BlueMap3D.register(chainConveyors);'
        + '\n        BlueMap3D.register(belts);',
    1,
)
clear_needle = '        bearings.clear();'
if clear_needle not in s:
    raise SystemExit("CreateAddon chain provider clear point not found")
s = s.replace(
    clear_needle,
    clear_needle
        + '\n        chainConveyors.clear();'
        + '\n        belts.clear();',
    1,
)
p.write_text(s)

# Translate ModelAttachment.Loop into the existing fixed-size BM3D node trailer.
p = Path("core/src/main/java/dev/duzo/bluemap3d/bake/VolumeMesher.java")
s = p.read_text()
loop_needle = '''            case ModelAttachment.Rate rate -> new BakedMesh.Node(
                    BakedMesh.KIND_RATE, indexStart, indexCount,
                    pivotFor(rate.pivot(), matrix, attachment, volumePivot),
                    axisFor(rate.axis(), matrix),
                    // No radius or period: a constant rate is not a length, so the
                    // transform's scale has nothing to act on.
                    0f, 0f, rate.radiansPerSecond());
'''
if loop_needle not in s:
    raise SystemExit("VolumeMesher loop-motion insertion point not found")
loop_replacement = '''            case ModelAttachment.Rate rate -> new BakedMesh.Node(
                    BakedMesh.KIND_RATE, indexStart, indexCount,
                    pivotFor(rate.pivot(), matrix, attachment, volumePivot),
                    axisFor(rate.axis(), matrix),
                    // No radius or period: a constant rate is not a length, so the
                    // transform's scale has nothing to act on.
                    0f, 0f, rate.radiansPerSecond());
            case ModelAttachment.Loop loop -> new BakedMesh.Node(
                    BakedMesh.KIND_LOOP, indexStart, indexCount,
                    new float[]{0f, 0f, 0f},
                    axisFor(loop.axis(), matrix),
                    0f, loop.period() / 16f * s, loop.blocksPerSecond());
            case ModelAttachment.UvScroll scroll -> new BakedMesh.Node(
                    BakedMesh.KIND_UV_SCROLL, indexStart, indexCount,
                    new float[]{0f, 0f, 0f},
                    new float[]{scroll.axis().x(), scroll.axis().y(), 0f},
                    0f, scroll.phase(), scroll.cyclesPerSecond());
'''
s = s.replace(loop_needle, loop_replacement, 1)
p.write_text(s)

replace(
    "core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js",
    'var BUILD = "core-history-15-special-models";',
    'var BUILD = "core-history-32-belts-dual-chain";',
)
replace("gradle.properties", "version=1.0.9", "version=1.0.28")

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
        + '\nimport dev.duzo.bluemap3d.bake.SymmetricSailSource;'
        + '\nimport dev.duzo.bluemap3d.bake.ChainConveyorSource;',
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
        + '\n                sources.add(new ChainConveyorSource(packs));'
        + '\n                sources.add(new TrafficCraftSignSource(packs));'
        + '\n                sources.add(new TrafficCraftTintSource(packs));',
    1,
)

needle = 'LOGGER.info("BlueMap3D loaded. Waiting for BlueMap and at least one addon.");'
if needle not in s:
    raise SystemExit("BlueMap3D startup marker insertion point not found")
s = s.replace(
    needle,
    needle + '\n        LOGGER.info("BlueMap3D FPB patches 1.0.28 active; BlueMap target is 5.7.");',
    1,
)
p.write_text(s)

# Browser support for KIND_LOOP. Layout stays fixed-width; v6 only adds the new semantic.
p = Path("core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js")
s = p.read_text()
kind_needle = '    var KIND_RATE = 3;'
if kind_needle not in s:
    raise SystemExit("web loop kind insertion point not found")
s = s.replace(
    kind_needle,
    kind_needle + '\n    var KIND_LOOP = 4;\n    var KIND_UV_SCROLL = 5;',
    1,
)

s = s.replace(
    'if (version < 1 || version > 5) {',
    'if (version < 1 || version > 7) {',
    1,
)

uv_attr_needle = '''            nodeGeometry.setAttribute("position", position);
            nodeGeometry.setAttribute("uv", geometry.attributes.uv);
            nodeGeometry.setAttribute("color", geometry.attributes.color);
'''
if uv_attr_needle not in s:
    raise SystemExit("web UV node attribute insertion point not found")
uv_attr_replacement = '''            nodeGeometry.setAttribute("position", position);
            if (node.kind === KIND_UV_SCROLL) {
                var sourceUv = geometry.attributes.uv;
                var uvCopy = new Float32Array(sourceUv.array);
                nodeGeometry.setAttribute("uv", new THREE.BufferAttribute(uvCopy, 2));

                var seenUv = Object.create(null);
                var uvVertices = [];
                var uvBase = [];
                var minU = Infinity, maxU = -Infinity;
                var minV = Infinity, maxV = -Infinity;
                var uvEnd = node.indexStart + node.indexCount;
                for (var uvIndex = node.indexStart; uvIndex < uvEnd; uvIndex++) {
                    var vertexIndex = index.array[uvIndex];
                    if (seenUv[vertexIndex] === true) {
                        continue;
                    }
                    seenUv[vertexIndex] = true;
                    var baseU = sourceUv.array[vertexIndex * 2];
                    var baseV = sourceUv.array[vertexIndex * 2 + 1];
                    uvVertices.push(vertexIndex);
                    uvBase.push(baseU, baseV);
                    minU = Math.min(minU, baseU);
                    maxU = Math.max(maxU, baseU);
                    minV = Math.min(minV, baseV);
                    maxV = Math.max(maxV, baseV);
                }
                node.uvVertices = uvVertices;
                node.uvBase = uvBase;
                node.uvSpanU = isFinite(minU) ? maxU - minU : 0;
                node.uvSpanV = isFinite(minV) ? maxV - minV : 0;
            } else {
                nodeGeometry.setAttribute("uv", geometry.attributes.uv);
            }
            nodeGeometry.setAttribute("color", geometry.attributes.color);
'''
s = s.replace(uv_attr_needle, uv_attr_replacement, 1)

material_needle = '    var materialCache = Object.create(null);'
if material_needle not in s:
    raise SystemExit("web UV helper insertion point not found")
uv_helper = '''    function scrollNodeUvs(node, geometry, phase) {
        if (!node.uvVertices || !geometry.attributes.uv) {
            return;
        }

        phase = phase - Math.floor(phase);
        var du = node.axis.x * node.uvSpanU * phase;
        var dv = node.axis.y * node.uvSpanV * phase;
        var array = geometry.attributes.uv.array;

        for (var i = 0; i < node.uvVertices.length; i++) {
            var vertexIndex = node.uvVertices[i];
            array[vertexIndex * 2] = node.uvBase[i * 2] + du;
            array[vertexIndex * 2 + 1] = node.uvBase[i * 2 + 1] + dv;
        }

        geometry.attributes.uv.needsUpdate = true;
    }

'''
s = s.replace(material_needle, uv_helper + material_needle, 1)

live_rate = '''                } else if (node.kind === KIND_RATE) {
                    /* Driven by wall-clock time, not by "value" (the odometer) - a
'''
if live_rate not in s:
    raise SystemExit("web live loop insertion point not found")
live_loop = '''                } else if (node.kind === KIND_UV_SCROLL) {
                    group.position.set(0, 0, 0);
                    group.quaternion.set(0, 0, 0, 1);
                    var uvMesh = group.children[0];
                    if (uvMesh && uvMesh.geometry) {
                        scrollNodeUvs(
                            node,
                            uvMesh.geometry,
                            node.period + node.rate * performance.now() / 1000);
                    }
                } else if (node.kind === KIND_LOOP) {
                    var loopNow = performance.now();
                    var loopLast = entry.rateLastTime[i];
                    var loopDt = loopLast === null ? 0 : (loopNow - loopLast) / 1000;
                    if (loopDt < 0 || loopDt > MAX_RATE_DT_SECONDS) {
                        loopDt = 0;
                    }
                    entry.rateLastTime[i] = loopNow;
                    entry.rateAngles[i] += node.rate * loopDt;
                    var loopPhase = node.period > 0
                        ? entry.rateAngles[i] % node.period : 0;
                    group.position.set(
                        node.axis.x * loopPhase,
                        node.axis.y * loopPhase,
                        node.axis.z * loopPhase);
                    group.quaternion.set(0, 0, 0, 1);
                } else if (node.kind === KIND_RATE) {
                    /* Driven by wall-clock time, not by "value" (the odometer) - a
'''
s = s.replace(live_rate, live_loop, 1)

replay_rate = '''            } else if (node.kind === KIND_RATE) {
                var angle = node.rate * timeSeconds;
'''
if replay_rate in s:
    replay_loop = '''            } else if (node.kind === KIND_UV_SCROLL) {
                group.position.set(0, 0, 0);
                group.quaternion.set(0, 0, 0, 1);
                var uvMesh = group.children[0];
                if (uvMesh && uvMesh.geometry) {
                    scrollNodeUvs(node, uvMesh.geometry, node.period + node.rate * timeSeconds);
                }
            } else if (node.kind === KIND_LOOP) {
                var loopPhase = node.period > 0
                    ? (node.rate * timeSeconds) % node.period : 0;
                group.position.set(
                    node.axis.x * loopPhase,
                    node.axis.y * loopPhase,
                    node.axis.z * loopPhase);
                group.quaternion.set(0, 0, 0, 1);
            } else if (node.kind === KIND_RATE) {
                var angle = node.rate * timeSeconds;
'''
    s = s.replace(replay_rate, replay_loop, 1)

s = s.replace(
    'if (nodes[i].kind === KIND_RATE) {',
    'if (nodes[i].kind === KIND_RATE || nodes[i].kind === KIND_LOOP) {',
    1,
)

# The loop may translate by almost one full period beyond its baked pose. Inflate
# the animated child sphere so frustum culling never drops an endpoint mid-cycle.
sphere_needle = '''            nodeGeometry.boundingSphere = new THREE.Sphere(node.pivot.clone(), Math.sqrt(maxDistSq));
'''
if sphere_needle not in s:
    raise SystemExit("web loop bounding sphere insertion point not found")
sphere_replacement = '''            var nodeRadius = Math.sqrt(maxDistSq);
            if (node.kind === KIND_LOOP && node.period > 0) {
                nodeRadius += node.period;
            }
            nodeGeometry.boundingSphere = new THREE.Sphere(node.pivot.clone(), nodeRadius);
'''
s = s.replace(sphere_needle, sphere_replacement, 1)
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

with zipfile.ZipFile(dist / "bluemap3d-patches-1.0.28.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(dist.rglob("*")):
        if p.is_file() and p.name != "bluemap3d-patches-1.0.28.zip":
            z.write(p, p.relative_to(dist))
PY

ls -lah "$DIST"