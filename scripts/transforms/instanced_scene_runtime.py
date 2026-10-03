from pathlib import Path

p = Path("core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js")
s = p.read_text()

metric_vars_needle = '''    var pollCount = 0;
    var frameCount = 0;
'''
if metric_vars_needle not in s:
    raise SystemExit("web instance metric variable insertion point not found")
s = s.replace(
    metric_vars_needle,
    '''    var pollCount = 0;
    var frameCount = 0;
    var lastFeedBytes = 0;
    var lastServerPerf = null;
    var textEncoder = typeof TextEncoder !== "undefined" ? new TextEncoder() : null;
''',
    1,
)

# Measure the actual UTF-8 payload, not JS string length.
poll_json_needle = '''                return response.json();
            })
            .then(applyFeed)
'''
if poll_json_needle not in s:
    raise SystemExit("web feed-byte instrumentation insertion point not found")
s = s.replace(
    poll_json_needle,
    '''                return response.text().then(function (text) {
                    lastFeedBytes = textEncoder ? textEncoder.encode(text).length : text.length;
                    return JSON.parse(text);
                });
            })
            .then(applyFeed)
''',
    1,
)

perf_feed_needle = '''        dimensionMaps = feed.maps || {};
        if (typeof feed.tileReloadMinMs === "number") {
'''
if perf_feed_needle not in s:
    raise SystemExit("web server perf insertion point not found")
s = s.replace(
    perf_feed_needle,
    '''        dimensionMaps = feed.maps || {};
        lastServerPerf = feed.perf || null;
        if (typeof feed.tileReloadMinMs === "number") {
''',
    1,
)

entry_needle = '''                    mesh: null,
                    meshUrl: null,
                    dimension: row.dimension,
                    from: null,
                    to: null,
                    odometer: 0,
                    segmentTravel: 0
'''
if entry_needle not in s:
    raise SystemExit("web instance entry insertion point not found")
s = s.replace(
    entry_needle,
    '''                    mesh: null,
                    meshUrl: null,
                    dimension: row.dimension,
                    from: null,
                    to: null,
                    odometer: 0,
                    segmentTravel: 0,
                    isInstanced: false,
                    instanceGroups: Object.create(null)
''',
    1,
)

mesh_swap_needle = '''            /* A new mesh url means the geometry version changed, so swap the mesh but
             * keep the interpolation state - the object has not teleported. */
            if (entry.meshUrl !== row.mesh) {
                entry.meshUrl = row.mesh;
                debug("mesh: loading", row.mesh, "for", row.id);
                loadMesh(row.mesh).then(function (resource) {
                    var live = objects[row.id];
                    if (!live || live.meshUrl !== row.mesh) {
                        return;
                    }
                    replaceMesh(live, resource, row);
                    console.debug(LOG, "mesh ready:", row.label || row.id,
                        resource.geometry.attributes.position.count + " verts");
                }).catch(function (e) {
                    console.error(LOG, "could not load", row.mesh, e);
                });
            }
'''
if mesh_swap_needle not in s:
    raise SystemExit("web instance/rigid mesh dispatch insertion point not found")
s = s.replace(
    mesh_swap_needle,
    '''            if (row.groups && row.groups.length) {
                syncInstanceGroups(entry, row);
            } else {
                if (entry.isInstanced) {
                    clearInstanceGroups(entry);
                }

                /* A new mesh url means the geometry version changed, so swap the mesh but
                 * keep the interpolation state - the object has not teleported. */
                if (entry.meshUrl !== row.mesh) {
                    entry.meshUrl = row.mesh;
                    debug("mesh: loading", row.mesh, "for", row.id);
                    loadMesh(row.mesh).then(function (resource) {
                        var live = objects[row.id];
                        if (!live || live.meshUrl !== row.mesh || live.isInstanced) {
                            return;
                        }
                        replaceMesh(live, resource, row);
                        console.debug(LOG, "mesh ready:", row.label || row.id,
                            resource.geometry.attributes.position.count + " verts");
                    }).catch(function (e) {
                        console.error(LOG, "could not load", row.mesh, e);
                    });
                }
            }
''',
    1,
)

sample_needle = '''            var sample = {
                t: now,
                pos: row.pos,
                rot: row.rot,
                scale: row.scale || [1, 1, 1]
            };
'''
if sample_needle not in s:
    raise SystemExit("web instance sample insertion point not found")
