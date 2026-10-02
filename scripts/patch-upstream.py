from pathlib import Path
import runpy

from release_metadata import load_release
from transforms.contraption_provider import apply as patch_contraption_provider

RELEASE = load_release()
VERSION = RELEASE["version"]
BLUEMAP_TARGET = RELEASE["blueMapVersion"]

def replace(path, old, new):
    p = Path(path)
    s = p.read_text()
    if old not in s:
        raise SystemExit(f"expected text not found in {path}: {old!r}")
    p.write_text(s.replace(old, new))

patch_contraption_provider()

replace(
    "core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js",
    'var BUILD = "core-history-15-special-models";',
    'var BUILD = "core-history-43-rope-removal";',
)
replace("gradle.properties", "version=1.0.9", f"version={VERSION}")

# Release metadata remains dynamic; structural BlueMap3DMod wiring lives in 0030.
p = Path("core/src/main/java/dev/duzo/bluemap3d/BlueMap3DMod.java")
s = p.read_text()
needle = 'LOGGER.info("BlueMap3D loaded. Waiting for BlueMap and at least one addon.");'
if needle not in s:
    raise SystemExit("BlueMap3D startup marker insertion point not found")
s = s.replace(
    needle,
    needle
        + f'\n        LOGGER.info("BlueMap3D FPB patches {VERSION} active; BlueMap target is {BLUEMAP_TARGET}.");',
    1,
)
p.write_text(s)

runpy.run_path(
    Path(__file__).parent / "transforms" / "instanced_scene_runtime.py",
    run_name="__bluemap3d_instanced_scene_runtime__",
)
