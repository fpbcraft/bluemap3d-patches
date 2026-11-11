/* BlueMap3D stable loader. Licensed under LGPL-3.0-only.
 * Revalidate the manifest, reuse content-addressed code. */
(function () {
    "use strict";
    if (window.__bluemap3dLoaded) return;
    window.__bluemap3dLoaded = "loader-3-content-hash";
    var attempt = 0;
    function load() {
        attempt++;
        fetch("assets/bluemap3d/bluemap3d-build.json", {cache: "no-cache"})
            .then(function (response) {
                if (!response.ok) throw new Error("HTTP " + response.status);
                return response.json();
            }).then(function (manifest) {
                if (!/^assets\/bluemap3d\/bluemap3d\.core-[a-f0-9]{64}\.js$/.test(manifest.script)) {
                    throw new Error("Invalid core script manifest");
                }
                return new Promise(function (resolve, reject) {
                    var script = document.createElement("script");
                    script.src = manifest.script;
                    script.async = false;
                    script.onload = resolve;
                    script.onerror = function () {
                        script.remove();
                        reject(new Error("Core script download failed"));
                    };
                    document.body.appendChild(script);
                });
            }).catch(function (error) {
                console.error("[BlueMap3D] core loading failed", error);
                if (attempt < 3) setTimeout(load, attempt * 1000);
                else delete window.__bluemap3dLoaded;
            });
    }
    load();
})();
