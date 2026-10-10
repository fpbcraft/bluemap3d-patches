# Copycats static and moving CT diagnostics

## Why

A successful `STATIC ... geometryQuads=6 emittedQuads=6` line proves only that
six quads were submitted. It cannot confirm that the expected copied-material
texture, generated CT sheet, connectivity mask or UVs were selected. Also,
`STATIC` messages refer to BlueMap terrain, **not** BlueMap3D moving trains.

## Activate

Add the following **JVM arguments** to the Minecraft server launch and
restart with matching BlueMap3D and compatibility-addon artifacts:

**Crafty / NeoForge startup argument placement:** These are Java system properties,
not Minecraft server command-line options. Put them in Crafty's Java/JVM arguments
field, **before** the main class `cpw.mods.bootstraplauncher.BootstrapLauncher`
(or before `-jar` on jar-based launch commands), not in server/game/extra
arguments that follow `--launchTarget forgeserver`. Placing `-D...` flags after
the main class results in `joptsimple.UnrecognizedOptionException: D is not a
recognized option`.

If Crafty has no distinct Java-arguments field, insert these flags immediately
after the `java` executable in the actual start command. Alternatively, edit
`user_jvm_args.txt` **only if** the launch script references that file; direct
bootstrap-launcher commands may bypass it.

Example order: `java -Dbluemap.copycats.trace=true [other JVM arguments]
cpw.mods.bootstraplauncher.BootstrapLauncher --launchTarget forgeserver ...`



```text
-Dbluemap.copycats.trace=true
-Dbluemap.copycats.trace.center=-907,-387
-Dbluemap.copycats.trace.radius=24
-Dbluemap.copycats.trace.limit=240
```

For a second, narrower pass focused specifically on Railways CT, optionally add
`-Dbluemap.copycats.trace.match=railways:`. This filters static trace details
by text; leave it **unset** for the initial pass so `entity=<null>` and missing
material data are not hidden.

BlueMap DEBUG logging must be enabled to see these lines. The center accepts
**X,Z** coordinates; Y is not part of the filter. Remove
`-Dbluemap.copycats.trace.center=...` to trace the first samples anywhere,
but this may consume the log budget before your test area is rendered.

Force a BlueMap region update (using the usual in-game update command) and,
separately, assemble or move a train to cause BlueMap3D to re-mesh. Search
the server log for `COPYCATS-TRACE` and `COPYCATS-MOVING-TRACE`.

### Static terrain trace

- `phase=ENTITY`: raw decoded block-entity class, retained fields,
  material_data part keys, representative material. `<null>` indicates
  missing or unusable NBT; it cannot be fixed by texture selection.
- `phase=GEOMETRY`: copycat wrapper, material-to-quad counts, emitted vs
  generated. A positive emitted count **does not prove texture correctness**.
- `phase=MODEL`: missing blockstate, model, resolved variant, inherited
  geometry or face.
- `phase=CT`: material, sampled base texture, `mode=none/create/fusion`,
  connection mask, CT tile index, target generated sheet and `available`.
  `mode=none` means no supported CT specification was associated with
  that texture, whereas `available=false` means the computed CT image
  is not in BlueMap's texture registry.
- `phase=ATLAS`: selected texture, image availability, texture-gallery index
  and final UV coordinates.

### Moving 3D scene trace

- `phase=SOURCE`: which procedural Copycats source won, sampled copied
  textures and number of quads that lack both culling and shading faces.
- `phase=MATERIAL`: copied block material, face being sampled, texture
  chosen and whether its pixels were found.
- `phase=SHAPE`: CopycatsShapeSource voxel geometry/texture results.
- `phase=CT-SKIP`: a generated moving quad has no face metadata or usable UV axes.
- `phase=CT-NOMATCH`: a moving quad reached CT resolution but its copied
  texture/wrapper did not match a CT definition.
- `phase=CT-MISSING-SHEET`: matching Create CT definition with no sheet image.
- `phase=CT-CREATE` / `phase=CT-FUSION`: the moving pass selected a
  particular CT sheet, connectivity mask and tile.

**Important:** The moving CT resolver ordinarily requires a face on each
quad. A high `missingFace` count is evidence of why a downstream CT
pass might skip generated geometry.

Moving traces are capped independently at 60 samples per source. Static
traces share the configured cap. Tracing is fully off unless explicitly enabled.

## Findings from October 10, 10:50 trace

The copied Railways material was successfully decoded on
`copycats:copycat_byte_panel` at `(-889, 72, -411)`. A Create
`omnidirectional` CT spec was found; the generated
`wrapped_slashed_connected/sheet` was available; the expected atlas index
and tile UVs were selected. Geometry also emitted successfully.
This rules out missing NBT or a missing CT image **for the sampled static
blocks**. In contrast, it does not confirm the connection mask is correct
for each 8x8 material part.

