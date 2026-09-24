# Compatibility rules

Built-in compatibility lives in `compat/builtin/`. Server-local rules go in:

`config/bluemap3d/compat/*.json`

The rule engine hot-reloads external files. Static BlueMap tiles still need to be rerendered after a visual rule changes.

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
    "sourceBlock": "example:${path}"
  }
}
```

Alias templates can use `${id}`, `${namespace}`, `${path}`, and path-segment tokens
such as `${path0}` / `${path1}`. Aliases resolve against the original resource-pack
mapping, so alias chains cannot accidentally form cycles.