s = s.replace(
    sample_needle,
    '''            var sample = {
                t: now,
                pos: row.pos,
                rot: row.rot,
                scale: row.scale || [1, 1, 1],
                groups: row.groups || null
            };
''',
    1,
)

remove_needle = '''    function remove(id) {
        var entry = objects[id];
        delete objects[id];
        if (entry && entry.mesh) {
            root.remove(entry.mesh);
            /* Geometry and material live in meshCache and may be shared with another
             * object, so they are not disposed here. */
        }
    }

    function replaceMesh(entry, resource, row) {
'''
if remove_needle not in s:
    raise SystemExit("web instance helper insertion point not found")

instance_helpers = r'''    function remove(id) {
        var entry = objects[id];
        delete objects[id];
        if (entry && entry.mesh) {
            root.remove(entry.mesh);
            /* Geometry and material live in meshCache and may be shared with another
             * object, so they are not disposed here. */
        }
        if (entry) {
            entry.instanceGroups = Object.create(null);
        }
    }

    function nextInstanceCapacity(count) {
        var capacity = 1;
        while (capacity < count) capacity <<= 1;
        return capacity;
    }

    function clearInstanceGroups(entry) {
        if (entry.mesh) {
            root.remove(entry.mesh);
        }
        entry.mesh = null;
        entry.meshUrl = null;
        entry.isInstanced = false;
        entry.instanceGroups = Object.create(null);
    }

    function ensureInstanceRoot(entry, row) {
        if (entry.isInstanced && entry.mesh) {
            return;
        }

        if (entry.mesh) {
            root.remove(entry.mesh);
        }

        var group = new THREE.Group();
        group.matrixAutoUpdate = true;
        if (row.label) {
            group.name = row.label;
        }

        entry.mesh = group;
        entry.meshUrl = null;
        entry.isInstanced = true;
        entry.instanceGroups = Object.create(null);
        root.add(group);
        applyVisibility();
    }

    function syncInstanceGroups(entry, row) {
        ensureInstanceRoot(entry, row);
        var presentGroups = Object.create(null);

        (row.groups || []).forEach(function (groupRow) {
            presentGroups[groupRow.id] = true;
            var count = groupRow.instances ? groupRow.instances.length : 0;
            var state = entry.instanceGroups[groupRow.id];

            if (
                state
                && state.meshUrl === groupRow.mesh
                && count <= state.capacity
                && (count === 0 || state.mesh)
            ) {
                state.count = count;
                if (state.mesh) {
                    state.mesh.count = count;
                }
                return;
            }

            var oldToken = state ? state.token : 0;
            if (state && state.mesh && entry.mesh) {
                entry.mesh.remove(state.mesh);
            }

            var capacity = nextInstanceCapacity(Math.max(1, count));
            state = entry.instanceGroups[groupRow.id] = {
                meshUrl: groupRow.mesh,
                mesh: null,
                capacity: capacity,
                count: count,
                token: oldToken + 1
            };
            var token = state.token;

            if (!groupRow.mesh || count === 0) {
                return;
            }

            loadMesh(groupRow.mesh).then(function (resource) {
                var live = entry.instanceGroups[groupRow.id];
                if (!entry.isInstanced || !live || live.token !== token
                        || live.meshUrl !== groupRow.mesh || !entry.mesh) {
                    return;
                }

                resource.geometry.setDrawRange(0, resource.staticIndexCount);
                if (resource.nodes && resource.nodes.length) {
                    console.warn(LOG,
                        "animated BM3D nodes are not supported inside instance groups:",
                        groupRow.mesh);
                }

                var mesh = new THREE.InstancedMesh(
                    resource.geometry,
                    resource.material,
                    live.capacity);
                mesh.count = live.count;
                mesh.matrixAutoUpdate = false;
                /* three.js' automatic instance bounds are expensive to recompute for
                 * continuously deforming ropes. A rope/spring group is only one draw call,
                 * so avoiding incorrect culling is the better tradeoff. */
                mesh.frustumCulled = false;
                if (mesh.instanceMatrix && THREE.DynamicDrawUsage !== undefined) {
                    mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
                }

                live.mesh = mesh;
                entry.mesh.add(mesh);
                writeInstanceTransforms(entry, 1);
            }).catch(function (e) {
                console.error(LOG, "could not load instance prototype", groupRow.mesh, e);
            });
        });

        Object.keys(entry.instanceGroups).forEach(function (groupId) {
            if (presentGroups[groupId]) return;
            var state = entry.instanceGroups[groupId];
            if (state && state.mesh && entry.mesh) {
                entry.mesh.remove(state.mesh);
            }
            delete entry.instanceGroups[groupId];
        });
    }

    var instancePosition = null;
    var instanceScale = null;
    var instanceFromQ = null;
    var instanceToQ = null;
    var instanceMatrix = null;

    function groupsById(groups) {
        var result = Object.create(null);
        if (!groups) return result;
        for (var i = 0; i < groups.length; i++) {
            result[groups[i].id] = groups[i];
        }
        return result;
    }

    function writeInstanceTransforms(entry, alpha) {
        if (!entry.isInstanced || !entry.instanceGroups) return;
        var from = entry.from || entry.to;
        var to = entry.to;
        if (!from || !to) return;

        if (!instancePosition) {
            instancePosition = new THREE.Vector3();
            instanceScale = new THREE.Vector3();
            instanceFromQ = new THREE.Quaternion();
            instanceToQ = new THREE.Quaternion();
            instanceMatrix = new THREE.Matrix4();
        }

        var fromGroups = groupsById(from.groups);
        var toGroups = groupsById(to.groups);

        Object.keys(entry.instanceGroups).forEach(function (groupId) {
            var state = entry.instanceGroups[groupId];
            if (!state || !state.mesh) return;

            var targetGroup = toGroups[groupId];
            if (!targetGroup || !targetGroup.instances) {
                state.mesh.count = 0;
                return;
            }

            var sourceGroup = fromGroups[groupId] || targetGroup;
            var sourceInstances = sourceGroup.instances || [];
            var targetInstances = targetGroup.instances || [];
            var count = Math.min(targetInstances.length, state.capacity);
            state.mesh.count = count;

            for (var i = 0; i < count; i++) {
                var b = targetInstances[i];
                var a = sourceInstances[i] || b;
                if (!a || !b) continue;

                instancePosition.set(
                    a[0] + (b[0] - a[0]) * alpha,
                    a[1] + (b[1] - a[1]) * alpha,
                    a[2] + (b[2] - a[2]) * alpha);

                instanceFromQ.set(a[3], a[4], a[5], a[6]);
                instanceToQ.set(b[3], b[4], b[5], b[6]);
                instanceFromQ.slerp(instanceToQ, alpha);

                instanceScale.set(
                    a[7] + (b[7] - a[7]) * alpha,
                    a[8] + (b[8] - a[8]) * alpha,
                    a[9] + (b[9] - a[9]) * alpha);

                instanceMatrix.compose(instancePosition, instanceFromQ, instanceScale);
                state.mesh.setMatrixAt(i, instanceMatrix);
            }

            state.mesh.instanceMatrix.needsUpdate = true;
        });
    }

    function createReplayInstanceGroup(url, count, label) {
        return loadMesh(url).then(function (resource) {
            resource.geometry.setDrawRange(0, resource.staticIndexCount);
            var mesh = new THREE.InstancedMesh(
                resource.geometry,
                resource.material,
                Math.max(1, count));
            mesh.count = count;
            mesh.matrixAutoUpdate = false;
            mesh.frustumCulled = false;
            if (mesh.instanceMatrix && THREE.DynamicDrawUsage !== undefined) {
                mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage);
            }
            if (label) mesh.name = label;
            return mesh;
        });
    }

    function setReplayInstances(mesh, instances) {
        if (!mesh || !instances) return;
        if (!instancePosition) {
            instancePosition = new THREE.Vector3();
            instanceScale = new THREE.Vector3();
            instanceFromQ = new THREE.Quaternion();
            instanceToQ = new THREE.Quaternion();
            instanceMatrix = new THREE.Matrix4();
        }

        var count = Math.min(instances.length, mesh.instanceMatrix.count);
        mesh.count = count;
        for (var i = 0; i < count; i++) {
            var value = instances[i];
            instancePosition.set(value[0], value[1], value[2]);
            instanceFromQ.set(value[3], value[4], value[5], value[6]);
            instanceScale.set(value[7], value[8], value[9]);
            instanceMatrix.compose(instancePosition, instanceFromQ, instanceScale);
            mesh.setMatrixAt(i, instanceMatrix);
        }
        mesh.instanceMatrix.needsUpdate = true;
    }

    function replaceMesh(entry, resource, row) {
'''
s = s.replace(remove_needle, instance_helpers, 1)

