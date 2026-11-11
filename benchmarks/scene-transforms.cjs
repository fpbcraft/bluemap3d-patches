// BASELINE_CORE=... CANDIDATE_CORE=... THREE_MODULE=... node benchmarks/scene-transforms.cjs
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const THREE = require(process.env.THREE_MODULE);
const extract = (source, name, end) => {
    const start = source.indexOf(`    function ${name}(`);
    assert(start >= 0 && source.indexOf(end, start) > start, `missing ${name}`);
    return source.slice(start, source.indexOf(end, start));
};
for (const label of ['before', 'after']) {
    const source = fs.readFileSync(process.env[label === 'before' ? 'BASELINE_CORE' : 'CANDIDATE_CORE'], 'utf8');
    let now = 0;
    const context = vm.createContext({THREE, performance: {now: () => now}, shipMarkers: null, _scratch: new THREE.Vector3(),
        instancePosition: null, instanceScale: null, instanceFromQ: null, instanceToQ: null, instanceMatrix: null,
        KIND_SPIN: 0, KIND_OSCILLATE: 1, KIND_ORBIT: 2, KIND_RATE: 3, KIND_LOOP: 4, KIND_UV_SCROLL: 5, MAX_RATE_DT_SECONDS: 1});
    const helper = label === 'after' ? fs.readFileSync(require('node:path').join(__dirname, '../scripts/transforms/dirty_transforms.js'), 'utf8') : '';
    vm.runInContext(helper + extract(source, 'groupsById', '    function writeInstanceTransforms') +
        extract(source, 'writeInstanceTransforms', '    function createReplayInstanceGroup') +
        extract(source, 'writeTransform', '    var _scratch = null;'), context);
    const scene = new THREE.Scene();
    const pose = {pos: [1, 2, 3], rot: [0, 0, 0, 1], scale: [1, 1, 1]};
    const entries = Array.from({length: 6749}, () => {
        const mesh = new THREE.Object3D(); mesh.matrixAutoUpdate = label === 'before'; scene.add(mesh);
        return {mesh, from: pose, to: pose, nodeGroups: [], nodes: []};
    });
    const instances = Array.from({length: 5000}, (_, i) => [i, 0, 0, 0, 0, 0, 1, 1, 1, 1]);
    const mesh = new THREE.InstancedMesh(new THREE.BoxGeometry(), new THREE.MeshBasicMaterial(), instances.length);
    const entry = {isInstanced: true, instanceGroups: {g: {capacity: instances.length, mesh}},
        from: {groups: [{id: 'g', instances}]}, to: {groups: [{id: 'g', instances}]}};
    function frame() {
        now += 16;
        for (const entry of entries) context.writeTransform(entry, 1);
        context.writeInstanceTransforms(entry, 1);
        scene.updateMatrixWorld(true);
    }
    for (let i = 0; i < 30; i++) frame();
    const uploadsBefore = mesh.instanceMatrix.version;
    const samples = [];
    for (let trial = 0; trial < 5; trial++) {
        const start = performance.now();
        for (let i = 0; i < 60; i++) frame();
        samples.push((performance.now() - start) / 60);
    }
    const matrix = new THREE.Matrix4(); mesh.getMatrixAt(4999, matrix);
    assert.equal(matrix.elements[12], 4999);
    assert.equal(entries[0].mesh.matrixWorld.elements[12], 1);
    console.log(JSON.stringify({label, objects: entries.length, instances: instances.length,
        cpuMsPerFrameTrials: samples, bufferUpdateSignals: mesh.instanceMatrix.version - uploadsBefore,
        work: context.transformWork || null}));
    mesh.geometry.dispose(); mesh.material.dispose();
}
