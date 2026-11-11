"""Reduce stationary root/instance work while preserving continuous node animation."""
from pathlib import Path

web = Path("core/src/main/resources/assets/bluemap3d/web/bluemap3d.core.js")
source = web.read_text()

def inject(old, new):
    global source
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"dirty transforms expected one anchor, found {count}: {old[:100]!r}")
    source = source.replace(old, new, 1)

inject('var BUILD = "core-history-44-geometry-loading";', 'var BUILD = "core-history-45-dirty-transforms";')
inject('    function groupsById(groups) {', Path(__file__).with_suffix('.js').read_text() + '\n    function groupsById(groups) {')
inject('''        if (!groups) return result;
        for (var i = 0; i < groups.length; i++) {''', '''        if (!groups) return result;
        var cached = groupIndexCache.get(groups);
        if (cached) return cached;
        groupIndexCache.set(groups, result);
        for (var i = 0; i < groups.length; i++) {''')
inject('''            state.mesh.count = count;

            for (var i = 0; i < count; i++) {''', '''            state.mesh.count = count;
            if (!instanceFrameNeeded(state, sourceInstances, targetInstances, count, alpha)) return;
            var firstDirty = count, lastDirty = -1;

            for (var i = 0; i < count; i++) {''')
inject('''                if (!a || !b) continue;

                instancePosition.set(''', '''                if (!a || !b || instancePoseUnchanged(state, i, a, b)) continue;

                instancePosition.set(''')
inject('''                state.mesh.setMatrixAt(i, instanceMatrix);
            }

            state.mesh.instanceMatrix.needsUpdate = true;''', '''                state.mesh.setMatrixAt(i, instanceMatrix);
                rememberInstancePose(state, i, instancePosition, instanceFromQ, instanceScale);
                firstDirty = Math.min(firstDirty, i);
                lastDirty = Math.max(lastDirty, i);
            }

            finishInstanceFrame(state, targetInstances, count, alpha, firstDirty, lastDirty);''')
# Gate root transforms only: animation nodes below this block must keep running.
start = source.index('        mesh.position.set(', source.index('    function writeTransform(entry, alpha)'))
end = source.index('        if (entry.isInstanced) {', start)
block = source[start:end]
marker = '        if (shipMarkers) shipMarkers.position(entry, mesh.position);\n'
if marker not in block: raise SystemExit('dirty transforms missing named marker hook')
block = block.replace(marker, '')
source = source[:start] + '        if (rootNeedsUpdate(entry, from, to, alpha)) {\n' + block + '            mesh.updateMatrix();\n        }\n' + marker + '\n' + source[end:]
inject('''        group.matrixAutoUpdate = true;
        if (row.label)''', '''        group.matrixAutoUpdate = false;
        if (row.label)''')
inject('''        mesh.frustumCulled = true;
        mesh.matrixAutoUpdate = true;''', '''        mesh.frustumCulled = true;
        mesh.matrixAutoUpdate = false;''')
inject('''                    scenePerf: browserScenePerf()''', '''                    scenePerf: browserScenePerf(),
                    transformWork: Object.assign({}, transformWork)''')
web.write_text(source)
