const test = require('node:test');
const assert = require('node:assert/strict');
const api = require('../../addon-compat/src/main/resources/bluemap-seasons/seasonal.js');

test('neutral palette is reversible; colours preserve per-channel shading', () => {
    assert.deepEqual(api.ratio(0x123456, 0xffffff, 0), [1, 1, 1]);
    assert.deepEqual(api.ratio(0x204080, 0x408040, 1), [2, 2, 0.5]);
    assert.deepEqual(api.ratio(0, 0xffffff, 1), [4, 4, 4]);
});
test('negative world coordinates and signed surface height decode correctly', () => {
    const image = new Uint8Array(512 * 1024 * 4);
    const i = (511 * 512 + 511) * 4, h = i + 512 * 512 * 4;
    image[i] = 1; image[i + 1] = 44; image[i + 2] = 5;
    image[h] = 127; image[h + 1] = 240;
    assert.deepEqual(api.decode(image, -1, -513), {biome: 300, flags: 5, height: -16});
});
test('texture classification excludes specialised leaves, buildings and grass sides', () => {
    assert.equal(api.materialKind('minecraft:block/grass_block_top'), 1);
    assert.equal(api.materialKind('minecraft:block/oak_leaves'), 2);
    assert.equal(api.materialKind('minecraft:block/birch_leaves'), 0);
    assert.equal(api.materialKind('mod:block/grass_block_top'), 0);
    assert.equal(api.snowMaterial('minecraft:block/grass_block_side'), false);
    assert.equal(api.snowMaterial('minecraft:block/stone'), true);
    assert.equal(api.snowMaterial('minecraft:block/stone_bricks'), false);
});
test('shader adapters fail closed on changed upstream anchors', () => {
    assert.equal(api.shader('void main() {}', false), null);
    const high = api.shader('varying vec3 vPosition; void main() { //apply ao\n }', false);
    assert.ok(high.includes('vNormal.y > 0.99'));
    assert.ok(high.includes('abs(vPosition.y - sy) < 0.05'));
    const low = api.shader('void main() { vec4 meta = texture(textureImage, posToMetaUV(vPosition.xz)); }', true);
    assert.ok(low.includes('seasonGrass'));
    assert.ok(!low.includes('vNormal'));
});
