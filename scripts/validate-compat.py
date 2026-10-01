#!/usr/bin/env python3
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BUILTIN = ROOT / "compat" / "builtin"
INDEX = BUILTIN / "index.txt"
SCHEMA_VERSION = 1
SUPPORTED_TINTS = {"none", "fixed", "palette"}
HEX = re.compile(r"^#[0-9A-Fa-f]{6}$")
NUMERIC_TEMPLATE_REF = re.compile(r"\$\{(\d+)\}")


def fail(message: str) -> None:
    print(f"compat validation: {message}", file=sys.stderr)
    raise SystemExit(1)


def validate_color(value: object, where: str) -> None:
    if not isinstance(value, str) or not HEX.fullmatch(value):
        fail(f"{where}: expected #RRGGBB, got {value!r}")


def load(path: Path) -> dict:
    try:
        value = json.loads(path.read_text())
    except (OSError, json.JSONDecodeError) as error:
        fail(f"{path}: {error}")
    if not isinstance(value, dict):
        fail(f"{path}: document must be an object")
    return value


def main() -> None:
    schema = load(ROOT / "compat" / "schema.json")
    if schema.get("type") != "object":
        fail("compat/schema.json does not describe an object")

    if not INDEX.is_file():
        fail("compat/builtin/index.txt is missing")

    listed = [
        line.strip()
        for line in INDEX.read_text().splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    actual = sorted(path.name for path in BUILTIN.glob("*.json"))
    if sorted(listed) != actual:
        fail(
            "builtin index mismatch: "
            f"listed={sorted(listed)!r}, actual={actual!r}"
        )

    seen_rule_ids: dict[str, str] = {}

    for name in listed:
        path = BUILTIN / name
        document = load(path)
        if document.get("schemaVersion") != SCHEMA_VERSION:
            fail(f"{path}: schemaVersion must be {SCHEMA_VERSION}")
        if not isinstance(document.get("id"), str) or not document["id"].strip():
            fail(f"{path}: document id is required")

        moving = document.get("moving")
        if moving is not None:
            if not isinstance(moving, dict):
                fail(f"{path}: moving must be an object")
            policy = moving.get("modelNamespaces")
            if policy is not None:
                if not isinstance(policy, dict):
                    fail(f"{path}: moving.modelNamespaces must be an object")
                for key in ("include", "exclude"):
                    values = policy.get(key, [])
                    if not isinstance(values, list) or not all(
                        isinstance(item, str) and item for item in values
                    ):
                        fail(f"{path}: moving.modelNamespaces.{key} must be strings")
            features = moving.get("features", {})
            if not isinstance(features, dict) or not all(
                isinstance(key, str) and key and isinstance(value, bool)
                for key, value in features.items()
            ):
                fail(f"{path}: moving.features must map feature ids to booleans")

        rules = document.get("rules", [])
        if not isinstance(rules, list):
            fail(f"{path}: rules must be an array")

        for index, rule in enumerate(rules):
            where = f"{path}: rules[{index}]"
            if not isinstance(rule, dict):
                fail(f"{where}: rule must be an object")

            rule_id = rule.get("id")
            if not isinstance(rule_id, str) or not rule_id.strip():
                fail(f"{where}: id is required")
            if rule_id in seen_rule_ids:
                fail(
                    f"{where}: duplicate built-in rule id {rule_id!r}; "
                    f"already defined in {seen_rule_ids[rule_id]}"
                )
            seen_rule_ids[rule_id] = str(path)

            match = rule.get("match")
            if not isinstance(match, dict):
                fail(f"{where}: match object is required")
            blocks = match.get("blocks", [])
            models = match.get("models", [])
            if not isinstance(blocks, list) or not all(
                isinstance(item, str) and item for item in blocks
            ):
                fail(f"{where}: match.blocks must contain strings")
            if not isinstance(models, list) or not all(
                isinstance(item, str) and item for item in models
            ):
                fail(f"{where}: match.models must contain strings")
            if not blocks and not models:
                fail(f"{where}: match must contain block or model patterns")

            for key in ("exclude",):
                values = match.get(key, [])
                if not isinstance(values, list) or not all(
                    isinstance(item, str) and item for item in values
                ):
                    fail(f"{where}: match.{key} must be strings")

            properties = match.get("properties", {})
            if not isinstance(properties, dict) or not all(
                isinstance(key, str)
                and isinstance(value, str)
                and key
                and value
                for key, value in properties.items()
            ):
                fail(f"{where}: match.properties must map strings to patterns")

            scope = rule.get("scope", ["terrain", "moving"])
            if not isinstance(scope, list) or not set(scope).issubset(
                {"terrain", "moving"}
            ):
                fail(f"{where}: invalid scope {scope!r}")

            model = rule.get("model")
            if model is not None:
                if not isinstance(model, dict):
                    fail(f"{where}: model must be an object")

                model_type = model.get("type")
                templates: list[str] = []

                if model_type == "alias":
                    source_block = model.get("sourceBlock")
                    if not isinstance(source_block, str) or not source_block:
                        fail(f"{where}: alias model requires sourceBlock")
                    templates.append(source_block)
                elif model_type == "resource_alias":
                    source_model = model.get("sourceModel")
                    if not isinstance(source_model, str) or not source_model:
                        fail(f"{where}: resource_alias model requires sourceModel")
                    if "moving" in scope:
                        fail(
                            f"{where}: resource_alias is terrain-only; "
                            "remove moving from scope"
                        )
                    templates.append(source_model)
                    target_model = model.get("targetModel")
                    if target_model is not None:
                        if not isinstance(target_model, str) or not target_model:
                            fail(
                                f"{where}: resource_alias targetModel must be "
                                "a non-empty string"
                            )
                        templates.append(target_model)
                else:
                    fail(f"{where}: unsupported model type {model_type!r}")

                capture_refs = [
                    int(match.group(1))
                    for template in templates
                    for match in NUMERIC_TEMPLATE_REF.finditer(template)
                ]
                if any(index < 1 for index in capture_refs):
                    fail(f"{where}: wildcard captures are 1-based")
                if capture_refs:
                    highest_capture = max(capture_refs)
                    capture_patterns = (
                        models
                        if model_type == "resource_alias" and models
                        else blocks
                    )
                    if not capture_patterns:
                        fail(
                            f"{where}: model type {model_type!r} has no "
                            "compatible match patterns"
                        )
                    for pattern in capture_patterns:
                        available = pattern.count("*")
                        if available < highest_capture:
                            fail(
                                f"{where}: model template references "
                                f"${{{highest_capture}}} but pattern "
                                f"{pattern!r} has only "
                                f"{available} '*' capture(s)"
                            )

            tint = rule.get("tint")
            if tint is None:
                continue
            if not blocks:
                fail(f"{where}: tint rules require match.blocks")
            if not isinstance(tint, dict):
                fail(f"{where}: tint must be an object")

            tint_type = tint.get("type")
            if tint_type not in SUPPORTED_TINTS:
                fail(f"{where}: unsupported tint type {tint_type!r}")

            if tint_type == "fixed":
                validate_color(tint.get("color"), f"{where}.tint.color")

            if tint_type == "palette":
                palette = tint.get("palette")
                if not isinstance(palette, list) or not palette:
                    fail(f"{where}: palette tint requires colors")
                for color_index, color in enumerate(palette):
                    validate_color(
                        color, f"{where}.tint.palette[{color_index}]"
                    )
                validate_color(
                    tint.get("defaultColor", "#FFFFFF"),
                    f"{where}.tint.defaultColor",
                )
                defaults = tint.get("defaultByBlock", [])
                if not isinstance(defaults, list):
                    fail(f"{where}: defaultByBlock must be an array")
                for default_index, default in enumerate(defaults):
                    if not isinstance(default, dict):
                        fail(
                            f"{where}.tint.defaultByBlock[{default_index}] "
                            "must be an object"
                        )
                    validate_color(
                        default.get("color"),
                        f"{where}.tint.defaultByBlock[{default_index}].color",
                    )

    print(
        f"compat validation: {len(listed)} document(s), "
        f"{len(seen_rule_ids)} rule(s) OK"
    )


if __name__ == "__main__":
    main()
