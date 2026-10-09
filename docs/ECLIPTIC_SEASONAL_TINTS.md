# Ecliptic Seasons: seasonal terrain tint (stacked slice)

The default now uses the [dynamic saved-world layer](ECLIPTIC_DYNAMIC_LAYER.md).
The baked-tint behaviour below applies only with `-Dbluemap.compat.ecliptic.dynamic=false`.

Depends on PR #74. The original calendar watcher reads the Ecliptic solar term on the Minecraft server thread. This slice additionally captures the **biome-specific** Ecliptic `SolarTermColor` values into an immutable map and publishes it to BlueMap render workers. No Ecliptic client code is required at runtime.

The existing native `ConfiguredTintHook` is extended to blend a season-dependent grass/foliage palette over the normal BlueMap color path. Explicit compatibility rules retain priority over this fallback.

- Covered as a first pass: `minecraft:grass_block`, vanilla grass/ferns, and regular `*_leaves` block IDs.
- Spruce, birch and mangrove leaves are excluded until their specialized Ecliptic `LeaveColor` sources are reproduced.
- Only the Overworld calendar is sampled; no Net​​her/End seasonal palette is calculated.
- Virtual snow and visually frozen water are **not yet rendered**.
- The upstream client's biome-defined `BiomeColor` overrides and smooth in-term transitions are **not yet reproduced**; only `SolarTerm#getSolarTermColor` climate-tag defaults are read.

To disable tint but keep calendar observation, use the JVM argument:

```text
-Dbluemap.compat.ecliptic.tint=false
```

**Refresh limitations:** Changes are visible on newly rendered tiles, and old tiles still require an explicit refresh. The parent PR offers `-Dbluemap.compat.ecliptic.rerender=season` for an opt-in *full* Overworld rerender. This is not recommended as a default for a finite, pre-generated large map. A selective/budgeted refresh scheduler belongs in a subsequent slice.

**Important:** Ecliptic is detected and the palette is populated only when the public API and its internal biome-color manager match the server version. Incompatible versions fail closed to normal BlueMap colors, with a warning. The implementation should be validated on the target NeoForge 1.21.1 runtime before merging.
