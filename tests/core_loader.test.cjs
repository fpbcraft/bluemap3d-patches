const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const source = readFileSync('scripts/transforms/core_loader.js', 'utf8');
const flush = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); };
function fixture(manifest) {
    const nodes = []; const timers = []; const calls = [];
    const context = {window: {}, console: {error() {}}, setTimeout: fn => timers.push(fn),
        fetch: async (url, options) => { calls.push([url, options]); return {ok: true, json: async () => manifest}; },
        document: {createElement: () => ({remove() {}}), body: {appendChild: node => nodes.push(node)}}};
    vm.runInNewContext(source, context);
    return {context, nodes, calls, timers};
}
test('revalidates the manifest and loads a reusable hashed core only once', async () => {
    const url = `assets/bluemap3d/bluemap3d.core-${'a'.repeat(64)}.js`;
    const f = fixture({script: url}); await flush();
    assert.equal(f.calls[0][1].cache, 'no-cache');
    assert.equal(f.nodes[0].src, url);
    f.nodes[0].onload(); await flush();
    vm.runInNewContext(source, f.context); await flush();
    assert.equal(f.calls.length, 1); assert.equal(f.nodes.length, 1);
});
test('rejects unexpected paths, bounds retries, and clears guard after failure', async () => {
    const f = fixture({script: 'https://other.example/code.js'}); await flush();
    for (let i = 0; i < 2; i++) { f.timers.shift()(); await flush(); }
    assert.equal(f.calls.length, 3); assert.equal(f.nodes.length, 0);
    assert.equal(f.context.window.__bluemap3dLoaded, undefined);
});
test('a script download failure retries through the manifest', async () => {
    const f = fixture({script: `assets/bluemap3d/bluemap3d.core-${'b'.repeat(64)}.js`});
    await flush(); f.nodes[0].onerror(); await flush();
    f.timers.shift()(); await flush();
    assert.equal(f.calls.length, 2); assert.equal(f.nodes.length, 2);
    f.nodes[1].onload(); await flush();
});
