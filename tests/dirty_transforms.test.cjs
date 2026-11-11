const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
function fixture() {
    const ctx = vm.createContext({});
    vm.runInContext(readFileSync('scripts/transforms/dirty_transforms.js', 'utf8'), ctx);
    return ctx;
}
const sample = x => ({pos: [x, 2, 3], rot: [0, 0, 0, 1], scale: [1, 1, 1]});
const pose = x => [x, 2, 3, 0, 0, 0, 1, 1, 1, 1];
test('stationary roots skip duplicate/new identical snapshots, but movement, scale and replacement remain live', () => {
    const ctx = fixture(), entry = {mesh: {}}, a = sample(1);
    assert.equal(ctx.rootNeedsUpdate(entry, a, a, 0), true);
    for (let frame = 0; frame < 100; frame++) assert.equal(ctx.rootNeedsUpdate(entry, a, a, frame / 100), false);
    assert.equal(ctx.rootNeedsUpdate(entry, sample(1), sample(1), .5), false);
    const b = sample(2);
    assert.equal(ctx.rootNeedsUpdate(entry, a, b, .5), true);
    assert.equal(ctx.rootNeedsUpdate(entry, a, b, .5), false);
    assert.equal(ctx.rootNeedsUpdate(entry, a, b, 1), true);
    assert.equal(ctx.rootNeedsUpdate(entry, sample(2), sample(2), 0), false);
    const scaled = sample(2); scaled.scale = [2, 1, 1];
    assert.equal(ctx.rootNeedsUpdate(entry, b, scaled, .5), true);
    entry.mesh = {};
    assert.equal(ctx.rootNeedsUpdate(entry, b, scaled, .5), true);
});
test('settled instance groups skip equal snapshots and resume after a changed target/count', () => {
    const ctx = fixture(), state = {mesh: {instanceMatrix: {}}, capacity: 4};
    const a = [pose(1), pose(2)];
    assert.equal(ctx.instanceFrameNeeded(state, a, a, 2, .5), true);
    ctx.finishInstanceFrame(state, a, 2, .5, 0, 1);
    for (let frame = 0; frame < 100; frame++) assert.equal(ctx.instanceFrameNeeded(state, a, a, 2, frame / 100), false);
    assert.equal(ctx.instanceFrameNeeded(state, a.map(p => [...p]), a.map(p => [...p]), 2, .2), false);
    const b = [pose(1), pose(3)];
    assert.equal(ctx.instanceFrameNeeded(state, a, b, 2, .5), true);
    ctx.finishInstanceFrame(state, b, 2, .5, 1, 1);
    assert.equal(ctx.instanceFrameNeeded(state, a, b, 2, .5), false);
    assert.equal(ctx.instanceFrameNeeded(state, a, b, 2, 1), true);
    ctx.finishInstanceFrame(state, b, 2, 1, 1, 1);
    assert.equal(ctx.instanceFrameNeeded(state, b, b.map(p => [...p]), 2, 0), false);
    assert.equal(ctx.instanceFrameNeeded(state, [...b, pose(4)], [...b, pose(4)], 3, 0), true);
});
test('dirty upload ranges union pending changes rather than losing an earlier write', () => {
    const ctx = fixture(), range = {offset: 0, count: -1};
    const state = {mesh: {instanceMatrix: {updateRange: range}}, poseStationary: true};
    ctx.finishInstanceFrame(state, [], 0, 1, 3, 3);
    assert.deepEqual(range, {offset: 48, count: 16});
    ctx.finishInstanceFrame(state, [], 0, 1, 1, 1);
    assert.deepEqual(range, {offset: 16, count: 48});
    assert.equal(ctx.transformWork.instanceUploads, 2);
});
