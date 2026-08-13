from __future__ import annotations

import unittest

from script.ci.check_gate import verify


def needs(*, java: str = "true", java_result: str = "success") -> dict[str, dict[str, object]]:
    outputs = {name: "false" for name in ("java", "agent", "web", "openapi", "docker")}
    outputs["java"] = java
    outputs["docs_only"] = "true" if java == "false" else "false"
    return {
        "changes": {"result": "success", "outputs": outputs},
        "ci-security": {"result": "success"},
        "java": {"result": java_result},
        "agent": {"result": "skipped"},
        "web": {"result": "skipped"},
        "openapi": {"result": "skipped"},
        "docker": {"result": "skipped"},
    }


class GateTest(unittest.TestCase):
    def test_selected_success_passes(self) -> None:
        self.assertEqual(verify(needs()), [])

    def test_selected_failure_blocks_gate(self) -> None:
        self.assertTrue(verify(needs(java_result="failure")))

    def test_selected_cancelled_blocks_gate(self) -> None:
        self.assertTrue(verify(needs(java_result="cancelled")))

    def test_selected_skipped_blocks_gate(self) -> None:
        self.assertTrue(verify(needs(java_result="skipped")))

    def test_unselected_skipped_is_allowed(self) -> None:
        self.assertEqual(verify(needs(java="false", java_result="skipped")), [])

    def test_change_detection_failure_blocks_gate(self) -> None:
        payload = needs()
        payload["changes"]["result"] = "failure"
        self.assertTrue(verify(payload))

    def test_missing_change_output_blocks_gate(self) -> None:
        payload = needs()
        del payload["changes"]["outputs"]["agent"]
        self.assertTrue(verify(payload))

    def test_invalid_change_output_blocks_gate(self) -> None:
        payload = needs()
        payload["changes"]["outputs"]["agent"] = "yes"
        self.assertTrue(verify(payload))

    def test_all_skipped_without_docs_only_blocks_gate(self) -> None:
        payload = needs(java="false", java_result="skipped")
        payload["changes"]["outputs"]["docs_only"] = "false"
        self.assertTrue(verify(payload))


if __name__ == "__main__":
    unittest.main()
