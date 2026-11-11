"""Keep geometry scheduling independent of scene-format and marker transforms."""
from pathlib import Path

web = Path("core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js")
source = web.read_text()

def inject(old, new):
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"geometry loading expected one anchor, found {count}: {old[:100]!r}")
    source = source.replace(old, new, 1)

inject('var BUILD = "core-history-43-rope-removal";',
       'var BUILD = "core-history-44-geometry-loading";')

helper = Path(__file__).with_suffix(".js").read_text()
inject("    function loadMesh(url) {", helper + '''
    var geometryQueue = createGeometryQueue(function (url, signal) {
        return fetch(url, {cache: "force-cache", signal: signal})
            .then(function (response) {
                if (!response.ok) throw new Error("HTTP " + response.status);
                return response.arrayBuffer();
            }).then(decode);
    }, function (owner) {
        if (!owner) return -1; // Explicit historical demand is never gated by live dimensions.
        if (objects[owner.id] !== owner) return null;
        var map = viewer && viewer.map;
        var mapId = map && map.data ? map.data.id : null;
        var allowed = dimensionMaps[owner.dimension];
        if (mapId && allowed && allowed.indexOf(mapId) < 0) return Infinity;
        var pos = owner.to && owner.to.pos;
        var camera = viewer && viewer.camera && viewer.camera.position;
        if (!pos || !camera) return 0;
        return Math.hypot(pos[0] - camera.x, pos[1] - camera.y, pos[2] - camera.z);
    }, 4);

    function loadMesh(url, owner) {''')
inject('''        if (!meshCache[url]) {
            meshCache[url] = fetch(url, {cache: "force-cache"})
                .then(function (response) {
                    if (!response.ok) {
                        throw new Error("HTTP " + response.status);
                    }
                    return response.arrayBuffer();
                })
                .then(decode)''', '''        geometryQueue.addOwner(url, owner);
        if (!meshCache[url]) {
            meshCache[url] = geometryQueue.load(url, owner)''')
inject("            entry.dimension = row.dimension;", "            entry.dimension = row.dimension;\n            entry.latestRow = row;\n            entry.id = row.id;")
inject("loadMesh(row.mesh).then", "loadMesh(row.mesh, entry).then")
inject("replaceMesh(live, resource, row);", "replaceMesh(live, resource, live.latestRow);")
inject('''                        console.error(LOG, "could not load", row.mesh, e);''', '''                        var live = objects[row.id];
                        if (live === entry && live.meshUrl === row.mesh) live.meshUrl = null;
                        debug("mesh load deferred or failed", row.mesh, e.message);''')
inject("&& (count === 0 || state.mesh)", "&& (count === 0 || state.mesh || state.loading)")
inject("loadMesh(groupRow.mesh).then", "state.loading = true;\n            loadMesh(groupRow.mesh, entry).then")
inject("                live.mesh = mesh;", "                live.loading = false;\n                live.mesh = mesh;")
inject('''                console.error(LOG, "could not load instance prototype", groupRow.mesh, e);''', '''                var live = entry.instanceGroups && entry.instanceGroups[groupRow.id];
                if (live && live.token === token) live.loading = false;
                debug("instance geometry deferred or failed", groupRow.mesh, e.message);''')
inject("    function disposeAll() {\n        disposed = true;", "    function disposeAll() {\n        disposed = true;\n        geometryQueue.dispose();")
web.write_text(source)

# Published under the existing stable loader URL; Java publishes its hashed target first.
web.with_name("bluemap3d.js").write_text(Path(__file__).with_name("core_loader.js").read_text())
