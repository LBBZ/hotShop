#!/usr/bin/env python3
"""Verify that every job selected by change detection really succeeded."""

from __future__ import annotations

import json
import os
import sys


JOB_COMPONENTS = {
    "java": "java",
    "agent": "agent",
    "web": "web",
    "openapi": "openapi",
    "docker": "docker",
}
CHANGE_OUTPUTS = (*JOB_COMPONENTS.values(), "docs_only")


def verify(needs: dict[str, dict[str, object]]) -> list[str]:
    failures: list[str] = []
    changes = needs.get("changes", {})
    if changes.get("result") != "success":
        return ["change detection did not succeed"]
    outputs = changes.get("outputs", {})
    if not isinstance(outputs, dict):
        return ["change detection outputs are missing"]
    for component in CHANGE_OUTPUTS:
        if component not in outputs:
            failures.append(f"change detection output {component} is missing")
        elif outputs[component] not in {"true", "false"}:
            failures.append(
                f"change detection output {component} has invalid value {outputs[component]!r}"
            )
    if failures:
        return failures
    if not any(outputs[component] == "true" for component in JOB_COMPONENTS.values()):
        if outputs["docs_only"] != "true":
            return ["change detection selected no component without marking docs_only"]

    always_required = ("ci-security",)
    for job in always_required:
        result = needs.get(job, {}).get("result")
        if result != "success":
            failures.append(f"required job {job} ended as {result!r}")

    for job, component in JOB_COMPONENTS.items():
        expected = outputs[component] == "true"
        result = needs.get(job, {}).get("result")
        if expected and result != "success":
            failures.append(f"selected job {job} ended as {result!r}")
        if not expected and result not in {"skipped", "success"}:
            failures.append(f"unselected job {job} ended unexpectedly as {result!r}")
    return failures


def main() -> None:
    needs = json.loads(os.environ["HOTSHOP_NEEDS_JSON"])
    failures = verify(needs)
    if failures:
        print("CI gate failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        raise SystemExit(1)
    print("CI gate passed: every selected job succeeded.")


if __name__ == "__main__":
    main()