# When a rigid object replaces an instanced one, reset the instance state first.
replace_mesh_start = '''    function replaceMesh(entry, resource, row) {
        /* Captured before anything below overwrites entry.nodes / entry.rateAngles, so a
'''
if replace_mesh_start not in s:
    raise SystemExit("web rigid replacement reset insertion point not found")
s = s.replace(
    replace_mesh_start,
    '''    function replaceMesh(entry, resource, row) {
        entry.isInstanced = false;
        entry.instanceGroups = Object.create(null);
        /* Captured before anything below overwrites entry.nodes / entry.rateAngles, so a
''',
    1,
)

scale_tail_needle = '''        mesh.scale.set(
            fromScale[0] + (toScale[0] - fromScale[0]) * alpha,
            fromScale[1] + (toScale[1] - fromScale[1]) * alpha,
            fromScale[2] + (toScale[2] - fromScale[2]) * alpha
        );

        if (entry.nodeGroups) {
'''
if scale_tail_needle not in s:
    raise SystemExit("web instance transform insertion point not found")
s = s.replace(
    scale_tail_needle,
    '''        mesh.scale.set(
            fromScale[0] + (toScale[0] - fromScale[0]) * alpha,
            fromScale[1] + (toScale[1] - fromScale[1]) * alpha,
            fromScale[2] + (toScale[2] - fromScale[2]) * alpha
        );

        if (entry.isInstanced) {
            writeInstanceTransforms(entry, alpha);
        }

        if (entry.nodeGroups) {
''',
    1,
)

