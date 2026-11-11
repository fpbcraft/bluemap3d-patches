"""Preserve patched web-runtime features while adding MCA-region cache invalidation."""
from pathlib import Path

TARGET = Path("core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js")

def apply():
    src = TARGET.read_text()
    anchor = """            for (var i = 0; i < keys.length; i++) {
                var x = bucket[keys[i]][0];"""
    if src.count(anchor) != 1:
        raise SystemExit("Could not locate unique BlueMap hires tile reload loop")
    insertion = """            /* Region refresh marker: one forced MCA update can rewrite several
             * visible hires tiles. The sampled server tile list is insufficient.
             * Reload currently loaded hires tiles only; leave lowres untouched. */
            var regionRefresh = keys.some(function (key) {
                return bucket[key][0] === -2147483648 && bucket[key][1] === -2147483648;
            });
            if (regionRefresh) {
                var loaded = Array.from(manager.tiles.values());
                for (var j = 0; j < loaded.length; j++) {
                    var oldTile = loaded[j];
                    if (!oldTile) continue;
                    var tx = oldTile.x;
                    var tz = oldTile.z;
                    oldTile.unload();
                    manager.tiles.delete(hash(tx, tz));
                    manager.tryLoadTile(tx, tz);
                    replaced++;
                }
                dirtyTiles[mapId] = Object.create(null);
                console.info(LOG, "terrain (" + reason + "): reloaded",
                    replaced, "loaded hires tile(s) after region render");
                return;
            }

"""
    TARGET.write_text(src.replace(anchor, insertion + anchor, 1))

if __name__ == "__main__":
    apply()
