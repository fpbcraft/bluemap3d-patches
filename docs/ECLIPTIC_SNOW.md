# Ecliptic virtual snow: initial terrain surface approximation

This is stacked on PR #75, which is stacked on #74.

Virtual snow sampling is **enabled by default** when Ecliptic Seasons is installed. To disable it, set the JVM startup flag `-Dbluemap.compat.ecliptic.snow=false`.

The BlueMap color callback queues exposed opaque surface locations (selected vanilla dirt/grass/stone/gravel types only). A bounded queue (2,048) and cache (8,192) keep memory limited. Ecliptic's public `isSnowyBlock(Level, BlockState, BlockPos)` is queried on the Minecraft server thread during the calendar poll; no chunks are deliberately loaded. The renderer uses cached results only. On a confirmed snowy surface it lightens the color, preserving alpha.

**Important limitations**
- The first render queues samples but does not immediately recolor; another tile render is required once the server has processed the queue. No automatic per-region refresh is wired in this slice.
- This is **not snow geometry or a texture overlay**. It approximates snow with a pale tint; block sides can also be affected by the per-block tint callback.
- Snow coverage changes **within the same solar term** are not observed after a location enters the cache. Cache clears on solar-term changes, server shutdown, or capacity exhaustion.
- Snow leaves, snow layers, plant replacement, shaders and frozen-water rendering are not implemented.
- It is not intended as an automatic large-world feature until a per-region refresh budget and incremental invalidation are implemented.
- The optional Ecliptic bridge is sensitive to the target mod API; validate on the server before enabling.

A follow-up should move snow evaluation to chunk-snapshot ingestion and use top-face-specific geometry/materials, rather than approximating through global block tint.
