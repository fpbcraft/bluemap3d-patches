# Ecliptic Seasons: saved-world seasonal layer

The default integration separates seasonal appearance from BlueMap's terrain meshes.
It indexes **saved Overworld chunks**, including chunks that Minecraft has unloaded,
and applies grass/foliage colour changes and virtual surface snow in the browser.
Changing season or biome snow depth does not enqueue a BlueMap terrain render.

## Data flow and cost

- Every 10 seconds, the Minecraft server thread captures an immutable biome palette
  and Ecliptic's `WeatherManager.getSnowDepthAtBiome` values. No position or chunk is queried.
- One background worker enumerates BlueMap's saved region files and reads their chunks
  directly through `Region.iterateAllChunks`. It never calls Minecraft's live chunk APIs.
- Indexing decodes at most 32 chunks per batch, with a 25 ms delay between batches.
  One 512×1024 RGB PNG per region stores biome index, surface category, snow eligibility
  and height. A batch is bounded by chunk count, not a hard execution-time deadline.
- The first pass covers every saved region in each supported map's boundaries. Subsequent
  passes compare region chunk timestamps, positions and compatibility configuration;
  unchanged regions are not decoded. Passes run no more often than every five minutes.
  Removed regions lose their atlas files. Files are replaced atomically.
- The browser polls the small state document every 10 seconds. Already visible tiles
  update from their cached surface samples; newly viewed tiles use the same current state.
  Two tile jobs run concurrently, with a 16-region LRU cache and at most 16 regions per
  tile. Tiles beyond that limit retain normal appearance. The standard BlueMap LODs fit.
- Both hires and lowres shaders consume the atlas. Ordinary block edits remain BlueMap's
  responsibility; surface-index refresh follows disk saves and the next indexing pass.

The initial index is still proportional to world size and consumes disk I/O and a worker
thread. It avoids model baking and terrain uploads, but there is no measured whole-world
completion estimate yet. `assets/bluemap-seasons/state.json` exposes `indexedRegions`
for the current process; `window.__bluemapSeasons` exposes browser diagnostics.

## Fidelity and supported scope

This is a **surface approximation**, not an exact reproduction of Ecliptic's client renderer.

- The Overworld's actual map IDs gate the effect. Nether, End and unrelated dimensions
  never borrow its calendar or coordinates. Map bounds and minimum inhabited time are
  respected while indexing; the current map tile filter is also checked.
- Close-up grass and ordinary vanilla foliage textures receive a biome colour ratio;
  original texture, lighting and vertex colours remain in use. Birch, spruce, mangrove,
  modded/special texture names and custom Ecliptic colour datapacks are not reproduced.
- The index uses the **top surface biome per column**, not each vertex's 3D biome.
  Biome-edge blending and position-dependent base colours can differ from Minecraft.
  Distant tiles sample surface categories rather than rerendering every composited block.
- Explicit compatibility tint rules suppress seasonal tint at indexed surfaces. After
  editing those rules, allow an index pass; the ordinary map may still need its usual rerender.
- Snow uses current biome snow depth and a deterministic per-column coverage threshold.
  It replaces eligible upward-facing surface pixels with the snow texture, preserving
  sides and geometry. It does not add snow thickness, skirts or frozen-water geometry.
- Snow is restricted to exposed grass blocks, dirt, coarse dirt, podzol, mycelium, stone
  and gravel. Missing light data, blocked skylight and block light of 10+ suppress it.
  Plants above the surface can prevent snow; roofs, water and unrecognised blocks stop
  the surface scan. Footprints, local snow history, snowline overrides and configurable
  Ecliptic melting thresholds are not reproduced.
- Missing/unsupported metadata and stale server state retain the original terrain.
  Shader integration targets BlueMap 5.7; unknown shader layouts fail closed.

## Installation and rollback

Install the new compatibility addon in place of the old one, then reload BlueMap and the
browser. The addon installs a stable script loader plus `seasonal.js` under the web root.
External web hosting must also serve the generated `assets/bluemap-seasons/` directory.

Existing **neutral** terrain tiles work without a terrain rebuild. If PR #75 has already
baked seasonal colours into a map, perform one forced update with dynamic mode enabled
so its base tiles are neutral again. Do not combine this implementation with PR #76's
live per-position snow renderer. This PR is based on #75 and replaces #76's approach.

JVM properties:

```text
-Dbluemap.compat.ecliptic.dynamic=true   # default; skips the old forced seasonal rerender policy
-Dbluemap.compat.ecliptic.tint=true      # default
-Dbluemap.compat.ecliptic.snow=true      # default
```

Set `dynamic=false` and restart to return to #75's baked-tint path. The legacy
`bluemap.compat.ecliptic.rerender` property only applies in that mode. To disable all
seasonal effects, set both `tint=false` and `snow=false`. Disabling the addon publishes
an empty map list; the browser also stops effects when the publisher becomes stale.

The index is rebuildable: stop the addon and remove its generated atlas subdirectories
under `assets/bluemap-seasons/` to force reindexing. This is also necessary after changing
a custom programmatic tile filter without changing the map's configured bounds.
No Minecraft world files or original map tiles are modified.

## Validation

`./build.sh` runs Java saved-chunk tests along with the repository's full build.
`npm ci --prefix tests/seasonal` then `npm test --prefix tests/seasonal` runs the pure browser tests.
`npm run test:browser --prefix tests/seasonal` runs Chromium/WebGL against vendored,
MIT-licensed BlueMap 5.7 shader fixtures and Three.js 0.147.0. Install Playwright Chromium
first, or set `CHROME_PATH` to a local Chrome executable.

The browser test exercises neutral → autumn → snow → thaw in hires and lowres without
reloading geometry, plus disabled-state fallback. Java tests exercise saved chunks,
negative coordinates, missing height/light data, heat, roofs and water.

Before production approval, run the built addon with the server's exact Ecliptic version:
check palette capture, initial atlas progress on a genuinely unloaded region, winter/thaw
at both zoom levels, atlas refresh after a saved block edit, server MSPT and browser memory.
Passing builds and synthetic WebGL tests do not replace that target-runtime smoke test.
