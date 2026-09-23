(function () {
    "use strict";

    var BUILD = "fa-player-profile-1";
    var FEED_URL = "assets/bluemap3d/players3d.json";
    var LOG = "[BlueMap3D/Players]";
    var PIXEL = 0.05625;
    var MAX_FRAME_MS = 100;
    var FALLBACK_INTERVAL = 100;

    var THREE = null;
    var viewer = null;
    var root = null;
    var actors = Object.create(null);
    var textureCache = Object.create(null);
    var intervalMs = FALLBACK_INTERVAL;
    var pollTimer = null;
    var disposed = false;
    var lastFrame = 0;

    function ready() {
        return window.bluemap
            && window.bluemap.mapViewer
            && window.bluemap.mapViewer.markers
            && window.BlueMap
            && window.BlueMap.Three;
    }

    function clamp(value, low, high) {
        return Math.max(low, Math.min(high, value));
    }

    function radians(degrees) {
        return Number(degrees || 0) * Math.PI / 180;
    }

    function shortestAngle(from, to) {
        return Math.atan2(Math.sin(to - from), Math.cos(to - from));
    }

    function flag(value) {
        return value === true || value === "true";
    }

    function currentMapId() {
        return viewer && viewer.map && viewer.map.data ? viewer.map.data.id : null;
    }

    function mapVisible(data) {
        var id = currentMapId();
        return !id || !data.maps || data.maps.indexOf(id) >= 0;
    }

    function boxRegions(x, y, width, height, depth) {
        return [
            [x + depth + width, y + depth, depth, height],
            [x, y + depth, depth, height],
            [x + depth, y, width, depth],
            [x + depth + width, y, width, depth],
            [x + depth, y + depth, width, height],
            [x + depth * 2 + width, y + depth, width, height]
        ];
    }

    // UV layout and the basic skin cuboids follow Minecraft's standard player skin
    // layout. The small helper structure is adapted from DecoderCoder/bluemap-player-models
    // (MIT); the gameplay pose system below is BlueMap3D-specific.
    function cuboidGeometry(width, height, depth, uv) {
        var geometry = new THREE.BoxGeometry(
            width * PIXEL,
            height * PIXEL,
            depth * PIXEL
        );
        var regions = boxRegions(uv[0], uv[1], width, height, depth);
        var attribute = geometry.attributes.uv;
        regions.forEach(function (region, face) {
            var x = region[0];
            var y = region[1];
            var w = region[2];
            var h = region[3];
            var left = x / 64;
            var right = (x + w) / 64;
            var top = 1 - y / 64;
            var bottom = 1 - (y + h) / 64;
            var index = face * 4;
            attribute.setXY(index, left, top);
            attribute.setXY(index + 1, right, top);
            attribute.setXY(index + 2, left, bottom);
            attribute.setXY(index + 3, right, bottom);
        });
        attribute.needsUpdate = true;
        return geometry;
    }

    function material(color, overlay) {
        return new THREE.MeshBasicMaterial({
            color: color,
            transparent: !!overlay,
            alphaTest: 0.05,
            depthWrite: !overlay
        });
    }

    function addSkinPart(actor, parent, width, height, depth, baseUv, overlayUv, position) {
        var group = new THREE.Group();
        group.position.set(position[0], position[1], position[2]);

        var base = new THREE.Mesh(
            cuboidGeometry(width, height, depth, baseUv),
            actor.baseMaterial
        );
        var overlay = new THREE.Mesh(
            cuboidGeometry(width, height, depth, overlayUv),
            actor.overlayMaterial
        );
        overlay.scale.setScalar(1.055);
        overlay.visible = true;

        group.add(base);
        group.add(overlay);
        parent.add(group);
        return group;
    }

    function addLimb(actor, width, height, depth, baseUv, overlayUv, position) {
        var pivot = new THREE.Group();
        pivot.position.set(position[0], position[1], position[2]);
        addSkinPart(
            actor,
            pivot,
            width,
            height,
            depth,
            baseUv,
            overlayUv,
            [0, -height * PIXEL / 2, 0]
        );
        actor.model.add(pivot);
        return pivot;
    }

    function validSkinUrl(value) {
        if (!value) return null;
        try {
            var url = new URL(value);
            return url.protocol === "https:"
                && url.hostname === "textures.minecraft.net"
                && /^\/texture\/[a-f0-9]+$/i.test(url.pathname)
                ? url.href
                : null;
        } catch (ignored) {
            return null;
        }
    }

    function loadTexture(url) {
        url = validSkinUrl(url);
        if (!url) return Promise.resolve(null);
        if (textureCache[url]) return textureCache[url];

        textureCache[url] = new Promise(function (resolve) {
            var loader = new THREE.TextureLoader();
            loader.setCrossOrigin("anonymous");
            loader.load(
                url,
                function (texture) {
                    texture.magFilter = THREE.NearestFilter;
                    texture.minFilter = THREE.NearestFilter;
                    texture.generateMipmaps = false;
                    if (THREE.SRGBColorSpace !== undefined) {
                        texture.colorSpace = THREE.SRGBColorSpace;
                    }
                    texture.needsUpdate = true;
                    resolve(texture);
                },
                undefined,
                function () {
                    console.warn(LOG, "could not load player skin", url);
                    resolve(null);
                }
            );
        });
        return textureCache[url];
    }

    function applySkin(actor, url) {
        var key = validSkinUrl(url);
        if (!key || actor.skinUrl === key) return;
        actor.skinUrl = key;
        loadTexture(key).then(function (texture) {
            if (!texture || actor.removed || actor.skinUrl !== key) return;
            actor.baseMaterial.map = texture;
            actor.baseMaterial.color.setHex(0xffffff);
            actor.baseMaterial.needsUpdate = true;
            actor.overlayMaterial.map = texture;
            actor.overlayMaterial.color.setHex(0xffffff);
            actor.overlayMaterial.needsUpdate = true;
            if (viewer) viewer.redraw();
        });
    }

    function createActor(data) {
        var actor = {
            data: data,
            slim: !!data.slim,
            root: new THREE.Group(),
            model: new THREE.Group(),
            baseMaterial: material(0x7d8a91, false),
            overlayMaterial: material(0xffffff, true),
            target: new THREE.Vector3(Number(data.x), Number(data.y), Number(data.z)),
            targetYaw: -radians(data.bodyYaw),
            cycle: 0,
            landing: 0,
            wasOnGround: flag(data.onGround),
            skinUrl: null,
            removed: false
        };

        actor.root.name = "bluemap3d-player-" + data.uuid;
        actor.model.name = "bluemap3d-player-rig-" + data.uuid;
        actor.root.position.copy(actor.target);
        actor.root.rotation.y = actor.targetYaw;
        actor.root.add(actor.model);

        var armWidth = actor.slim ? 3 : 4;

        actor.head = new THREE.Group();
        actor.head.position.set(0, 1.575, 0);
        actor.head.rotation.order = "YXZ";
        addSkinPart(actor, actor.head, 8, 8, 8, [0, 0], [32, 0], [0, 0, 0]);
        actor.model.add(actor.head);

        actor.body = new THREE.Group();
        actor.body.position.set(0, 1.35, 0);
        addSkinPart(actor, actor.body, 8, 12, 4, [16, 16], [16, 32],
            [0, -6 * PIXEL, 0]);
        actor.model.add(actor.body);

        actor.rightArm = addLimb(actor, armWidth, 12, 4, [40, 16], [40, 32],
            [-(8 + armWidth) * PIXEL / 2, 1.35, 0]);
        actor.leftArm = addLimb(actor, armWidth, 12, 4, [32, 48], [48, 48],
            [(8 + armWidth) * PIXEL / 2, 1.35, 0]);
        actor.rightLeg = addLimb(actor, 4, 12, 4, [0, 16], [0, 32],
            [-2 * PIXEL, 0.675, 0]);
        actor.leftLeg = addLimb(actor, 4, 12, 4, [16, 48], [0, 48],
            [2 * PIXEL, 0.675, 0]);

        root.add(actor.root);
        applySkin(actor, data.skinUrl);
        return actor;
    }

    function removeActor(id) {
        var actor = actors[id];
        if (!actor) return;
        actor.removed = true;
        if (root) root.remove(actor.root);
        actor.baseMaterial.dispose();
        actor.overlayMaterial.dispose();
        actor.root.traverse(function (object) {
            if (object.geometry && object.geometry.dispose) {
                object.geometry.dispose();
            }
        });
        delete actors[id];
    }

    function resetPose(actor) {
        actor.model.position.set(0, 0, 0);
        actor.model.rotation.set(0, 0, 0);

        actor.head.position.set(0, 1.575, 0);
        actor.body.position.set(0, 1.35, 0);
        actor.rightArm.position.set(-(8 + (actor.slim ? 3 : 4)) * PIXEL / 2, 1.35, 0);
        actor.leftArm.position.set((8 + (actor.slim ? 3 : 4)) * PIXEL / 2, 1.35, 0);
        actor.rightLeg.position.set(-2 * PIXEL, 0.675, 0);
        actor.leftLeg.position.set(2 * PIXEL, 0.675, 0);

        actor.head.rotation.set(0, 0, 0);
        actor.body.rotation.set(0, 0, 0);
        actor.rightArm.rotation.set(0, 0, 0);
        actor.leftArm.rotation.set(0, 0, 0);
        actor.rightLeg.rotation.set(0, 0, 0);
        actor.leftLeg.rotation.set(0, 0, 0);
    }

    function localMotion(data) {
        var yaw = radians(data.bodyYaw);
        var vx = Number(data.vx || 0);
        var vz = Number(data.vz || 0);
        // Minecraft yaw 0 points +Z. Rotate the world velocity into body-local axes.
        return {
            forward: vz * Math.cos(yaw) + vx * Math.sin(yaw),
            side: vx * Math.cos(yaw) - vz * Math.sin(yaw),
            speed: Math.sqrt(vx * vx + vz * vz)
        };
    }

    function animatePose(actor, now, dt) {
        var data = actor.data;
        var motion = localMotion(data);
        var speed = motion.speed;
        var moving = speed > 0.012;
        var sprinting = flag(data.sprinting);
        var crouching = flag(data.crouching);
        var inWater = flag(data.inWater);
        var swimming = flag(data.swimming);
        var climbing = flag(data.climbing);
        var fallFlying = flag(data.fallFlying);
        var riding = flag(data.passenger);
        var sleeping = flag(data.sleeping);
        var onGround = flag(data.onGround);
        var pose = String(data.pose || "");
        var crawling = pose === "swimming" && !inWater && !fallFlying;
        var treading = inWater && !swimming && !onGround;
        var wading = inWater && !swimming && onGround;
        var backwards = motion.forward < -0.012;
        var gaitDirection = backwards ? -1 : 1;

        var frequency = sprinting ? 12.5 : (wading ? 4.2 : (moving ? 7.5 : 2.0));
        actor.cycle += dt * frequency;
        var wave = Math.sin(actor.cycle) * gaitDirection;
        var cross = Math.cos(actor.cycle) * gaitDirection;
        var amplitude = clamp(speed * (sprinting ? 5.0 : 4.0), 0, sprinting ? 1.25 : 0.95);
        if (wading) amplitude *= 0.55;

        resetPose(actor);

        // FA-style idle motion: very small, continuous asymmetry so a stationary player
        // reads as alive without wobbling on the map.
        var breath = Math.sin(now * 0.0018);
        actor.body.position.y += breath * 0.004;
        actor.rightArm.rotation.z = 0.025 + breath * 0.012;
        actor.leftArm.rotation.z = -0.025 - breath * 0.012;

        if (moving && !fallFlying && !swimming && !crawling && !climbing && !sleeping) {
            actor.rightLeg.rotation.x = wave * 0.85 * amplitude;
            actor.leftLeg.rotation.x = -wave * 0.85 * amplitude;
            actor.rightArm.rotation.x = -wave * 0.72 * amplitude;
            actor.leftArm.rotation.x = wave * 0.72 * amplitude;
            actor.body.rotation.z += clamp(-motion.side * 0.55, -0.18, 0.18);
            actor.body.rotation.y = clamp(-motion.side * 0.35, -0.12, 0.12);
            if (sprinting) {
                actor.body.rotation.x = 0.13;
                actor.rightArm.rotation.x *= 1.22;
                actor.leftArm.rotation.x *= 1.22;
                actor.model.position.y += Math.abs(cross) * 0.012;
            }
        }

        if (crouching && !fallFlying && !swimming && !crawling) {
            actor.head.position.y -= 4.2 * PIXEL;
            actor.body.position.y -= 3.2 * PIXEL;
            actor.rightArm.position.y -= 3.2 * PIXEL;
            actor.leftArm.position.y -= 3.2 * PIXEL;
            actor.rightLeg.position.y -= 0.2 * PIXEL;
            actor.leftLeg.position.y -= 0.2 * PIXEL;
            actor.rightLeg.position.z -= 4 * PIXEL;
            actor.leftLeg.position.z -= 4 * PIXEL;
            actor.body.rotation.x += 0.48;
            actor.rightArm.rotation.x += 0.34;
            actor.leftArm.rotation.x += 0.34;
        }

        if (climbing && !fallFlying) {
            var climb = Math.sin(actor.cycle * 0.78);
            actor.body.rotation.x = -0.08;
            actor.rightArm.rotation.x = -2.35 + climb * 0.55;
            actor.leftArm.rotation.x = -2.35 - climb * 0.55;
            actor.rightLeg.rotation.x = climb * 0.55;
            actor.leftLeg.rotation.x = -climb * 0.55;
            actor.model.position.y += Math.abs(climb) * 0.025;
        }

        if (swimming || crawling) {
            actor.model.rotation.x = Math.PI / 2 - (swimming ? 0.08 : 0);
            actor.model.position.y += swimming ? 0.55 : 0.42;
            var swim = Math.sin(actor.cycle * 0.72);
            actor.rightArm.rotation.x = -1.45 + swim * 0.8;
            actor.leftArm.rotation.x = -1.45 - swim * 0.8;
            actor.rightArm.rotation.z = 0.16;
            actor.leftArm.rotation.z = -0.16;
            actor.rightLeg.rotation.x = swim * 0.38;
            actor.leftLeg.rotation.x = -swim * 0.38;
        } else if (treading) {
            var tread = Math.sin(actor.cycle * 0.66);
            actor.model.position.y -= 0.35;
            actor.rightArm.rotation.z = 0.85 + tread * 0.32;
            actor.leftArm.rotation.z = -0.85 - tread * 0.32;
            actor.rightArm.rotation.x = -0.25;
            actor.leftArm.rotation.x = -0.25;
            actor.rightLeg.rotation.x = tread * 0.32;
            actor.leftLeg.rotation.x = -tread * 0.32;
        }

        if (fallFlying) {
            actor.model.rotation.x = Math.PI / 2 - 0.22;
            actor.model.position.y += 0.55;
            actor.rightArm.rotation.x = 0.2;
            actor.leftArm.rotation.x = 0.2;
            actor.rightArm.rotation.z = 0.23;
            actor.leftArm.rotation.z = -0.23;
            actor.rightLeg.rotation.x = 0.12;
            actor.leftLeg.rotation.x = -0.12;
        } else if (riding) {
            actor.rightLeg.rotation.x = -1.1;
            actor.leftLeg.rotation.x = -1.1;
            actor.rightLeg.rotation.z = 0.18;
            actor.leftLeg.rotation.z = -0.18;
            actor.rightArm.rotation.x *= 0.35;
            actor.leftArm.rotation.x *= 0.35;
        }

        if (sleeping) {
            actor.model.rotation.z = Math.PI / 2;
            actor.model.position.y += 0.65;
            actor.rightArm.rotation.x = 0;
            actor.leftArm.rotation.x = 0;
            actor.rightLeg.rotation.x = 0;
            actor.leftLeg.rotation.x = 0;
        }

        var vy = Number(data.vy || 0);
        if (!onGround && !fallFlying && !swimming && !crawling && !climbing) {
            if (vy > 0.035) {
                actor.rightArm.rotation.x -= 0.32;
                actor.leftArm.rotation.x -= 0.32;
                actor.rightLeg.rotation.x += 0.18;
                actor.leftLeg.rotation.x += 0.18;
            } else if (vy < -0.055) {
                actor.rightArm.rotation.x += 0.24;
                actor.leftArm.rotation.x += 0.24;
                actor.rightLeg.rotation.x -= 0.12;
                actor.leftLeg.rotation.x -= 0.12;
            }
        }

        if (actor.wasOnGround === false && onGround) {
            actor.landing = 0.18;
        }
        actor.wasOnGround = onGround;
        if (actor.landing > 0) {
            actor.landing = Math.max(0, actor.landing - dt);
            var land = Math.sin((actor.landing / 0.18) * Math.PI);
            actor.model.position.y -= land * 0.06;
            actor.body.rotation.x += land * 0.14;
            actor.rightLeg.rotation.x += land * 0.18;
            actor.leftLeg.rotation.x += land * 0.18;
        }

        if (flag(data.swinging)) {
            var swing = Math.sin(now * 0.021);
            var right = String(data.mainArm || "right") !== "left";
            var arm = right ? actor.rightArm : actor.leftArm;
            arm.rotation.x = -1.2 + swing * 0.52;
            actor.body.rotation.y += (right ? -1 : 1) * 0.12 * Math.abs(swing);
        } else if (flag(data.usingItem)) {
            var off = String(data.usedHand || "") === "off_hand";
            var mainLeft = String(data.mainArm || "right") === "left";
            var useLeft = off ? !mainLeft : mainLeft;
            var useArm = useLeft ? actor.leftArm : actor.rightArm;
            useArm.rotation.x = -1.18;
            useArm.rotation.y = useLeft ? 0.18 : -0.18;
        }

        var relativeHeadYaw = radians(Number(data.headYaw || 0) - Number(data.bodyYaw || 0));
        actor.head.rotation.y = clamp(-relativeHeadYaw, -1.35, 1.35);
        actor.head.rotation.x = clamp(radians(data.pitch), -1.25, 1.25);
        if (!moving && !sleeping && !fallFlying) {
            actor.head.rotation.z = Math.sin(now * 0.0011) * 0.018;
        }
    }

    function updateActor(actor, data) {
        if (!!data.slim !== actor.slim) {
            var id = data.uuid;
            removeActor(id);
            actors[id] = createActor(data);
            return;
        }

        actor.data = data;
        actor.target.set(Number(data.x), Number(data.y), Number(data.z));
        actor.targetYaw = -radians(data.bodyYaw);
        applySkin(actor, data.skinUrl);
        actor.root.visible = mapVisible(data);

        if (actor.root.position.distanceToSquared(actor.target) > 4096) {
            actor.root.position.copy(actor.target);
            actor.root.rotation.y = actor.targetYaw;
        }
    }

    function applyFeed(feed) {
        if (disposed) return;
        if (Number(feed.intervalMs) > 0) {
            intervalMs = Number(feed.intervalMs);
        }

        var present = Object.create(null);
        (feed.players || []).forEach(function (data) {
            if (!data || !data.uuid) return;
            present[data.uuid] = true;
            var actor = actors[data.uuid];
            if (!actor) {
                actor = actors[data.uuid] = createActor(data);
            } else {
                updateActor(actor, data);
            }
            actor.root.visible = mapVisible(data);
        });

        Object.keys(actors).forEach(function (id) {
            if (!present[id]) removeActor(id);
        });
    }

    function poll() {
        if (disposed) return;
        fetch(FEED_URL, {cache: "no-store"})
            .then(function (response) {
                if (!response.ok) throw new Error("HTTP " + response.status);
                return response.json();
            })
            .then(applyFeed)
            .catch(function (error) {
                console.warn(LOG, "feed poll failed:", error && error.message ? error.message : error);
            })
            .then(function () {
                if (!disposed) {
                    pollTimer = setTimeout(poll, Math.max(50, intervalMs));
                }
            });
    }

    function onFrame() {
        if (disposed) return;

        var now = performance.now();
        var frameMs = lastFrame ? Math.min(MAX_FRAME_MS, now - lastFrame) : 16;
        lastFrame = now;
        var dt = frameMs / 1000;
        var blend = 1 - Math.pow(0.002, frameMs / Math.max(50, intervalMs));

        Object.keys(actors).forEach(function (id) {
            var actor = actors[id];
            actor.root.position.lerp(actor.target, blend);

            var turn = shortestAngle(actor.root.rotation.y, actor.targetYaw);
            actor.root.rotation.y += turn * blend;

            animatePose(actor, now, dt);
        });

        if (viewer && Object.keys(actors).length) {
            viewer.redraw();
        }
    }

    function applyVisibility() {
        Object.keys(actors).forEach(function (id) {
            actors[id].root.visible = mapVisible(actors[id].data);
        });
    }

    function dispose() {
        if (disposed) return;
        disposed = true;
        if (pollTimer !== null) clearTimeout(pollTimer);

        if (window.bluemap && window.bluemap.events) {
            window.bluemap.events.removeEventListener("bluemapRenderFrame", onFrame);
            window.bluemap.events.removeEventListener("bluemapMapChanged", applyVisibility);
        }

        Object.keys(actors).forEach(removeActor);
        if (viewer && root) {
            viewer.markers.remove(root);
        }
        if (window.__bluemap3dPlayers && window.__bluemap3dPlayers.dispose === dispose) {
            delete window.__bluemap3dPlayers;
        }
    }

    function start() {
        if (window.__bluemap3dPlayers && window.__bluemap3dPlayers.dispose) {
            window.__bluemap3dPlayers.dispose();
        }

        THREE = window.BlueMap.Three;
        viewer = window.bluemap.mapViewer;
        root = new THREE.Group();
        root.name = "bluemap3d-players";
        root.dispose = dispose;
        viewer.markers.add(root);

        window.bluemap.events.addEventListener("bluemapRenderFrame", onFrame);
        window.bluemap.events.addEventListener("bluemapMapChanged", applyVisibility);

        window.__bluemap3dPlayers = {
            build: BUILD,
            actors: actors,
            dispose: dispose,
            stats: function () {
                return {
                    build: BUILD,
                    players: Object.keys(actors).length,
                    intervalMs: intervalMs,
                    map: currentMapId()
                };
            }
        };

        console.info(LOG, BUILD, "attached to BlueMap marker scene");
        poll();
    }

    function waitForBlueMap(attempt) {
        if (ready()) {
            try {
                start();
            } catch (error) {
                console.error(LOG, "failed to start", error);
            }
            return;
        }
        if (attempt > 150) {
            console.warn(LOG, "gave up waiting for BlueMap");
            return;
        }
        setTimeout(function () {
            waitForBlueMap(attempt + 1);
        }, 100);
    }

    waitForBlueMap(0);
})();
