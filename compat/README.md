# Compatibility rules

Built-in compatibility lives in `compat/builtin/`. Server-local rules go in:

`config/bluemap3d/compat/*.json`

The rule engine hot-reloads active external files. Static BlueMap tiles still need to be rerendered after a visual rule changes.

At startup the installed bundle materializes two files:

- `supported-defaults.generated.json`: regenerated from bundled defaults and intentionally
  ignored by the loader. Use it as the authoritative reference for supported built-ins.
- `local.json`: created once, loaded normally, and safe to edit. Put new rules, feature
  overrides, namespace patterns, and built-in rule replacements here.

This split keeps defaults visible without letting an old copied default file override newer
built-ins after an upgrade.

## Matching

Prefer one wildcard rule for a whole block family instead of enumerating IDs:

```json
{
  "id": "example-autumn-leaves",
  "priority": 100,
  "scope": ["terrain", "moving"],
  "match": {
    "blocks": ["example:*_autumn_leaves"],
    "exclude": ["example:dead_autumn_leaves"]
  },
  "tint": { "type": "none" }
}
```

`*` matches any number of characters and `?` matches one character. Exact IDs and wildcards can be mixed in the same rule. Optional `match.properties` entries apply blockstate-property predicates and accept the same wildcards.

For model aliases, each `*` in the **matched `match.blocks` pattern** also captures its substring. Captures are numbered left-to-right as `${1}`, `${2}`, and so on. `?` remains a wildcard but does not create a capture. Exclusions and property predicates never contribute captures.

Rules are merged by stable `id`. A server-local rule with the same ID replaces the built-in rule. Higher `priority` wins when several rules match.

## Tint rules

- `none`: use white on tint-index faces (preserves textures that already contain their final color).
- `fixed`: apply one RGB color.
- `palette`: select a color by runtime value. The terrain side can read a no-arg method from a decoded BlueMap block entity; the moving side can read a raw NBT path.

Complex rendering concepts still belong in adapters. Current examples are Copycats material decoding, TrafficCraft dynamic sign textures, Sable parent transforms, and Create kinetic animation. The goal is to add Java only when a mod introduces a new rendering concept—not for every new block ID.


## Moving-object namespace policy

For mods whose block models should be preserved specially inside Create/Sable moving
volumes, add namespace patterns instead of editing Java:

```json
{
  "schemaVersion": 1,
  "id": "my-moving-support",
  "moving": {
    "modelNamespaces": {
      "include": ["my_mod", "my_family_*"],
      "exclude": ["my_family_debug"]
    }
  },
  "rules": []
}
```

Built-ins currently include `copycats` and `create_connected`. Excludes win over
includes. The moving policy hot-reloads with the rest of the external compatibility
directory.

## Model aliases

Use a model alias when the target block can reuse another loaded block's complete
blockstate/model definition. This is deliberately a block-level operation: property-sensitive
geometry should use a reusable renderer capability instead of a fragile alias.

```json
{
  "id": "example-fence-alias",
  "priority": 50,
  "scope": ["terrain", "moving"],
  "match": { "blocks": ["example:*_fancy_fence"] },
  "model": {
    "type": "alias",
    "sourceBlock": "example:${1}_fence"
  }
}
```

Alias templates can use `${id}`, `${namespace}`, `${path}`, path-segment tokens
such as `${path0}` / `${path1}`, and wildcard captures `${1}`, `${2}`, etc.
A numeric capture must exist in every block pattern in that rule; invalid local rules are
rejected at load time. Aliases resolve against the original resource-pack mapping, so
alias chains cannot accidentally form cycles.

### Model-resource aliases

Use `resource_alias` when the target blockstate is already correct but its referenced
model uses a loader BlueMap cannot parse. This keeps the target blockstate intact and
replaces only the model resource it points at. It is terrain-only.

This is the preferred Dynamic Trees pattern because branch blockstates use a default
variant, while primitive log blockstates typically require unrelated properties such as
`axis`.

```json
{
  "id": "dynamic-tree-family",
  "priority": 100,
  "scope": ["terrain"],
  "match": { "blocks": ["dtexample:*_branch"] },
  "model": {
    "type": "resource_alias",
    "sourceModel": "example:block/${1}_log"
  }
}
```

By default the target model is inferred as `<matched namespace>:block/<matched path>`.
Set `targetModel` when a blockstate points somewhere else. `sourceModel` and
`targetModel` support the same template and wildcard-capture syntax as `sourceBlock`.


## Moving feature flags

Runtime features that are useful but not universally desirable can be enabled or disabled
without rebuilding:

```json
{
  "schemaVersion": 1,
  "id": "my-runtime-options",
  "moving": {
    "features": {
      "create.chainConveyorAnimation": false,
      "create.mechanicalBeltAnimation": false
    }
  },
  "rules": []
}
```

The built-in defaults keep both Create conveyor-chain and mechanical-belt animations off.
Their ordinary static BlueMap geometry remains visible.
