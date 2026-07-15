#!/usr/bin/env python3
"""Validate an emitted audio benchmark report with only the Python standard library."""

from __future__ import annotations

import json
import math
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, NoReturn


class ContractError(ValueError):
    """Raised when the schema subset or report contract is invalid."""


SUPPORTED_KEYWORDS = {
    "$id",
    "$schema",
    "additionalProperties",
    "const",
    "description",
    "enum",
    "format",
    "items",
    "maximum",
    "minimum",
    "properties",
    "required",
    "title",
    "type",
}


def fail(path: str, message: str) -> NoReturn:
    raise ContractError(f"{path}: {message}")


def matches_type(value: Any, expected: str) -> bool:
    if expected == "null":
        return value is None
    if expected == "boolean":
        return isinstance(value, bool)
    if expected == "integer":
        return isinstance(value, int) and not isinstance(value, bool)
    if expected == "number":
        return (
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and math.isfinite(value)
        )
    if expected == "string":
        return isinstance(value, str)
    if expected == "array":
        return isinstance(value, list)
    if expected == "object":
        return isinstance(value, dict)
    fail("$schema", f"unsupported type {expected!r}")


def validate(instance: Any, schema: dict[str, Any], path: str = "$") -> None:
    unknown = set(schema) - SUPPORTED_KEYWORDS
    if unknown:
        fail(path, f"unsupported schema keywords: {sorted(unknown)}")

    expected = schema.get("type")
    if expected is not None:
        expected_types = [expected] if isinstance(expected, str) else expected
        if not isinstance(expected_types, list) or not all(
            isinstance(item, str) for item in expected_types
        ):
            fail(path, "schema type must be a string or string array")
        if not any(matches_type(instance, item) for item in expected_types):
            fail(path, f"expected type {expected_types}, got {type(instance).__name__}")

    if "const" in schema and instance != schema["const"]:
        fail(path, f"expected constant {schema['const']!r}")
    if "enum" in schema and instance not in schema["enum"]:
        fail(path, f"expected one of {schema['enum']!r}")

    if isinstance(instance, (int, float)) and not isinstance(instance, bool):
        if not math.isfinite(instance):
            fail(path, "number must be finite")
        if "minimum" in schema and instance < schema["minimum"]:
            fail(path, f"must be >= {schema['minimum']}")
        if "maximum" in schema and instance > schema["maximum"]:
            fail(path, f"must be <= {schema['maximum']}")

    if schema.get("format") is not None:
        if schema["format"] != "date-time" or not isinstance(instance, str):
            fail(path, f"unsupported format {schema['format']!r}")
        try:
            parsed = datetime.fromisoformat(instance.replace("Z", "+00:00"))
        except ValueError as error:
            fail(path, f"invalid date-time: {error}")
        if parsed.tzinfo is None:
            fail(path, "date-time must include a timezone")

    if isinstance(instance, list) and "items" in schema:
        item_schema = schema["items"]
        for index, item in enumerate(instance):
            validate(item, item_schema, f"{path}[{index}]")

    if isinstance(instance, dict):
        properties = schema.get("properties", {})
        for key in schema.get("required", []):
            if key not in instance:
                fail(path, f"missing required property {key!r}")
        additional = schema.get("additionalProperties", True)
        for key, value in instance.items():
            child_path = f"{path}.{key}"
            if key in properties:
                validate(value, properties[key], child_path)
            elif additional is False:
                fail(child_path, "additional property is not allowed")
            elif isinstance(additional, dict):
                validate(value, additional, child_path)


def reject_constant(value: str) -> NoReturn:
    raise ContractError(f"non-standard JSON number {value!r}")


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle, parse_constant=reject_constant)


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print("usage: validate_audio_pipeline_report.py SCHEMA REPORT", file=sys.stderr)
        return 64
    schema_path = Path(argv[1])
    report_path = Path(argv[2])
    try:
        schema = load_json(schema_path)
        report = load_json(report_path)
        if not isinstance(schema, dict):
            fail("$schema", "root schema must be an object")
        validate(report, schema)
    except (ContractError, json.JSONDecodeError, OSError) as error:
        print(f"audio report contract validation failed: {error}", file=sys.stderr)
        return 1
    print(f"Validated {report_path} against {schema_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
