#!/usr/bin/env python3
"""Fail-safe component detection for HotShop CI."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


COMPONENTS = ("java", "agent", "web", "openapi", "docker")
ALL_COMPONENTS = frozenset(COMPONENTS)
JAVA_ROOTS = frozenset(
    {"common", "infrastructure", "domain", "database", "portal", "admin", "security", "task"}
)
GLOBAL_FILES = frozenset(
    {
        ".dockerignore",
        ".env.example",
        ".gitattributes",
        ".gitignore",
        "docker-compose.yml",
        "mvnw",
        "mvnw.cmd",
        "pom.xml",
    }
)


def normalize_paths(paths: list[str]) -> list[str]:
    normalized: list[str] = []
    for raw_path in paths:
        if not raw_path.strip():
            continue
        path = raw_path.replace("\\", "/")
        normalized.append(path[2:] if path.startswith("./") else path)
    return normalized


def change_metadata(paths: list[str]) -> dict[str, bool]:
    normalized = normalize_paths(paths)
    return {
        "ci_security": any(
            path == ".gitleaks.toml"
            or path.startswith((".github/", "script/ci/"))
            for path in normalized
        ),
        "docs_only": bool(normalized)
        and all(path == "README.md" or path.startswith("docs/") for path in normalized),
    }


def classify_paths(paths: list[str]) -> dict[str, bool]:
    selected: set[str] = set()
    normalized = normalize_paths(paths)

    if not normalized:
        selected.update(ALL_COMPONENTS)

    for path in normalized:
        parts = path.split("/")
        root = parts[0]

        if path in GLOBAL_FILES or path.startswith((".github/", ".mvn/", "script/ci/")):
            selected.update(ALL_COMPONENTS)
        elif root in JAVA_ROOTS:
            selected.update({"java", "openapi", "docker"})
        elif root == "agent":
            selected.update({"agent", "docker"})
        elif root == "web":
            selected.update({"web", "docker"})
        elif path.startswith("docs/api/") or path in {
            "script/canonicalize_openapi.py",
            "script/check_openapi_compatibility.py",
            "script/generate-api-client.ps1",
            "script/generate-openapi.ps1",
            "script/update-openapi-baseline.ps1",
        }:
            selected.update({"openapi", "web"})
        elif root == "docker":
            selected.update(ALL_COMPONENTS)
        elif root == "docs" or path == "README.md":
            continue
        else:
            # Unknown non-document changes run everything instead of risking a false green.
            selected.update(ALL_COMPONENTS)

    return {component: component in selected for component in COMPONENTS}


def changed_paths(base: str, head: str, *, cwd: Path | None = None) -> list[str]:
    if not base or set(base) == {"0"}:
        command = ["git", "ls-tree", "-r", "--name-only", head]
    else:
        command = ["git", "diff", "--name-only", "--diff-filter=ACMRTUXBD", base, head]
    result = subprocess.run(command, check=True, text=True, capture_output=True, cwd=cwd)
    return [line for line in result.stdout.splitlines() if line]


def write_outputs(path: Path, results: dict[str, bool]) -> None:
    with path.open("a", encoding="utf-8") as output:
        for component, enabled in results.items():
            output.write(f"{component}={str(enabled).lower()}\n")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()

    paths = changed_paths(args.base, args.head)
    results = classify_paths(paths)
    metadata = change_metadata(paths)
    if args.output:
        write_outputs(args.output, results | metadata)
    payload = {"paths": paths, "components": results, "categories": metadata}
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(payload, separators=(",", ":")))


if __name__ == "__main__":
    main()
