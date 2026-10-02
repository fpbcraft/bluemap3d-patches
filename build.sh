#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK="$ROOT/.tmp-bluemap3d"
DIST="$ROOT/dist"

mapfile -t RELEASE_METADATA < <(python3 "$ROOT/scripts/release_metadata.py" --lines)
VERSION="${RELEASE_METADATA[0]}"
UPSTREAM_COMMIT="${RELEASE_METADATA[1]}"
BLUEMAP_TARGET="${RELEASE_METADATA[2]}"
MINECRAFT_TARGET="${RELEASE_METADATA[3]}"

python3 -m unittest discover -s "$ROOT/tests" -p 'test_*.py'
python3 "$ROOT/scripts/validate-compat.py"
python3 "$ROOT/scripts/generate-compat-shared.py" --check

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
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/compat/SharedCompatRules.java" \
   core/src/main/java/dev/duzo/bluemap3d/compat/SharedCompatRules.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/TrafficCraftSignSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/TrafficCraftSignSource.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/SymmetricSailSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/SymmetricSailSource.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/bake/ChainConveyorSource.java" \
   core/src/main/java/dev/duzo/bluemap3d/bake/ChainConveyorSource.java
mkdir -p core/src/main/java/dev/duzo/bluemap3d/api
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/api/ModelAttachment.java" \
   core/src/main/java/dev/duzo/bluemap3d/api/ModelAttachment.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/api/SceneObject.java" \
   core/src/main/java/dev/duzo/bluemap3d/api/SceneObject.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/api/SceneObjectProvider.java" \
   core/src/main/java/dev/duzo/bluemap3d/api/SceneObjectProvider.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/api/SceneObjectLifecycle.java" \
   core/src/main/java/dev/duzo/bluemap3d/api/SceneObjectLifecycle.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/api/PersistentSceneObjectProvider.java" \
   core/src/main/java/dev/duzo/bluemap3d/api/PersistentSceneObjectProvider.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/api/ScenePersistencePolicy.java" \
   core/src/main/java/dev/duzo/bluemap3d/api/ScenePersistencePolicy.java
cp "$ROOT/overrides/core/src/main/java/dev/duzo/bluemap3d/api/DynamicModelSegment.java" \
   core/src/main/java/dev/duzo/bluemap3d/api/DynamicModelSegment.java
mkdir -p core/src/test/java/dev/duzo/bluemap3d/api
cp "$ROOT/overrides/core/src/test/java/dev/duzo/bluemap3d/api/ScenePersistencePolicyTest.java" \
   core/src/test/java/dev/duzo/bluemap3d/api/ScenePersistencePolicyTest.java
mkdir -p core/src/main/resources/assets/bluemap3d/models/block
cp "$ROOT/overrides/core/src/main/resources/assets/bluemap3d/models/block/flexible_segment.json" \
   core/src/main/resources/assets/bluemap3d/models/block/flexible_segment.json
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
cp "$ROOT/overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/SimulatedRopeRegistry.java" \
   addon-create/src/main/java/dev/duzo/bluemap3d/create/SimulatedRopeRegistry.java
cp "$ROOT/overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/SimulatedSpringProvider.java" \
   addon-create/src/main/java/dev/duzo/bluemap3d/create/SimulatedSpringProvider.java
cp "$ROOT/overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/SimulatedSpringRegistry.java" \
   addon-create/src/main/java/dev/duzo/bluemap3d/create/SimulatedSpringRegistry.java
mkdir -p addon-create/src/main/java/dev/duzo/bluemap3d/create/mixin
cp "$ROOT/overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/mixin/SimulatedSpringBlockEntityMixin.java" \
   addon-create/src/main/java/dev/duzo/bluemap3d/create/mixin/SimulatedSpringBlockEntityMixin.java
cp "$ROOT/overrides/addon-create/src/main/java/dev/duzo/bluemap3d/create/mixin/SimulatedRopeStrandHolderMixin.java" \
   addon-create/src/main/java/dev/duzo/bluemap3d/create/mixin/SimulatedRopeStrandHolderMixin.java
cp "$ROOT/overrides/addon-create/src/main/resources/bluemap3d_create.mixins.json" \
   addon-create/src/main/resources/bluemap3d_create.mixins.json

mkdir -p addon-sable/src/main/java/dev/duzo/bluemap3d/sable/mixin
cp "$ROOT/overrides/addon-sable/src/main/java/dev/duzo/bluemap3d/sable/ShipGeometryRevisionTracker.java" \
   addon-sable/src/main/java/dev/duzo/bluemap3d/sable/ShipGeometryRevisionTracker.java
cp "$ROOT/overrides/addon-sable/src/main/java/dev/duzo/bluemap3d/sable/mixin/SableBlockChangeMixin.java" \
   addon-sable/src/main/java/dev/duzo/bluemap3d/sable/mixin/SableBlockChangeMixin.java
cp "$ROOT/overrides/addon-sable/src/main/resources/bluemap3d_sable.mixins.json" \
   addon-sable/src/main/resources/bluemap3d_sable.mixins.json

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

./gradlew clean :core:test bundleJar :addon-compat:build

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
printf 'BlueMap %s\n' "$BLUEMAP_TARGET" > "$DIST/BLUEMAP_TARGET.txt"
printf '%s\n' "$MINECRAFT_TARGET" > "$DIST/MINECRAFT_TARGET.txt"
cp "$WORK/src/LICENSE" "$DIST/UPSTREAM-LICENSE.txt"

cd "$ROOT"
python3 "$ROOT/scripts/package-dist.py"

ls -lah "$DIST"