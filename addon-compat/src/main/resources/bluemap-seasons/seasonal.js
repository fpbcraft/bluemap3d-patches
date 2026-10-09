/* BlueMap 5.7 seasonal surface layer. Terrain geometry and world blocks stay untouched. */
(function (root) {
    'use strict';
    const BASE = 'assets/bluemap-seasons/';
    const RGB = n => [(n >> 16) & 255, (n >> 8) & 255, n & 255];
    function ratio(base, target, mix) {
        return RGB(base).map((b, i) => Math.min(4, Math.max(0, (b * (1 - mix) + RGB(target)[i] * mix) / Math.max(b, 1))));
    }
    function decode(pixels, x, z) {
        const i = ((z % 512 + 512) % 512 * 512 + (x % 512 + 512) % 512) * 4;
        const h = i + 512 * 512 * 4;
        return {biome: pixels[i] * 256 + pixels[i + 1], flags: pixels[i + 2],
            height: pixels[h] * 256 + pixels[h + 1] - 32768};
    }
    function materialKind(path) {
        if (/^minecraft:block\/(grass_block_top|grass_block_side_overlay|short_grass|tall_grass_(top|bottom)|fern|large_fern_(top|bottom))$/.test(path)) return 1;
        if (/^minecraft:block\/(oak|jungle|acacia|dark_oak)_leaves$/.test(path) || path === 'minecraft:block/vine') return 2;
        return 0;
    }
    function snowMaterial(path) {
        return /^minecraft:block\/(grass_block_top|dirt|coarse_dirt|podzol_top|mycelium_top|stone|gravel)$/.test(path);
    }
    const declarations = `
        uniform sampler2D seasonGrass;
        uniform sampler2D seasonLeaves;
        uniform sampler2D seasonHeight;
        uniform sampler2D seasonSnow;
        uniform vec4 seasonBounds;
        uniform vec4 seasonWorld;
        uniform float seasonKind;
        uniform float seasonSnowAllowed;
        uniform float seasonActive;
        vec2 seasonUV() { return clamp((vPosition.xz - seasonBounds.xy) / seasonBounds.zw, 0.0, 1.0); }
        float seasonNoise(vec2 p) { return fract(sin(dot(floor(p), vec2(12.9898,78.233))) * 43758.5453); }
    `;
    function shader(source, lowres) {
        const anchor = lowres ? 'vec4 meta = texture(textureImage, posToMetaUV(vPosition.xz));' : '//apply ao';
        if (!source.includes(anchor) || !source.includes('void main()')) return null;
        const body = `
            if (seasonActive > 0.5) {
                vec2 suv = seasonUV();
                vec4 grassEffect = texture(seasonGrass, suv);
                vec4 leavesEffect = texture(seasonLeaves, suv);
                vec3 factor = ${lowres ? 'grassEffect.rgb * (255.0 / 64.0)' : '(seasonKind < 0.5 ? vec3(1.0) : (seasonKind < 1.5 ? grassEffect.rgb : leavesEffect.rgb) * (255.0 / 64.0))'};
                color.rgb *= factor;
                vec4 sh = texture(seasonHeight, suv);
                float sy = sh.r * 65280.0 + sh.g * 255.0 - 32768.0;
                vec2 wp = vPosition.xz * seasonWorld.zw + seasonWorld.xy;
                bool snowy = ${lowres ? 'true' : 'seasonSnowAllowed > 0.5 && vNormal.y > 0.99 && abs(vPosition.y - sy) < 0.05'};
                if (snowy && sh.b > 0.5 && grassEffect.a > seasonNoise(wp)) {
                    color.rgb = ${lowres ? 'vec3(0.92)' : 'texture(seasonSnow, fract(wp)).rgb'};
                }
            }
        `;
        return source.replace('void main()', declarations + '\nvoid main()').replace(anchor, body + '\n' + anchor);
    }
    const helpers = {ratio, decode, materialKind, snowMaterial, shader};
    if (typeof module !== 'undefined' && module.exports) { module.exports = helpers; return; }
    if (root.__bluemapSeasons) return;
    const status = root.__bluemapSeasons = {tiles: 0, regions: 0, error: null};
    let THREE, viewer, state = null, lastSuccess = 0, map = null, metadata = [], generation = 0;
    let paletteKey = '', paletteRevision = 0, inflight = 0;
    const records = new Map(), regions = new Map();
    let candidates = [];

    async function readState() {
        try {
            const response = await fetch(BASE + 'state.json', {cache: 'no-store'});
            if (!response.ok) throw Error('state HTTP ' + response.status);
            const next = await response.json();
            if (next.version !== 1 || !next.maps) throw Error('unsupported seasonal state');
            // A stopped/offline publisher must not leave a permanently snowy map.
            if (Object.keys(next.maps).length && Date.now() / 10000 - next.updated > 6) throw Error('stale seasonal state');
            const key = JSON.stringify([next.palette, next.tint, next.snow, next.maps]);
            if (key !== paletteKey) { paletteKey = key; paletteRevision++; }
            state = next; lastSuccess = Date.now(); status.error = null;
        } catch (error) { status.error = String(error); }
    }

    async function region(atlas, rx, rz) {
        const key = atlas + '/' + rx + '_' + rz;
        let entry = regions.get(key);
        if (entry && Date.now() - entry.time < 60000) { regions.delete(key); regions.set(key, entry); return entry.promise; }
        entry = {time: Date.now()};
        entry.promise = (async () => {
            const response = await fetch(BASE + key + '.png', {cache: 'no-cache'});
            if (!response.ok) throw Error('atlas pending: ' + key);
            const bitmap = await createImageBitmap(await response.blob());
            if (bitmap.width !== 512 || bitmap.height !== 1024) { bitmap.close(); throw Error('invalid atlas'); }
            const canvas = document.createElement('canvas'); canvas.width = 512; canvas.height = 1024;
            const context = canvas.getContext('2d', {willReadFrequently: true});
            context.drawImage(bitmap, 0, 0); bitmap.close();
            return context.getImageData(0, 0, 512, 1024).data;
        })();
        regions.set(key, entry);
        while (regions.size > 16) regions.delete(regions.keys().next().value);
        status.regions = regions.size;
        return entry.promise;
    }

    function texture(bytes, width, height) {
        const result = new THREE.DataTexture(bytes, width, height, THREE.RGBAFormat);
        result.magFilter = THREE.NearestFilter; result.minFilter = THREE.NearestFilter;
        result.generateMipmaps = false; result.flipY = false; result.needsUpdate = true;
        return result;
    }

    function dispose(record) {
        if (record.mesh.material === record.material) record.mesh.material = record.original;
        record.owned.forEach(material => material.dispose());
        record.textures.forEach(t => t.dispose());
        if (!record.mesh.parent && record.samples?.lowres) record.original.dispose();
    }

    function reset() {
        generation++; candidates = [];
        records.forEach(dispose); records.clear(); regions.clear(); metadata = []; map = viewer.map;
        if (!map) return;
        const captured = generation;
        fetch(map.data.texturesUrl, {cache: 'no-cache'}).then(r => {
            if (!r.ok) throw Error('texture metadata unavailable'); return r.json();
        }).then(data => { if (captured === generation) metadata = data; }).catch(e => { status.error = String(e); });
    }

    function discover() {
        if (viewer.map !== map) reset();
        const config = state && map && state.maps[map.data.id];
        if (!config || Date.now() - lastSuccess > 60000) {
            records.forEach(record => record.owned.forEach(m => { m.uniforms.seasonActive.value = 0; }));
            viewer.redraw();
            return;
        }
        records.forEach(record => record.owned.forEach(m => { m.uniforms.seasonActive.value = 1; }));
        const found = new Set();
        [map.hiresTileManager, ...(map.lowresTileManager || [])].forEach(manager => {
            if (manager && manager.scene) manager.scene.traverse(mesh => {
                if (mesh.isMesh && (mesh.userData.tileType === 'hires' || mesh.userData.tileType === 'lowres')) found.add(mesh);
            });
        });
        records.forEach((record, mesh) => { if (!found.has(mesh)) { dispose(record); records.delete(mesh); } });
        candidates = [...found].filter(mesh => !records.has(mesh));
        status.tiles = records.size;
    }

    async function samples(mesh, config) {
        const lowres = mesh.userData.tileType === 'lowres';
        const geometry = mesh.geometry;
        if (!geometry.boundingBox) geometry.computeBoundingBox();
        const box = geometry.boundingBox;
        const x0 = Math.floor(box.min.x), z0 = Math.floor(box.min.z);
        const sx = Math.ceil(box.max.x) - x0, sz = Math.ceil(box.max.z) - z0;
        if (sx <= 0 || sz <= 0 || sx > 1024 || sz > 1024) throw Error('unsupported tile bounds');
        // Lowres uses one sample per rendered texel; hires keeps one per block.
        const width = Math.ceil(sx), height = Math.ceil(sz);
        const locations = [], needed = new Map();
        for (let z = 0; z < height; z++) for (let x = 0; x < width; x++) {
            const wx = Math.floor(mesh.position.x + (x0 + x + 0.5) * mesh.scale.x);
            const wz = Math.floor(mesh.position.z + (z0 + z + 0.5) * mesh.scale.z);
            const rx = Math.floor(wx / 512), rz = Math.floor(wz / 512), key = rx + '_' + rz;
            needed.set(key, [rx, rz]); locations.push([key, wx, wz]);
        }
        if (needed.size > 16) throw Error('seasonal tile spans more than 16 regions');
        // Sequential region fetches keep simultaneous downloads bounded by the tile-worker count.
        const loaded = new Map();
        for (const [key, [rx, rz]] of needed) loaded.set(key, await region(config.atlas, rx, rz));
        return {lowres, width, height, x0, z0, sx, sz,
            columns: locations.map(([key, x, z]) => decode(loaded.get(key), x, z))};
    }

    function update(record) {
        const palette = state.palette || [], {columns, lowres} = record.samples;
        const grass = record.textures[0].image.data, leaves = record.textures[1].image.data, heights = record.textures[2].image.data;
        columns.forEach((column, i) => {
            const entry = palette[column.biome - 1];
            const mix = entry && state.tint && !(column.flags & 8) ? entry.mix : 0;
            const g = entry ? ratio(entry.baseGrass, entry.grass, mix) : [1, 1, 1];
            const l = entry ? ratio(entry.baseFoliage, entry.foliage, mix) : [1, 1, 1];
            const kind = column.flags & 3;
            const effect = lowres ? (kind === 1 ? g : kind === 2 ? l : [1, 1, 1]) : g;
            for (let c = 0; c < 3; c++) { grass[i * 4 + c] = Math.min(255, Math.round(effect[c] * 64)); leaves[i * 4 + c] = Math.min(255, Math.round(l[c] * 64)); }
            grass[i * 4 + 3] = entry && state.snow && (column.flags & 4) ? Math.round(entry.snow * 255 / 100) : 0;
            leaves[i * 4 + 3] = 255;
            const y = column.height + 32768;
            heights[i * 4] = y >> 8; heights[i * 4 + 1] = y & 255;
            heights[i * 4 + 2] = entry && (column.flags & 4) ? 255 : 0; heights[i * 4 + 3] = 255;
        });
        record.textures.forEach(t => { t.needsUpdate = true; });
        record.owned.forEach(m => { m.uniforms.seasonActive.value = 1; });
        record.revision = paletteRevision;
        viewer.redraw();
    }

    async function attach(mesh) {
        const config = state.maps[map.data.id], captured = generation;
        const record = {mesh, original: mesh.material, owned: [], textures: [], revision: -1, time: Date.now(), pending: true, atlas: config.atlas};
        records.set(mesh, record);
        try {
            record.samples = await samples(mesh, config);
            if (captured !== generation || records.get(mesh) !== record) return;
            const s = record.samples;
            record.textures = [0, 1, 2].map(() => texture(new Uint8Array(s.width * s.height * 4), s.width, s.height));
            const snowIndex = metadata.findIndex(t => t.resourcePath === 'minecraft:block/snow');
            const snowTexture = snowIndex >= 0 && map.hiresMaterial[snowIndex]?.uniforms.textureImage.value;
            const source = Array.isArray(record.original) ? record.original : [record.original];
            const used = new Set(mesh.geometry.groups.map(g => g.materialIndex));
            const materials = source.map((original, i) => {
                const path = metadata[i]?.resourcePath || '';
                if (!s.lowres && (!used.has(i) || (!materialKind(path) && !snowMaterial(path)))) return original;
                const fragment = shader(original.fragmentShader, s.lowres);
                if (!fragment) throw Error('BlueMap shader version mismatch');
                const material = original.clone();
                // Preserve shared lighting/animation uniforms; only seasonal data belongs to this tile.
                material.uniforms = {...original.uniforms,
                    seasonGrass: {value: record.textures[0]}, seasonLeaves: {value: record.textures[1]},
                    seasonHeight: {value: record.textures[2]}, seasonSnow: {value: snowTexture || original.uniforms.textureImage.value},
                    seasonBounds: {value: new THREE.Vector4(s.x0, s.z0, s.sx, s.sz)},
                    seasonWorld: {value: new THREE.Vector4(mesh.position.x, mesh.position.z, mesh.scale.x, mesh.scale.z)},
                    seasonKind: {value: materialKind(path)}, seasonSnowAllowed: {value: snowMaterial(path) && snowTexture ? 1 : 0},
                    seasonActive: {value: 0}};
                material.fragmentShader = fragment; material.needsUpdate = true;
                record.owned.push(material); return material;
            });
            record.material = Array.isArray(record.original) ? materials : materials[0];
            mesh.material = record.material; record.pending = false;
            update(record);
        } catch (error) {
            if (records.get(mesh) === record) { dispose(record); records.delete(mesh); }
            // Missing atlas regions are expected while the first saved-world scan is progressing.
            mesh.userData.seasonRetry = Date.now() + 30000;
            status.error = String(error);
        }
    }

    function pump() {
        if (!state || !map || !state.maps[map.data.id] || !metadata.length || Date.now() - lastSuccess > 60000) return;
        let updates = 0;
        for (const record of records.values()) {
            if (record.pending) continue;
            if (record.atlas !== state.maps[map.data.id].atlas || Date.now() - record.time > 60000) {
                dispose(record); records.delete(record.mesh); candidates.push(record.mesh); continue;
            }
            if (record.revision !== paletteRevision && updates++ < 8) update(record);
        }
        while (inflight < 2 && candidates.length) {
            const mesh = candidates.shift();
            if (records.has(mesh) || mesh.userData.seasonRetry > Date.now()) continue;
            inflight++; attach(mesh).finally(() => { inflight--; });
        }
    }

    function start() {
        if (!root.bluemap?.mapViewer || !root.BlueMap?.Three) { setTimeout(start, 500); return; }
        THREE = root.BlueMap.Three; viewer = root.bluemap.mapViewer;
        readState(); setInterval(readState, 10000); setInterval(discover, 1000); setInterval(pump, 50);
    }
    start();
})(typeof window === 'undefined' ? globalThis : window);
