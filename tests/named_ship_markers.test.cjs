const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

function fixture() {
    const listeners = new Map();
    const document = {
        head: null,
        createElementNS(_namespace, tag) { return this.createElement(tag); },
        createElement(tag) {
            return {
                tag, children: [], attributes: {}, hidden: false, textContent: "",
                className: "", style: {}, removed: false, handlers: {},
                appendChild(child) { this.children.push(child); return child; },
                append(...children) { this.children.push(...children); },
                setAttribute(key, value) { this.attributes[key] = value; },
                addEventListener(event, handler) { this.handlers[event] = handler; },
                contains(node) {
                    if (this === node) return true;
                    return this.children.some(child => child.contains(node));
                },
                remove() { this.removed = true; }
            };
        },
        addEventListener(name, listener) { listeners.set(name, listener); },
        removeEventListener(name, listener) {
            if (listeners.get(name) === listener) listeners.delete(name);
        }
    };
    document.head = document.createElement("head");

    class MarkerSet {
        constructor(id) { this.id = id; this.markers = new Map(); }
        add(marker) { this.markers.set(marker.id, marker); }
        remove(marker) { this.markers.delete(marker.id); }
    }
    class HtmlMarker {
        constructor(id) {
            this.id = id;
            this.element = document.createElement("div");
            this.anchor = {set() {}};
            this.position = {
                x: 0, y: 0, z: 0,
                set(x, y, z) { Object.assign(this, {x, y, z}); }
            };
        }
    }
    const root = new MarkerSet("main");
    const app = {popupMarkerSet: root};
    const source = fs.readFileSync(
        path.join(__dirname, "../scripts/transforms/named_ship_markers.js"), "utf8");
    const context = {document, console, window: {}};
    vm.runInNewContext(source + "\nthis.makeMarkers = createNamedShipMarkers;", context);
    const manager = context.makeMarkers(app, {MarkerSet, HtmlMarker});
    const children = () => [...root.markers.values()][0].markers;
    return {manager, document, listeners, root, children};
}

function shipRow(overrides = {}) {
    return {
        id: "sable_ships/ship-123",
        provider: "sable_ships",
        label: "The Peregrine",
        dimension: "minecraft:overworld",
        pos: [100, 80, -25],
        ...overrides
    };
}

test("named Sable ships get one reusable accessible marker and safe details", () => {
    const {manager, children, listeners} = fixture();
    const entry = {};
    manager.sync(entry, shipRow({label: "<img src=x onerror=alert(1)>"}));
    assert.equal(children().size, 1);
    // The name is only in the initially hidden popup; the button stays icon-only.
    assert.equal(entry.shipMarker.shipButton.textContent, "");
    assert.equal(entry.shipMarker.shipButton.className, "bm3d-ship-icon");
    assert.equal(entry.shipMarker.shipButton.children.length, 1);
    assert.equal(entry.shipMarker.shipButton.children[0].tag, "svg");
    assert.equal(entry.shipMarker.shipButton.children[0].children.length, 2);
    assert.equal(entry.shipMarker.shipButton.children[0].children[0].tag, "path");
    assert.equal(entry.shipMarker.shipButton.children[0].children[1].tag, "circle");
    assert.equal(entry.shipMarker.shipButton.children[0].children[1].attributes.r, "3");
    assert.equal(entry.shipMarker.shipButton.attributes["aria-label"],
        "Show details for <img src=x onerror=alert(1)>");
    assert.equal(entry.shipMarker.shipTitle.textContent, "<img src=x onerror=alert(1)>");
    assert.equal(entry.shipMarker.shipDetails.hidden, true);
    assert.equal(entry.shipMarker.position.x, 100);
    assert.equal(entry.shipMarker.position.y, 83);

    manager.sync(entry, shipRow({label: "The Peregrine"}));
    assert.equal(children().size, 1);
    assert.equal(entry.shipMarker.shipButton.textContent, "");
    assert.equal(entry.shipMarker.shipButton.children.length, 1);
    assert.equal(entry.shipMarker.shipButton.attributes["aria-label"],
        "Show details for The Peregrine");
    assert.equal(entry.shipMarker.shipTitle.textContent, "The Peregrine");

    const event = {preventDefault() {}, stopPropagation() {}};
    entry.shipMarker.shipButton.handlers.click(event);
    assert.equal(entry.shipMarker.shipDetails.hidden, false);
    assert.equal(entry.shipMarker.shipButton.attributes["aria-expanded"], "true");
    assert.equal(entry.shipMarker.shipTitle.textContent, "The Peregrine");
    assert.equal(entry.shipMarker.shipCoords.textContent, "Position: 100, 80, -25");
    assert.equal(entry.shipMarker.shipDimension.textContent, "Dimension: minecraft:overworld");

    manager.position(entry, {x: 124.8, y: 90, z: -10.1});
    assert.equal(entry.shipMarker.position.y, 93);
    assert.equal(entry.shipMarker.shipCoords.textContent, "Position: 125, 90, -10");

    listeners.get("keydown")({key: "Escape"});
    assert.equal(entry.shipMarker.shipDetails.hidden, true);
    entry.shipMarker.shipButton.handlers.click(event);
    assert.equal(entry.shipMarker.shipDetails.hidden, false);
    entry.shipMarker.shipButton.handlers.click(event);
    assert.equal(entry.shipMarker.shipDetails.hidden, true);
    manager.dispose();
});

test("unnamed or different-provider objects get no labels, and stale markers are removed", () => {
    const {manager, children, root, document, listeners} = fixture();
    const entry = {};
    manager.sync(entry, shipRow({label: "   "}));
    manager.sync(entry, shipRow({provider: "create_contraptions"}));
    assert.equal(children().size, 0);
    manager.sync(entry, shipRow());
    assert.equal(children().size, 1);
    manager.show(entry, false);
    assert.equal(entry.shipMarker.element.hidden, true);
    manager.show(entry, true);
    assert.equal(entry.shipMarker.element.hidden, false);
    manager.sync(entry, shipRow({label: null}));
    assert.equal(children().size, 0);
    assert.equal(entry.shipMarker, null);

    manager.dispose();
    assert.equal(root.markers.size, 0);
    assert.equal(document.head.children[0].removed, true);
    assert.equal(listeners.size, 0);
});

test("flyover vehicles never get markers, including rename transitions", () => {
    const {manager, children} = fixture();
    const entry = {};

    manager.sync(entry, shipRow({label: "flyover"}));
    assert.equal(children().size, 0);
    manager.sync(entry, shipRow({label: " FlyOver "}));
    assert.equal(children().size, 0);

    manager.sync(entry, shipRow({label: "The Peregrine"}));
    assert.equal(children().size, 1);
    manager.sync(entry, shipRow({label: "FLYOVER"}));
    assert.equal(children().size, 0);
    assert.equal(entry.shipMarker, null);

    manager.sync(entry, shipRow({label: "Flyover II"}));
    assert.equal(children().size, 1);
    manager.dispose();
});