# Public diagnostics and replay hooks for instance groups.
api_needle = '''            createReplayMesh: createReplayMesh,
            setReplayAnimation: setReplayAnimation,
            setSuppressedObjects: setSuppressedObjects,
'''
if api_needle not in s:
    raise SystemExit("web instance public API insertion point not found")
s = s.replace(
    api_needle,
    '''            createReplayMesh: createReplayMesh,
            createReplayInstanceGroup: createReplayInstanceGroup,
            setReplayInstances: setReplayInstances,
            setReplayAnimation: setReplayAnimation,
            setSuppressedObjects: setSuppressedObjects,
''',
    1,
)

stats_needle = '''                    msSinceTileReload: lastTileReload ? Date.now() - lastTileReload : null,
                    renderLoopRunning: frameCount > 0
'''
if stats_needle not in s:
    raise SystemExit("web instance diagnostics insertion point not found")
stats_replacement = '''                    msSinceTileReload: lastTileReload ? Date.now() - lastTileReload : null,
                    renderLoopRunning: frameCount > 0,
                    feedBytes: lastFeedBytes,
                    serverPerf: lastServerPerf,
                    scenePerf: browserScenePerf()
'''
s = s.replace(stats_needle, stats_replacement, 1)

stats_anchor = '''    function waitForBlueMap(attempt) {
'''
if stats_anchor not in s:
    raise SystemExit("web instance scene perf helper insertion point not found")
stats_helper = '''    function browserScenePerf() {
        var normalMeshes = 0;
        var instanceGroups = 0;
        var instances = 0;
        var estimatedDrawCalls = 0;

        Object.keys(objects).forEach(function (id) {
            var entry = objects[id];
            if (entry.isInstanced) {
                Object.keys(entry.instanceGroups || {}).forEach(function (groupId) {
                    var state = entry.instanceGroups[groupId];
                    if (!state || !state.mesh) return;
                    instanceGroups++;
                    instances += state.mesh.count || 0;
                    estimatedDrawCalls++;
                });
            } else if (entry.mesh) {
                normalMeshes++;
                estimatedDrawCalls += 1 + (entry.nodeGroups ? entry.nodeGroups.length : 0);
            }
        });

        return {
            logicalObjects: Object.keys(objects).length,
            normalMeshes: normalMeshes,
            instanceGroups: instanceGroups,
            instances: instances,
            estimatedDrawCalls: estimatedDrawCalls
        };
    }

'''
s = s.replace(stats_anchor, stats_helper + stats_anchor, 1)

p.write_text(s)
