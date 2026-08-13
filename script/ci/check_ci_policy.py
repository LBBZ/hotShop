#!/usr/bin/env python3
"""Static policy checks for HotShop GitHub Actions workflows."""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path


REQUIRED_WORKFLOW_NAMES = ("ci.yml", "full-verification.yml")
REQUIRED_CLEANUP_GATES = (
    "script/ci/tests/test_native_cleanup.ps1",
    "script/ci/tests/test_compose_cleanup_ownership.ps1",
)
SHA_ACTION = re.compile(
    r"^\s*-?\s*uses:\s*([^\s@]+)@([0-9a-f]{40})\s+#\s+v?\d[^\r\n]*$", re.MULTILINE
)
ANY_ACTION = re.compile(r"^\s*-?\s*uses:\s*([^\s@]+)@([^\s#]+)", re.MULTILINE)
JOB_LINE = re.compile(r"^  ([a-zA-Z0-9_-]+):\s*$")
FORBIDDEN_KEY = re.compile(
    r"(?:sk-[A-Za-z0-9_-]{16,}|AKID[A-Za-z0-9]{12,}|-----BEGIN (?:RSA |EC )?PRIVATE KEY-----)"
)
MODEL_KEY_NAME = re.compile(
    r"\b(?:DEEPSEEK|QWEN|DASHSCOPE|BAILIAN)_API_KEY\b", re.IGNORECASE
)
FROM_LINE = re.compile(
    r"^\s*FROM(?:\s+--platform=\S+)?\s+(\S+)(?:\s+AS\s+([A-Za-z0-9_.-]+))?",
    re.IGNORECASE | re.MULTILINE,
)
ARG_LINE = re.compile(
    r"^\s*ARG\s+([A-Za-z_][A-Za-z0-9_]*)=(\S+)\s*$", re.MULTILINE
)
PINNED_IMAGE = re.compile(r"^[^\s@]+@sha256:[0-9a-f]{64}$")
DOCKER_RUN_BLOCK = re.compile(
    r"(?ms)^\s*docker run\b.*?(?=^\s*docker run\b|^\s{6}-\s+name:|\Z)"
)


def check_agent_test_isolation(path: Path, text: str) -> list[str]:
    """Keep Agent tests independent of ambient pytest entry-point plugins."""
    errors: list[str] = []
    for index, block in enumerate(DOCKER_RUN_BLOCK.findall(text), start=1):
        is_pytest = re.search(r"\s-m\s+pytest(?:\s|$)", block) is not None
        is_eval = "-m hotshop_agent.eval_runner" in block
        if not is_pytest and not is_eval:
            continue
        label = f"{path}: Agent container command {index}"
        if not re.search(r"-e\s+PYTEST_DISABLE_PLUGIN_AUTOLOAD=1(?:\s|\\|$)", block):
            errors.append(f"{label} must disable pytest plugin autoload")
        if not is_pytest:
            continue
        if not re.search(r"-p\s+pytest_asyncio\.plugin(?:\s|\\|$)", block):
            errors.append(f"{label} must explicitly load pytest_asyncio.plugin")
        if "--cov" in block and not re.search(
            r"-p\s+pytest_cov\.plugin(?:\s|\\|$)", block
        ):
            errors.append(f"{label} coverage must explicitly load pytest_cov.plugin")
    return errors


def job_blocks(text: str) -> dict[str, str]:
    lines = text.splitlines()
    try:
        jobs_index = next(index for index, line in enumerate(lines) if line == "jobs:")
    except StopIteration:
        return {}
    starts: list[tuple[int, str]] = []
    for index in range(jobs_index + 1, len(lines)):
        match = JOB_LINE.match(lines[index])
        if match:
            starts.append((index, match.group(1)))
    blocks: dict[str, str] = {}
    for position, (start, name) in enumerate(starts):
        end = starts[position + 1][0] if position + 1 < len(starts) else len(lines)
        blocks[name] = "\n".join(lines[start:end])
    return blocks


