from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).resolve().parents[1] / "check_openapi_compatibility.py"
SPEC = importlib.util.spec_from_file_location("compatibility", SCRIPT_PATH)
assert SPEC is not None and SPEC.loader is not None
compatibility = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(compatibility)


def contract() -> dict:
    return {
        "openapi": "3.1.0",
        "paths": {
            "/widgets": {
                "get": {
                    "responses": {
                        "200": {
                            "description": "ok",
                            "content": {
                                "application/json": {
                                    "schema": {"$ref": "#/components/schemas/Widget"}
                                }
                            },
                        }
                    }
                }
            }
        },
        "components": {
            "schemas": {
                "Widget": {
                    "type": "object",
                    "required": ["id"],
                    "properties": {
                        "id": {"type": "string"},
                        "label": {"type": "string"},
                    },
                }
            }
        },
    }


class CompatibilityGateTest(unittest.TestCase):
    def assert_breaking(self, mutate, expected: str) -> None:
        baseline = contract()
        current = copy.deepcopy(baseline)
        mutate(current)
        errors = compatibility.compare_document(baseline, current, "test")
        self.assertTrue(any(expected in error for error in errors), errors)

    def test_detects_deleted_path(self) -> None:
        self.assert_breaking(
            lambda current: current["paths"].pop("/widgets"),
            "path /widgets was deleted",
        )

    def test_detects_deleted_field(self) -> None:
        self.assert_breaking(
            lambda current: current["components"]["schemas"]["Widget"]["properties"].pop(
                "label"
            ),
            "field was deleted",
        )

    def test_detects_changed_type(self) -> None:
        self.assert_breaking(
            lambda current: current["components"]["schemas"]["Widget"]["properties"][
                "id"
            ].update(type="integer"),
            "type changed",
        )

    def test_detects_tightened_required_field(self) -> None:
        self.assert_breaking(
            lambda current: current["components"]["schemas"]["Widget"]["required"].append(
                "label"
            ),
            "optional field became required",
        )

    def test_rejects_javascript_number_for_bigint_url_parameter(self) -> None:
        document = contract()
        document["paths"]["/products/{productId}"] = {
            "get": {
                "parameters": [
                    {
                        "name": "productId",
                        "in": "path",
                        "required": True,
                        "schema": {"type": "integer", "format": "int64"},
                    }
                ],
                "responses": {"200": {"description": "ok"}},
            }
        }

        errors = compatibility.validate_hotshop_invariants(document, "test")

        self.assertTrue(any("must be a decimal string" in error for error in errors), errors)

    def test_rejects_unconstrained_order_id_path_parameter(self) -> None:
        document = contract()
        document["paths"]["/orders/{orderId}"] = {
            "get": {
                "parameters": [
                    {
                        "name": "orderId",
                        "in": "path",
                        "required": True,
                        "schema": {"type": "string"},
                    }
                ],
                "responses": {"200": {"description": "ok"}},
            }
        }

        errors = compatibility.validate_hotshop_invariants(document, "test")

        self.assertTrue(any("must use pattern" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
