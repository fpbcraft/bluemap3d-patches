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