def check_workflow(path: Path) -> list[str]:
    text = path.read_text(encoding="utf-8")
    errors: list[str] = []
    lowered = text.lower()

    for forbidden in ("pull_request_target", "write-all", "continue-on-error"):
        if forbidden in lowered:
            errors.append(f"{path}: forbidden token {forbidden}")
    if FORBIDDEN_KEY.search(text):
        errors.append(f"{path}: contains key-like material")
    if MODEL_KEY_NAME.search(text):
        errors.append(f"{path}: real model key variables are forbidden")
    if re.search(r"AGENT_MODEL_PROVIDER\s*:\s*(?:deepseek|qwen)\b", text, re.IGNORECASE):
        errors.append(f"{path}: Agent provider must remain fake")
    if re.search(r"(?:-D)?(?:skipTests|maven\.test\.skip)", text, re.IGNORECASE):
        errors.append(f"{path}: test-skipping Maven option is forbidden")
    if not re.search(r"(?ms)^permissions:\s*\n\s{2}contents:\s*read\s*$", text):
        errors.append(f"{path}: workflow permissions must be contents: read")
    for job, block in job_blocks(text).items():
        if re.search(r"(?m)^    permissions:\s*$", block):
            errors.append(f"{path}: job {job} must not override workflow permissions")
    if not re.search(r"(?m)^concurrency:\s*$", text):
        errors.append(f"{path}: workflow concurrency is missing")

    pinned = {(name, ref) for name, ref in SHA_ACTION.findall(text)}
    for name, ref in ANY_ACTION.findall(text):
        if name.startswith("./"):
            continue
        if (name, ref) not in pinned:
            errors.append(f"{path}: action {name}@{ref} is not a commented full SHA pin")

    if re.search(
        r"(?m)^\s+(?:-\s+)?(?:run:\s*)?docker\s+build(?:x\s+build|\s)", text
    ):
        errors.append(f"{path}: Docker images must be built with docker/build-push-action")
    build_scopes: set[str] = set()
    action_steps = re.split(r"(?m)^\s{6}-\s+", text)
    for block in action_steps:
        if "uses: docker/build-push-action@" not in block:
            continue
        if not re.search(r"(?m)^\s{10}load:\s*true\s*$", block):
            errors.append(f"{path}: docker/build-push-action must set load: true")
        if not re.search(r"(?m)^\s{10}push:\s*false\s*$", block):
            errors.append(f"{path}: docker/build-push-action must set push: false")
        from_scope = re.search(r"(?m)^\s{10}cache-from:.*\bscope=([^,\s]+)", block)
        to_scope = re.search(r"(?m)^\s{10}cache-to:.*\bscope=([^,\s]+)", block)
        if not from_scope or not to_scope or from_scope.group(1) != to_scope.group(1):
            errors.append(
                f"{path}: docker/build-push-action must use one matching cache scope"
            )
            continue
        scope = from_scope.group(1)
        if scope in build_scopes:
            errors.append(f"{path}: Docker build cache scope {scope} is reused")
        build_scopes.add(scope)
    for job, block in job_blocks(text).items():
        if "timeout-minutes:" not in block:
            errors.append(f"{path}: job {job} has no timeout-minutes")

    checkout_steps = [
        block
        for block in re.split(r"(?m)^\s{6}-\s+", text)
        if "uses: actions/checkout@" in block
    ]
    for block in checkout_steps:
        if not re.search(r"persist-credentials:\s*false", block):
            errors.append(f"{path}: checkout must disable persist-credentials")

    blocks = job_blocks(text)
    gate = blocks.get("gate", "")
    if not gate:
        errors.append(f"{path}: final gate job is missing")
    else:
        if "if: always()" not in gate:
            errors.append(f"{path}: final gate must always execute")
        if "needs:" not in gate:
            errors.append(f"{path}: final gate must depend on verification jobs")

    if path.name == "ci.yml":
        if gate and "check_gate.py" not in gate:
            errors.append(f"{path}: final gate must verify selected dependency results")
        if "pull_request:" not in text or not re.search(r"(?m)^\s{2}push:\s*$", text):
            errors.append(f"{path}: required pull_request/push triggers are missing")

    errors.extend(check_agent_test_isolation(path, text))

    return errors


