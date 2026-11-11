const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const source = readFileSync('scripts/transforms/geometry_loading.js', 'utf8');
function fixture() {
    let now = 0;
    const timers = new Set();
    const context = vm.createContext({AbortController, setTimeout: fn => (timers.add(fn), fn),
        clearTimeout: fn => timers.delete(fn), Date: {now: () => now}});
    vm.runInContext(source, context);
    return {create: context.createGeometryQueue, advance: value => now += value,
        pump: async () => {
            const pending = [...timers]; timers.clear(); pending.forEach(fn => fn());
            for (let i = 0; i < 12; i++) await Promise.resolve();
        }};
}
test('caps work, prioritizes current demand, deduplicates and releases a dimension on switch', async () => {
    const f = fixture(); let dimension = 'world';
    const calls = []; const finish = [];
    const queue = f.create(url => { calls.push(url); return new Promise(r => finish.push(r)); },
        owner => owner.dimension === dimension ? owner.distance : Infinity, 2);
    const far = queue.load('far', {dimension: 'world', distance: 20});
    const near = queue.load('near', {dimension: 'world', distance: 1});
    const middle = queue.load('middle', {dimension: 'world', distance: 5});
    const foreign = queue.load('nether', {dimension: 'nether', distance: 0});
    assert.equal(queue.load('near', {dimension: 'world', distance: 2}), near);
    await f.pump(); assert.deepEqual(calls, ['near', 'middle']);
    finish[0]('ok'); await f.pump(); assert.deepEqual(calls, ['near', 'middle', 'far']);
    finish[1]('ok'); finish[2]('ok'); await f.pump();
    assert.equal(calls.length, 3);
    dimension = 'nether'; await f.pump(); assert.equal(calls[3], 'nether');
    finish[3]('ok'); await Promise.all([far, near, middle, foreign]); queue.dispose();
});
test('failed loads cool down, retry, and disposal aborts inflight and rejects queued work', async () => {
    const f = fixture(); let signal;
    const queue = f.create((url, currentSignal) => {
        if (url === 'bad') return Promise.reject(new Error('network'));
        signal = currentSignal; return new Promise(() => {});
    }, () => 0, 1);
    const first = queue.load('bad'); const failed = assert.rejects(first, /network/);
    await f.pump(); await failed;
    await assert.rejects(queue.load('bad'), /cooling down/);
    f.advance(1000);
    const retry = queue.load('bad'); const retried = assert.rejects(retry, /network/);
    await f.pump(); await retried;
    const active = queue.load('active'); const queued = queue.load('queued');
    const stopped = [assert.rejects(active, /disposed/), assert.rejects(queued, /disposed/)];
    await f.pump(); queue.dispose(); assert.equal(signal.aborted, true);
    await Promise.all(stopped);
});
test('removed owners are cancelled and explicit historical demand bypasses live gating', async () => {
    const f = fixture(); const calls = [];
    const queue = f.create(url => { calls.push(url); return 'ok'; }, owner => owner ? null : -1, 1);
    const removed = assert.rejects(queue.load('removed', {}), /removed/);
    const historical = queue.load('history');
    await f.pump(); await removed; assert.equal(await historical, 'ok');
    assert.deepEqual(calls, ['history']); queue.dispose();
});
