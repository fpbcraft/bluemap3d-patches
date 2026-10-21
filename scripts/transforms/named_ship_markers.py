"""Inject the isolated named-ship marker UI after the browser runtime transforms.

The patch is deliberately independent of Sable's ship provider: it consumes the
existing provider/label/position fields in entities3d.json.
"""
from pathlib import Path

web = Path("core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js")
source = web.read_text()


def inject(old: str, new: str) -> None:
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(
            f"named ship marker injection expected one anchor, found {count}: {old[:100]!r}"
        )
    source = source.replace(old, new, 1)


inject(
    "    var root = null;\n",
    "    var root = null;\n"
    "    var shipMarkers = null;\n",
)

inject(
    "        viewer.markers.add(root);\n",
    "        viewer.markers.add(root);\n"
    "        shipMarkers = createNamedShipMarkers(window.bluemap, window.BlueMap);\n",
)

inject(
    "            entry.dimension = row.dimension;\n",
    "            entry.dimension = row.dimension;\n"
    "            if (shipMarkers) shipMarkers.sync(entry, row);\n",
)

inject(
    "        var entry = objects[id];\n        delete objects[id];\n",
    "        var entry = objects[id];\n"
    "        if (shipMarkers) shipMarkers.remove(entry);\n"
    "        delete objects[id];\n",
)

inject(
    """        mesh.position.set(
            from.pos[0] + (to.pos[0] - from.pos[0]) * alpha,
            from.pos[1] + (to.pos[1] - from.pos[1]) * alpha,
            from.pos[2] + (to.pos[2] - from.pos[2]) * alpha
        );
""",
    """        mesh.position.set(
            from.pos[0] + (to.pos[0] - from.pos[0]) * alpha,
            from.pos[1] + (to.pos[1] - from.pos[1]) * alpha,
            from.pos[2] + (to.pos[2] - from.pos[2]) * alpha
        );
        if (shipMarkers) shipMarkers.position(entry, mesh.position);
""",
)

inject(
    """            var entry = objects[id];
            if (!entry.mesh) {
                continue;
            }
            var allowed = dimensionMaps[entry.dimension];
""",
    """            var entry = objects[id];
            var allowed = dimensionMaps[entry.dimension];
            var visible = !mapId || !allowed || allowed.indexOf(mapId) >= 0;
            if (shipMarkers) shipMarkers.show(entry, visible && !suppressedObjects[id]);
            if (!entry.mesh) {
                continue;
            }
""",
)

inject(
    "        Object.keys(objects).forEach(remove);\n",
    "        Object.keys(objects).forEach(remove);\n"
    "        if (shipMarkers) {\n"
    "            shipMarkers.dispose();\n"
    "            shipMarkers = null;\n"
    "        }\n",
)

helper = Path(__file__).with_suffix(".js").read_text()
inject(
    "    waitForBlueMap(0);\n})();",
    helper + "\n    waitForBlueMap(0);\n})();",
)
web.write_text(source)