def check_dockerfile(path: Path) -> list[str]:
    """Require each Dockerfile base image to resolve to an immutable digest."""
    text = path.read_text(encoding="utf-8")
    args = dict(ARG_LINE.findall(text))
    errors: list[str] = []
    from_lines = FROM_LINE.findall(text)
    if not from_lines:
        return [f"{path}: Dockerfile has no FROM instruction"]

    stages: set[str] = set()
    for image, alias in from_lines:
        resolved = image
        variable = re.fullmatch(r"\$\{([A-Za-z_][A-Za-z0-9_]*)\}", image)
        if variable:
            resolved = args.get(variable.group(1), "")
        if (
            resolved.lower() != "scratch"
            and resolved.lower() not in stages
            and not PINNED_IMAGE.fullmatch(resolved)
        ):
            errors.append(f"{path}: base image {image} is not pinned by sha256 digest")
        if alias:
            stages.add(alias.lower())
    return errors


def repository_dockerfiles(root: Path) -> list[Path]:
    ignored = {".git", "node_modules", "target", ".mypy_cache", ".ruff_cache"}
    paths: list[Path] = []
    for directory, names, files in os.walk(root):
        names[:] = [name for name in names if name not in ignored]
        base = Path(directory)
        paths.extend(
            base / name
            for name in files
            if name == "Dockerfile" or name.endswith(".Dockerfile")
        )
    return sorted(paths)


def check_cleanup_powershell(root: Path) -> list[str]:
    """Protect the cross-version native invocation and final cleanup boundary."""
    errors: list[str] = []
    verification = root / "script" / "verify-task16-compose.ps1"
    native_test = root / "script" / "ci" / "tests" / "test_native_cleanup.ps1"
    ownership_test = (
        root / "script" / "ci" / "tests" / "test_compose_cleanup_ownership.ps1"
    )

    if verification.is_file():
        text = verification.read_text(encoding="utf-8")
        marker = re.search(r"(?m)^finally \{\r?\n    \$cleanupFailures", text)
        if not marker:
            errors.append(f"{verification}: final cleanup block is missing")
        else:
            cleanup = text[marker.start() :]
            if "Invoke-HotShopNativeCommand" in cleanup:
                errors.append(
                    f"{verification}: final cleanup must only use the non-throwing wrapper"
                )
            if re.search(r"(?s)\binspect\b.*?--format", cleanup):
                errors.append(
                    f"{verification}: final cleanup must parse docker inspect JSON"
                )

    if native_test.is_file():
        text = native_test.read_text(encoding="utf-8")
        if re.search(r"[\"']-Command[\"']", text):
            errors.append(f"{native_test}: native probes must execute a temporary -File")
        for required in ("GetTempPath", "-File", "finally", "Remove-Item"):
            if required not in text:
                errors.append(f"{native_test}: missing temporary probe safeguard {required}")

    if ownership_test.is_file():
        text = ownership_test.read_text(encoding="utf-8")
        if not re.search(
            r"if \(-not \[string\]::IsNullOrWhiteSpace\(\$Mode\)\)", text
        ):
            errors.append(
                f"{ownership_test}: OwnershipTestMode must only be passed when nonempty"
            )
    return errors


def check_repository(root: Path) -> list[str]:
    errors: list[str] = []
    workflow_root = root / ".github" / "workflows"
    for name in REQUIRED_WORKFLOW_NAMES:
        path = workflow_root / name
        if not path.is_file():
            errors.append(f"missing workflow: {path}")
    workflows = sorted(
        {path for pattern in ("*.yml", "*.yaml") for path in workflow_root.glob(pattern)}
    )
    if not workflows:
        errors.append(f"no workflows found: {workflow_root}")
    for path in workflows:
        errors.extend(check_workflow(path))
    for name in REQUIRED_WORKFLOW_NAMES:
        path = workflow_root / name
        if not path.is_file():
            continue
        text = path.read_text(encoding="utf-8")
        for gate in REQUIRED_CLEANUP_GATES:
            command = re.compile(rf"(?m)^\s*&\s+\./{re.escape(gate)}\s*$")
            if not command.search(text):
                errors.append(f"{path}: required cleanup gate is missing: {gate}")
    for path in repository_dockerfiles(root):
        errors.extend(check_dockerfile(path))
    errors.extend(check_cleanup_powershell(root))
    return errors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", nargs="?", type=Path, default=Path.cwd())
    args = parser.parse_args()
    errors = check_repository(args.root.resolve())
    if errors:
        print("CI policy check failed:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        raise SystemExit(1)
    print("CI policy check passed.")


if __name__ == "__main__":
    main()
