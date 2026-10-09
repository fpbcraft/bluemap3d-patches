# Ecliptic Seasons visual-only snow geometry

This PR is stacked on #75 and #74.

The addon uses Ecliptic's public `isSnowyBlock` predicate on the dedicated server thread. BlueMap renderer threads read only a bounded cache of results. The implementation **does not modify actual Minecraft block states, world save data or BlueMap source chunk snapshots**.

When an eligible opaque block is snow-covered, a seasonal renderer first draws the **original model** and then adds a thin, snow-textured horizontal top face. The snow geometry exists only during tile generation and does not become a real block. Original side textures remain untouched.

The renderer is **enabled by default when Ecliptic is present**. Disable with the JVM argument `-Dbluemap.compat.ecliptic.snow=false`.

## Current supported surface blocks

Vanilla grass block, dirt, coarse dirt, podzol, mycelium, stone, and gravel. The adapter routes existing default-renderer variants in place, leaving variants claimed by specialized addons alone. Other blocks retain their original appearance.

## Known limitations

- Eligibility is sampled lazily, using up to 128 requests per 60-second poll, from a maximum queue of 2,048. A surface may initially appear without snow and needs another tile render after the cache is populated.
- Only the surface of blocks with air above them is eligible. Snow geometry on foliage, slopes, partial blocks and modded materials requires further support.
- The snow face is a lightweight approximation, not a full replication of Ecliptic's custom block models, snow-height definitions or decorative snow edges.
- Existing rendered tiles are cached and do not automatically change when a snow result becomes available. Region-selective tile invalidation remains a follow-up.
- The cache is reset on a solar-term change, but changes in precipitation and snow accumulation inside the same term can temporarily remain stale.
- This renderer does not create virtual ice. Frozen-water behavior should be handled by a different material/geometry pass.
- Runtime integration with the server's Ecliptic version and BlueMap's native renderer still requires a smoke test.
