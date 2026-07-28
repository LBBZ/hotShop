#!/usr/bin/env python3
"""Conservative OpenAPI compatibility gate for HotShop's generated contracts."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from typing import Any


HTTP_METHODS = {
    "get",
    "put",
    "post",
    "delete",
    "options",
    "head",
    "patch",
    "trace",
}
BIGINT_URL_PARAMETER_NAMES = {"productId", "userId"}
POSITIVE_LONG_ID_PATTERN = r"^[1-9][0-9]{0,18}$"
ORDER_ID_PATTERN = r"^[A-Za-z0-9_-]{1,64}$"


def load(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as stream:
        return json.load(stream)


def resolve(document: dict[str, Any], schema: Any) -> Any:
    seen: set[str] = set()
    while isinstance(schema, dict) and "$ref" in schema:
        reference = schema["$ref"]
        if reference in seen or not reference.startswith("#/"):
            return schema
        seen.add(reference)
        value: Any = document
        for segment in reference[2:].split("/"):
            value = value[segment.replace("~1", "/").replace("~0", "~")]
        schema = value
    return schema


def schema_type(schema: dict[str, Any]) -> Any:
    if "type" in schema:
        return schema["type"]
    if "properties" in schema:
        return "object"
    if "items" in schema:
        return "array"
    return None


def compare_schema(
    baseline_document: dict[str, Any],
    current_document: dict[str, Any],
    baseline_schema: Any,
    current_schema: Any,
    location: str,
    errors: list[str],
    visited: set[tuple[int, int, str]],
) -> None:
    baseline_schema = resolve(baseline_document, baseline_schema)
    current_schema = resolve(current_document, current_schema)
    if not isinstance(baseline_schema, dict) or not isinstance(current_schema, dict):
        return
    visit_key = (id(baseline_schema), id(current_schema), location)
    if visit_key in visited:
        return
    visited.add(visit_key)

    baseline_type = schema_type(baseline_schema)
    current_type = schema_type(current_schema)
    if baseline_type != current_type:
        errors.append(f"{location}: type changed from {baseline_type!r} to {current_type!r}")
        return

    baseline_required = set(baseline_schema.get("required", []))
    current_required = set(current_schema.get("required", []))
    for field in sorted(current_required - baseline_required):
        errors.append(f"{location}.{field}: optional field became required")

    baseline_properties = baseline_schema.get("properties", {})
    current_properties = current_schema.get("properties", {})
    for field in sorted(set(baseline_properties) - set(current_properties)):
        errors.append(f"{location}.{field}: field was deleted")
    for field in sorted(set(baseline_properties) & set(current_properties)):
        compare_schema(
            baseline_document,
            current_document,
            baseline_properties[field],
            current_properties[field],
            f"{location}.{field}",
            errors,
            visited,
        )

    if baseline_type == "array":
        compare_schema(
            baseline_document,
            current_document,
            baseline_schema.get("items", {}),
            current_schema.get("items", {}),
            f"{location}[]",
            errors,
            visited,
        )


def parameter_key(parameter: dict[str, Any]) -> tuple[str, str]:
    return parameter.get("in", ""), parameter.get("name", "")


def collect_parameters(
    document: dict[str, Any], path_item: dict[str, Any], operation: dict[str, Any]
) -> dict[tuple[str, str], dict[str, Any]]:
    result: dict[tuple[str, str], dict[str, Any]] = {}
    for raw in [*path_item.get("parameters", []), *operation.get("parameters", [])]:
        parameter = resolve(document, raw)
        if isinstance(parameter, dict):
            result[parameter_key(parameter)] = parameter
    return result


def compare_operation(
    baseline_document: dict[str, Any],
    current_document: dict[str, Any],
    path: str,
    method: str,
    baseline_path_item: dict[str, Any],
    current_path_item: dict[str, Any],
    errors: list[str],
) -> None:
    baseline_operation = baseline_path_item[method]
    current_operation = current_path_item[method]
    location = f"{method.upper()} {path}"

    baseline_parameters = collect_parameters(
        baseline_document, baseline_path_item, baseline_operation
    )
    current_parameters = collect_parameters(
        current_document, current_path_item, current_operation
    )
    for key, baseline_parameter in baseline_parameters.items():
        if key not in current_parameters:
            errors.append(f"{location}: parameter {key[0]}:{key[1]} was deleted")
            continue
        current_parameter = current_parameters[key]
        if not baseline_parameter.get("required", False) and current_parameter.get(
            "required", False
        ):
            errors.append(f"{location}: parameter {key[0]}:{key[1]} became required")
        compare_schema(
            baseline_document,
            current_document,
            baseline_parameter.get("schema", {}),
            current_parameter.get("schema", {}),
            f"{location} parameter {key[0]}:{key[1]}",
            errors,
            set(),
        )

    baseline_body = baseline_operation.get("requestBody")
    current_body = current_operation.get("requestBody")
    if baseline_body is not None and current_body is None:
        errors.append(f"{location}: request body was deleted")
    elif baseline_body is not None and current_body is not None:
        baseline_body = resolve(baseline_document, baseline_body)
        current_body = resolve(current_document, current_body)
        if not baseline_body.get("required", False) and current_body.get("required", False):
            errors.append(f"{location}: request body became required")
        compare_content(
            baseline_document,
            current_document,
            baseline_body.get("content", {}),
            current_body.get("content", {}),
            f"{location} request",
            errors,
        )

    for status, baseline_response in baseline_operation.get("responses", {}).items():
        current_response = current_operation.get("responses", {}).get(status)
        if current_response is None:
            errors.append(f"{location}: response {status} was deleted")
            continue
        baseline_response = resolve(baseline_document, baseline_response)
        current_response = resolve(current_document, current_response)
        compare_content(
            baseline_document,
            current_document,
            baseline_response.get("content", {}),
            current_response.get("content", {}),
            f"{location} response {status}",
            errors,
        )


def compare_content(
    baseline_document: dict[str, Any],
    current_document: dict[str, Any],
    baseline_content: dict[str, Any],
    current_content: dict[str, Any],
    location: str,
    errors: list[str],
) -> None:
    for media_type, baseline_media in baseline_content.items():
        if media_type not in current_content:
            errors.append(f"{location}: media type {media_type} was deleted")
            continue
        compare_schema(
            baseline_document,
            current_document,
            baseline_media.get("schema", {}),
            current_content[media_type].get("schema", {}),
            f"{location} {media_type}",
            errors,
            set(),
        )


def compare_document(
    baseline_document: dict[str, Any], current_document: dict[str, Any], name: str
) -> list[str]:
    errors: list[str] = []
    baseline_paths = baseline_document.get("paths", {})
    current_paths = current_document.get("paths", {})
    for path, baseline_path_item in baseline_paths.items():
        if path not in current_paths:
            errors.append(f"{name}: path {path} was deleted")
            continue
        current_path_item = current_paths[path]
        for method in sorted(HTTP_METHODS & set(baseline_path_item)):
            if method not in current_path_item:
                errors.append(f"{name}: operation {method.upper()} {path} was deleted")
                continue
            compare_operation(
                baseline_document,
                current_document,
                path,
                method,
                baseline_path_item,
                current_path_item,
                errors,
            )

    baseline_schemas = baseline_document.get("components", {}).get("schemas", {})
    current_schemas = current_document.get("components", {}).get("schemas", {})
    for schema_name, baseline_schema in baseline_schemas.items():
        if schema_name not in current_schemas:
            errors.append(f"{name}: component schema {schema_name} was deleted")
            continue
        compare_schema(
            baseline_document,
            current_document,
            baseline_schema,
            current_schemas[schema_name],
            f"{name} component {schema_name}",
            errors,
            set(),
        )
    return errors


def validate_hotshop_invariants(document: dict[str, Any], name: str) -> list[str]:
    errors: list[str] = []
    for path, path_item in document.get("paths", {}).items():
        for method in sorted(HTTP_METHODS & set(path_item)):
            operation = path_item[method]
            parameters = collect_parameters(document, path_item, operation)
            for (location, parameter_name), parameter in parameters.items():
                if location not in {"path", "query"}:
                    continue
                schema = resolve(document, parameter.get("schema", {}))
                if parameter_name in BIGINT_URL_PARAMETER_NAMES:
                    if schema_type(schema) != "string":
                        errors.append(
                            f"{name}: {method.upper()} {path} {location}:{parameter_name} "
                            "must be a decimal string, not a JavaScript number"
                        )
                    if schema.get("pattern") != POSITIVE_LONG_ID_PATTERN:
                        errors.append(
                            f"{name}: {method.upper()} {path} {location}:{parameter_name} "
                            f"must use pattern {POSITIVE_LONG_ID_PATTERN}"
                        )
                if location == "path" and parameter_name == "orderId":
                    if schema_type(schema) != "string":
                        errors.append(
                            f"{name}: {method.upper()} {path} path:orderId must be a string"
                        )
                    if schema.get("pattern") != ORDER_ID_PATTERN:
                        errors.append(
                            f"{name}: {method.upper()} {path} path:orderId "
                            f"must use pattern {ORDER_ID_PATTERN}"
                        )
                    if schema.get("minLength") != 1 or schema.get("maxLength") != 64:
                        errors.append(
                            f"{name}: {method.upper()} {path} path:orderId "
                            "must be between 1 and 64 characters"
                        )
    return errors


def main() -> int:
    repository_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--baseline",
        type=Path,
        default=repository_root / "docs" / "api" / "openapi-baseline",
    )
    parser.add_argument(
        "--current",
        type=Path,
        default=repository_root / "target" / "openapi",
    )
    arguments = parser.parse_args()

    errors: list[str] = []
    for name in ("public", "user", "admin"):
        baseline_path = arguments.baseline / f"{name}.json"
        current_path = arguments.current / f"{name}.json"
        if not baseline_path.is_file():
            errors.append(f"{name}: baseline file is missing: {baseline_path}")
            continue
        if not current_path.is_file():
            errors.append(f"{name}: current file is missing: {current_path}")
            continue
        baseline_document = load(baseline_path)
        current_document = load(current_path)
        errors.extend(compare_document(baseline_document, current_document, name))
        errors.extend(validate_hotshop_invariants(current_document, name))

    if errors:
        print("OpenAPI compatibility check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 1
    print("OpenAPI compatibility check passed for public, user, and admin contracts.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