The earlier static emitter always mapped the *entire 16x16 material face*
onto every partial quad. A new UV projection fix samples each quad's
actual position within that 16x16 face. Tests assert that adjacent 8px
panels share the same UVs along their common seam. This addresses a
specific cause of repeated texture borders across individual copycat
parts. It does not yet fix every form of neighbor connectivity.

The original trace budget of 240 was reached after only about ten
blocks due to repeated face lookups. Identical per-position events
are now deduplicated before consuming the budget.

**No** `COPYCATS-MOVING-TRACE` lines were captured. Static BlueMap renders
do not force BlueMap3D to rebake the moving locomotive. Test an actual
assembled-train remesh separately.

If the objective is the locomotive around X=-907, Z=-387, reduce the
radius to `8` to avoid collecting many blocks at the edge of the
original 24-block window. For dedicated static samples at
`(-889,-411)`, use `center=-889,-411` with a small radius.

## Static copied-material CT neighbor mismatch (October 10)

If adjacent Copycats panels still show seams on the **stationary** BlueMap
locomotive, opt into one more diagnostic alongside normal `CopycatsTrace`:

```text
-Dbluemap.copycats.trace=true
-Dbluemap.copycats.trace.center=-890,-410
-Dbluemap.copycats.trace.radius=8
-Dbluemap.copycats.trace.limit=300
-Dbluemap.copycats.trace.neighbors=true
```

The new `COPYCATS-TRACE phase=CT-EDGE` events report only rejected **cardinal**
connections, including the wrapper block ID, effective copied material, whether
the neighboring block entity was retained and whether the block ahead of the
face was occluding. Compare these with the existing `phase=CT` mask/tile logs.
This separates missing neighbor NBT, material-state differences, and face
occlusion from a bad CT sheet or UV projection without logging raw NBT.
The additional neighbor logging is disabled by default.

For **moving/assembled** trains use `COPYCATS-MOVING-TRACE`: the resolver
now detects copied-material contexts *after* their wrapper state was replaced
by `context.withState(material)`. Ensure the train actually rebakes its mesh
after installing the patched BlueMap3D JAR; a terrain render is insufficient.

## Missing Create copycat panels and steps (ground + locomotive)

The October 10 comparison shows some `create:copycat_panel` and
`create:copycat_step` blocks are visible in Minecraft but missing both
on the static BlueMap ground test and in the moving locomotive geometry.

This update addresses **two separate renderers**:

1. **Static terrain:** A blockstate JSON supplied by the compatibility resource
   pack may lose load-order priority to Create's ordinary blockstate. The
   API-enable dispatch now explicitly replaces `create:copycat_panel` and
   `create:copycat_step` entries after all packs are loaded. Startup should
   log `static Create copycat panels/steps routed 2 id(s)`. Static tracing
   also emits `COPYCATS-TRACE phase=ENTITY` and `phase=GEOMETRY` when the
   custom renderer is called.
2. **BlueMap3D moving trains:** The old generic panel fallback used a
   **1-pixel** face. Create's `CopycatPanelBlock` uses
   `AllShapes.CASING_3PX`; the renderer now asks the actual state for its
   voxel shape and falls back to 3 pixels with explicit direction handling.
   Create steps have a canonical 16×8×8 fallback. With tracing enabled,
   `COPYCATS-MOVING-TRACE phase=CREATE-SHAPE` reports successful geometry or
   the exact early-return reason (`missing-material-nbt`,
   `unusable-copied-material`, or `invalid-voxel-shape`).

For the ground test, force-update the test area after restarting with the new
compatibility-addon JAR. For the locomotive, **also replace the BlueMap3D
patched JAR** and cause a new Create carriage mesh to be baked; ordinary
terrain rerenders and train movement do not guarantee this. A quick
disassemble/reassemble of the locomotive is a suitable test if safe for the
world. Do not delete any world files or objects just to refresh these assets.

If the startup reports the two Create IDs routed but there is no
`STATIC block=create:copycat_panel`/step, or the expected
`COPYCATS-TRACE` events in the correct area, record that alongside the
model results. If the moving trace says `missing-material-nbt`, the
failure is upstream in the contraption snapshot pipeline, not CT atlas UVs.

## Minimal reproducible case

Use a small cluster of unassembled blocks near the trace center:

1. Plain `railways:brown_brass_wrapped_locometal` blocks side-by-side.
2. Two touching `copycats:copycat_byte` parts with that material.
3. Two touching `copycats:copycat_byte_panel` parts with that material.
4. A `create:copycat_panel` and `create:copycat_step` with
   `pretty_in_pink:black_brushed_steel`.
5. A plain `railways:brown_single_pane_locometal_window` next to an
   opaque block for face-culling comparison.

Keep static and assembled-train observations separate. Save the resulting
`COPYCATS-TRACE`, `COPYCATS-MOVING-TRACE`, and CT discovery summary lines
and include the build/JAR commit IDs. Disable tracing after the capture.
