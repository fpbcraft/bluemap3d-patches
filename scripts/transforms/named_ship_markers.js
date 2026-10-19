/*
 * Named Sable/Aeronautics ship labels for BlueMap 5.7.
 * Included inside the BlueMap3D core runtime by named_ship_markers.py.
 * No additional feed or server-side ship scan: uses SceneObjectTracker's
 * authoritative provider, label and interpolated position.
 */
function createNamedShipMarkers(app, api) {
    if (!app.popupMarkerSet || !api.MarkerSet || !api.HtmlMarker) {
        console.warn("[BlueMap3D] Named ship markers unavailable in this BlueMap webapp");
        return null;
    }

    var root = new api.MarkerSet("bluemap3d-named-ships", {
        label: "Named ships",
        toggleable: true
    });
    app.popupMarkerSet.add(root);

    var css = document.createElement("style");
    css.textContent = [
        ".bm3d-ship-pin{position:relative;transform:translate(-50%,-100%);",
        "font:600 12px/1.35 system-ui,sans-serif;white-space:nowrap;pointer-events:auto}",
        ".bm3d-ship-icon{appearance:none;display:grid;place-items:center;",
        "width:32px;height:32px;padding:0;border:1px solid #b5cadb;",
        "border-radius:50%;background:#172b3be8;color:#fff;",
        "box-shadow:0 2px 9px #0008;cursor:pointer}",
        ".bm3d-ship-icon svg{display:block;width:20px;height:20px;",
        "fill:none;stroke:currentColor;stroke-width:1.8;",
        "stroke-linecap:round;stroke-linejoin:round}",
        ".bm3d-ship-icon:hover,.bm3d-ship-icon:focus-visible{background:#29465e;",
        "outline:2px solid #a8d6ff;outline-offset:2px}",
        ".bm3d-ship-details{position:absolute;top:calc(100% + 6px);left:50%;",
        "transform:translateX(-50%);background:#182632f5;color:#fff;",
        "border:1px solid #7991a3;border-radius:8px;padding:10px 12px;",
        "box-shadow:0 4px 16px #0009;min-width:190px;white-space:normal;",
        "line-height:1.5;z-index:10;font-weight:400}",
        ".bm3d-ship-details[hidden]{display:none}",
        ".bm3d-ship-title{font-weight:700;margin-bottom:4px;overflow-wrap:anywhere}",
        ".bm3d-ship-coords{font-variant-numeric:tabular-nums}"
    ].join("");
    document.head.appendChild(css);

    var openEntry = null;
    var disposed = false;

    function closeOpen() {
        if (!openEntry || !openEntry.shipMarker) {
            openEntry = null;
            return;
        }
        var marker = openEntry.shipMarker;
        marker.shipDetails.hidden = true;
        marker.shipButton.setAttribute("aria-expanded", "false");
        openEntry = null;
    }

    function closeOnOutsideClick(event) {
        if (openEntry && openEntry.shipMarker &&
                !openEntry.shipMarker.element.contains(event.target)) {
            closeOpen();
        }
    }

    function closeOnEscape(event) {
        if (event.key === "Escape") {
            closeOpen();
        }
    }

    document.addEventListener("pointerdown", closeOnOutsideClick);
    document.addEventListener("keydown", closeOnEscape);

    function position(entry, point) {
        var marker = entry.shipMarker;
        if (!marker || !point) return;
        marker.position.set(point.x, point.y + 3, point.z);
        marker.shipPoint = {x: point.x, y: point.y, z: point.z};
        if (openEntry === entry) {
            marker.shipCoords.textContent = "Position: " +
                Math.round(point.x) + ", " + Math.round(point.y) +
                ", " + Math.round(point.z);
        }
    }

    function remove(entry) {
        if (!entry || !entry.shipMarker) return;
        if (openEntry === entry) closeOpen();
        root.remove(entry.shipMarker);
        entry.shipMarker = null;
    }

    function sync(entry, row) {
        var label = row.provider === "sable_ships" && typeof row.label === "string"
            ? row.label.trim() : "";
        if (!label || !Array.isArray(row.pos) || row.pos.length < 3) {
            remove(entry);
            return;
        }

        var marker = entry.shipMarker;
        if (!marker) {
            marker = new api.HtmlMarker("bluemap3d-ship-" + row.id);
            marker.anchor.set(0, 0);

            var pin = document.createElement("div");
            pin.className = "bm3d-ship-pin";
            var button = document.createElement("button");
            button.type = "button";
            button.className = "bm3d-ship-icon";
            button.setAttribute("aria-expanded", "false");
            button.setAttribute("aria-label", "Show ship details");
            button.setAttribute("aria-haspopup", "true");

            // The ship name belongs in the details popup, not on the map marker.
            // Build the icon as SVG nodes so names never pass through innerHTML.
            var svgNS = "http://www.w3.org/2000/svg";
            var icon = document.createElementNS(svgNS, "svg");
            icon.setAttribute("viewBox", "0 0 24 24");
            icon.setAttribute("aria-hidden", "true");
            icon.setAttribute("focusable", "false");
            var hull = document.createElementNS(svgNS, "path");
            hull.setAttribute("d", "M3 10c0-4.2 4-7 9-7s9 2.8 9 7-4 4-9 4-9-1.8-9-4Z");
            var gondola = document.createElementNS(svgNS, "path");
            gondola.setAttribute("d", "m7 13 2 4h6l2-4M10 17v2m4-2v2m-4 0h4");
            icon.append(hull, gondola);
            button.appendChild(icon);
            pin.appendChild(button);

            var details = document.createElement("div");
            details.className = "bm3d-ship-details";
            details.hidden = true;
            details.setAttribute("role", "group");
            details.setAttribute("aria-label", "Ship details");
            var title = document.createElement("div");
            title.className = "bm3d-ship-title";
            var dimension = document.createElement("div");
            var coords = document.createElement("div");
            coords.className = "bm3d-ship-coords";
            details.append(title, dimension, coords);
            pin.appendChild(details);
            marker.element.appendChild(pin);

            marker.shipButton = button;
            marker.shipDetails = details;
            marker.shipTitle = title;
            marker.shipDimension = dimension;
            marker.shipCoords = coords;
            entry.shipMarker = marker;
            button.addEventListener("click", function (event) {
                event.preventDefault();
                event.stopPropagation();
                if (openEntry === entry) {
                    closeOpen();
                } else {
                    closeOpen();
                    openEntry = entry;
                    details.hidden = false;
                    button.setAttribute("aria-expanded", "true");
                    // Refresh details even when the map render loop is paused.
                    var p = entry.to && entry.to.pos;
                    if (p) position(entry, {x: p[0], y: p[1], z: p[2]});
                    else if (marker.shipPoint) position(entry, marker.shipPoint);
                }
            });
            root.add(marker);
        }

        // Only textContent: user-supplied ship names must not be interpreted as HTML.
        // The icon stays unchanged when a ship is renamed.
        marker.shipButton.setAttribute("aria-label", "Show details for " + label);
        marker.shipTitle.textContent = label;
        marker.shipDimension.textContent = "Dimension: " + row.dimension;
        position(entry, {x: row.pos[0], y: row.pos[1], z: row.pos[2]});
    }

    function show(entry, visible) {
        if (!entry.shipMarker) return;
        entry.shipMarker.visible = visible;
        entry.shipMarker.element.hidden = !visible;
        if (!visible && openEntry === entry) closeOpen();
    }

    function dispose() {
        if (disposed) return;
        disposed = true;
        closeOpen();
        document.removeEventListener("pointerdown", closeOnOutsideClick);
        document.removeEventListener("keydown", closeOnEscape);
        app.popupMarkerSet.remove(root);
        css.remove();
    }

    return {sync: sync, position: position, show: show, remove: remove, dispose: dispose};
}
