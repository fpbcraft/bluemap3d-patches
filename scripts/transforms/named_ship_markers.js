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
        label: "Ships and trains",
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

    var owners = new Set();
    var trainGroups = new Map();
    var overview = (app.mapViewer && app.mapViewer.controlsManager.distance || 0) >= 220;
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

    function positionMarker(entry, point) {
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

    function removeMarker(entry) {
        if (!entry || !entry.shipMarker) return;
        if (openEntry === entry) closeOpen();
        owners.delete(entry);
        root.remove(entry.shipMarker);
        entry.shipMarker = null;
    }

    function syncMarker(entry, row, kind, label) {
        // Hide flyover utility vehicles without affecting other named ships.
        if (!label || (kind === "ship" && label.toLowerCase() === "flyover") ||
                !Array.isArray(row.pos) || row.pos.length < 3) {
            removeMarker(entry);
            return;
        }

        var marker = entry.shipMarker;
        if (!marker) {
            marker = new api.HtmlMarker("bluemap3d-ship-" + row.id);
            owners.add(entry);
            marker.anchor.set(0, 0);

            var pin = document.createElement("div");
            pin.className = "bm3d-ship-pin";
            var button = document.createElement("button");
            button.type = "button";
            button.className = "bm3d-ship-icon";
            button.setAttribute("aria-expanded", "false");
            button.setAttribute("aria-label", "Show " + kind + " details");
            button.setAttribute("data-vehicle", kind);
            button.setAttribute("aria-haspopup", "true");

            // The ship name belongs in the details popup, not on the map marker.
            // Build the icon as SVG nodes so names never pass through innerHTML.
            var svgNS = "http://www.w3.org/2000/svg";
            var icon = document.createElementNS(svgNS, "svg");
            icon.setAttribute("viewBox", "0 0 24 24");
            icon.setAttribute("aria-hidden", "true");
            icon.setAttribute("focusable", "false");
            var gear = document.createElementNS(svgNS, "path");
            gear.setAttribute("d", "M10 2h4l.5 2.3 1.8.8 2-1.3 2.9 2.9-1.3 2 .8 1.8L23 11v2l-2.3.5-.8 1.8 1.3 2-2.9 2.9-2-1.3-1.8.8L14 22h-4l-.5-2.3-1.8-.8-2 1.3-2.9-2.9 1.3-2-.8-1.8L1 13v-2l2.3-.5.8-1.8-1.3-2 2.9-2.9 2 1.3 1.8-.8L10 2Z");
            var hub = document.createElementNS(svgNS, "circle");
            hub.setAttribute("cx", "12");
            hub.setAttribute("cy", "12");
            hub.setAttribute("r", "3");
            if (kind === "train") {
                gear.setAttribute("d", "M6 17h12V6c0-2-12-2-12 0v11ZM6 10h12M9 17l-3 4m9-4 3 4M8 20h8M9 14h.01M15 14h.01");
                icon.append(gear);
            } else icon.append(gear, hub);
            button.appendChild(icon);
            pin.appendChild(button);

            var details = document.createElement("div");
            details.className = "bm3d-ship-details";
            details.hidden = true;
            details.setAttribute("role", "group");
            details.setAttribute("aria-label", kind === "train" ? "Train details" : "Ship details");
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
                    if (p) positionMarker(entry, {x: p[0], y: p[1], z: p[2]});
                    else if (marker.shipPoint) positionMarker(entry, marker.shipPoint);
                }
            });
            root.add(marker);
            showMarker(entry, false);
        }

        // Only textContent: user-supplied ship names must not be interpreted as HTML.
        // The icon stays unchanged when a ship is renamed.
        marker.shipButton.setAttribute("aria-label", "Show details for " + label);
        marker.shipTitle.textContent = label;
        marker.shipDimension.textContent = "Dimension: " + row.dimension;
        positionMarker(entry, {x: row.pos[0], y: row.pos[1], z: row.pos[2]});
    }

    function showMarker(entry, visible) {
        entry.markerEligible = visible;
        visible = visible && overview;
        if (!entry.shipMarker) return;
        entry.shipMarker.visible = visible;
        entry.shipMarker.element.hidden = !visible;
        if (!visible && openEntry === entry) closeOpen();
    }

    function refreshTrain(group) {
        var leader = null;
        group.members.forEach(function (member, entry) {
            if (!leader || member.index < leader.member.index) leader = {entry: entry, member: member};
        });
        group.leader = leader && leader.entry;
        if (!leader) return;
        var row = leader.member.row;
        syncMarker(group.owner, row, "train", typeof row.label === "string" && row.label.trim() || "Train");
        if (leader.entry.markerPoint) positionMarker(group.owner, leader.entry.markerPoint);
        showMarker(group.owner, Array.from(group.members.keys()).some(function (entry) { return entry.markerEligible; }));
    }

    function remove(entry) {
        if (entry.trainGroup) {
            var group = entry.trainGroup;
            group.members.delete(entry);
            entry.trainGroup = null;
            entry.markerPoint = null;
            if (group.members.size) refreshTrain(group);
            else { removeMarker(group.owner); trainGroups.delete(group.key); }
        } else removeMarker(entry);
    }

    function sync(entry, row) {
        // Only authoritative carriage IDs qualify; windmills/other contraptions do not.
        var train = row.provider === "create_contraptions" && typeof row.id === "string"
            ? row.id.match(/^create_contraptions\/(.+)\/([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\/(\d+)$/i) : null;
        if (train && Array.isArray(row.pos) && row.pos.length >= 3) {
            var key = train[2] + "@" + row.dimension;
            if (entry.trainGroup && entry.trainGroup.key !== key) remove(entry);
            if (!entry.trainGroup) {
                removeMarker(entry);
                var group = trainGroups.get(key);
                if (!group) { group = {key: key, owner: {}, members: new Map()}; trainGroups.set(key, group); }
                entry.trainGroup = group;
            }
            entry.trainGroup.members.set(entry, {row: row, index: Number(train[3])});
            entry.markerPoint = {x: row.pos[0], y: row.pos[1], z: row.pos[2]};
            refreshTrain(entry.trainGroup);
            return;
        }
        if (entry.trainGroup) remove(entry);
        var label = row.provider === "sable_ships" && typeof row.label === "string" ? row.label.trim() : "";
        syncMarker(entry, row, "ship", label);
    }

    function position(entry, point) {
        entry.markerPoint = {x: point.x, y: point.y, z: point.z};
        if (entry.trainGroup) {
            if (entry.trainGroup.leader === entry) positionMarker(entry.trainGroup.owner, point);
        } else positionMarker(entry, point);
    }

    function show(entry, visible) {
        entry.markerEligible = visible;
        if (entry.trainGroup) {
            var group = entry.trainGroup;
            showMarker(group.owner, Array.from(group.members.keys()).some(function (member) { return member.markerEligible; }));
        } else showMarker(entry, visible);
    }

    function updateZoom() {
        var distance = app.mapViewer && app.mapViewer.controlsManager.distance || 0;
        var next = overview ? distance > 170 : distance >= 220;
        if (next === overview) return;
        overview = next;
        owners.forEach(function (entry) { showMarker(entry, entry.markerEligible); });
    }
    if (app.events) app.events.addEventListener("bluemapRenderFrame", updateZoom);

    function dispose() {
        if (disposed) return;
        disposed = true;
        closeOpen();
        document.removeEventListener("pointerdown", closeOnOutsideClick);
        document.removeEventListener("keydown", closeOnEscape);
        if (app.events) app.events.removeEventListener("bluemapRenderFrame", updateZoom);
        owners.clear();
        trainGroups.clear();
        app.popupMarkerSet.remove(root);
        css.remove();
    }

    return {sync: sync, position: position, show: show, remove: remove, dispose: dispose};
}
