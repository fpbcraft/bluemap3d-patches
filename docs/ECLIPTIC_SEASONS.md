# Ecliptic Seasons integration (initial slice)

The optional `addon-compat` integration detects Ecliptic Seasons on NeoForge 1.21.x and reads its server-authoritative solar term via its public API. There is no required runtime dependency: if Ecliptic Seasons is absent, nothing runs.

It polls once per 60 seconds and logs actual solar-term transitions. Reads are dispatched to the Minecraft server thread. The first read establishes a baseline and **never triggers a full rebuild**.

## Refresh policy

Set this **JVM system property** in the server startup arguments, then restart:

```text
-Dbluemap.compat.ecliptic.rerender=off
```

Valid values:
- `off` (default): monitor the calendar without scheduling costly forced full-map renders.
- `season`: force-update Overworld BlueMap maps when Spring/Summer/Autumn/Winter changes (at most four calendar boundaries per cycle).
- `solar_term`: force-update Overworld BlueMap maps whenever the 24-term calendar advances.

**Caution:** each forced update can redraw the entire pre-generated map and is not recommended for routine use on large worlds. BlueMap already tracks real block edits independently.

## Current limitations / next slice

This slice is **calendar detection and opt-in map refresh only**. It does **not** yet reproduce Ecliptic's client-side grass/foliage color resolver, virtual surface snow, or visually frozen water. Enabling `season` alone will not cause those visual effects to appear. Those require a separate seasonal render adapter that uses Ecliptic's biome climate tags and color data, integrates with the existing native BlueMap tint hook, and respects the renderer's thread constraints. A region-selective invalidation strategy is needed before setting an automatic refresh policy by default.

Ecliptic's public `EclipticSeasonsApi.getInstance().getSolarTerm(Level)` and `isSeasonEnabled(Level)` are the sources of truth. No derived day-count approximations are used. This integration should continue living alongside the existing specialized adapters, not in player-history-mc.
