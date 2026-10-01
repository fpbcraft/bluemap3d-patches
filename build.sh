#!/usr/bin/env bash
set -euo pipefail

ROOT="$PWD"
WORK="$ROOT/.tmp-bluemap3d"
DIST="$ROOT/dist"
UPSTREAM_COMMIT="f9a027de06f49384b86867b5c58b3630d29b1c9f"
VERSION="1.1.1"

python3 "$ROOT/scripts/validate-compat.py"

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
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/ConfiguredRuleSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/ConfiguredRuleSource.java
mkdir -p core/src/main/java/dev/duzo/bluemap3d/compat
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/compat/CompatRegistry.java" \
   core/src/main/java/dev/duzo/bluemap3d/compat/CompatRegistry.java
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
cp "$ROOT/overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/SimulatedRopeProvider.java" \
   addon-create/src/main/java/dev/duzo/bluemap3d/create/SimulatedRopeProvider.java

# Single native BlueMap 5.7 compatibility addon. Specialized adapters (Copycats,
# TrafficCraft dynamic textures) and generic rule capabilities share this artifact.
cp -R "$ROOT/addon-compat" ./addon-compat

# One source of truth for built-in compatibility rules. Package the same rules into
# the static BlueMap addon and the moving BlueMap3D bundle.
mkdir -p addon-compat/src/main/resources/bluemap3d-compat
mkdir -p core/src/main/resources/bluemap3d-compat
cp -R "$ROOT/compat/builtin" addon-compat/src/main/resources/bluemap3d-compat/
cp -R "$ROOT/compat/builtin" core/src/main/resources/bluemap3d-compat/
cp "$ROOT/compat/schema.json" addon-compat/src/main/resources/bluemap3d-compat/schema.json
cp "$ROOT/compat/schema.json" core/src/main/resources/bluemap3d-compat/schema.json
cp "$ROOT/compat/local-template.json" addon-compat/src/main/resources/bluemap3d-compat/local-template.json
cp "$ROOT/compat/local-template.json" core/src/main/resources/bluemap3d-compat/local-template.json

python3 "$ROOT/scripts/patch-upstream.py"

# Generate exhaustive static dispatch resources at build time.
python3 "$ROOT/scripts/generate-static-resources.py"

./gradlew clean bundleJar :addon-compat:build

cp build/libs/bluemap3d-bundle-*.jar "$DIST/"

COMPAT_ADDON_JAR="$(find addon-compat/build/libs -maxdepth 1 -type f -name '*.jar' \
  ! -name '*-sources.jar' ! -name '*-javadoc.jar' | head -n 1)"
if [[ -z "$COMPAT_ADDON_JAR" ]]; then
  echo "addon-compat main jar not found" >&2
  exit 1
fi
cp "$COMPAT_ADDON_JAR" "$DIST/bluemap-compat-$VERSION.jar"

mkdir -p "$DIST/source-addon-compat"
cp -R addon-compat/* "$DIST/source-addon-compat/"
mkdir -p "$DIST/compat"
cp -R "$ROOT/compat/"* "$DIST/compat/"
printf '%s\n' "$UPSTREAM_COMMIT" > "$DIST/UPSTREAM.txt"
printf '%s\n' "BlueMap 5.7" > "$DIST/BLUEMAP_TARGET.txt"

cd "$ROOT"
python3 "$ROOT/scripts/package-dist.py"

ls -lah "$DIST"