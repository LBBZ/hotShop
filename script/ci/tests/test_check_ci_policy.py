from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from script.ci.check_ci_policy import (
    check_agent_test_isolation,
    check_cleanup_powershell,
    check_dockerfile,
    check_repository,
    check_workflow,
)


VALID = """name: test
on:
  pull_request:
  push:
permissions:
  contents: read
concurrency:
  group: test
jobs:
  gate:
    if: always()
    needs: [test]
    timeout-minutes: 5
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2
        with:
          persist-credentials: false
      - run: python3 script/ci/check_gate.py
"""

CLEANUP_GATES = """      - name: Verify cleanup ownership
        shell: pwsh
        run: |
          & ./script/ci/tests/test_native_cleanup.ps1
          & ./script/ci/tests/test_compose_cleanup_ownership.ps1
"""


class PolicyTest(unittest.TestCase):
    def write(self, text: str, name: str = "ci.yml") -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / name
        path.write_text(text, encoding="utf-8")
        return path

    def test_valid_minimal_policy_passes(self) -> None:
        self.assertEqual(check_workflow(self.write(VALID)), [])

    def test_floating_action_is_rejected(self) -> None:
        errors = check_workflow(self.write(VALID.replace("11bd71901bbe5b1630ceea73d27597364c9af683 # v4.2.2", "v4")))
        self.assertTrue(any("not a commented full SHA pin" in error for error in errors))

    def test_pull_request_target_is_rejected(self) -> None:
        errors = check_workflow(self.write(VALID.replace("pull_request:", "pull_request_target:")))
        self.assertTrue(any("pull_request_target" in error for error in errors))

    def test_skip_tests_is_rejected(self) -> None:
        errors = check_workflow(self.write(VALID + "      - run: ./mvnw -DskipTests verify\n"))
        self.assertTrue(any("test-skipping" in error for error in errors))

    def test_real_provider_is_rejected(self) -> None:
        errors = check_workflow(self.write(VALID + "        env:\n          AGENT_MODEL_PROVIDER: qwen\n"))
        self.assertTrue(any("provider must remain fake" in error for error in errors))

    def test_missing_timeout_is_rejected(self) -> None:
        errors = check_workflow(self.write(VALID.replace("    timeout-minutes: 5\n", "")))
        self.assertTrue(any("no timeout-minutes" in error for error in errors))

    def test_missing_concurrency_is_rejected(self) -> None:
        errors = check_workflow(
            self.write(VALID.replace("concurrency:\n  group: test\n", ""))
        )
        self.assertTrue(any("concurrency is missing" in error for error in errors))

    def test_model_key_variable_is_rejected(self) -> None:
        errors = check_workflow(
            self.write(VALID + "        env:\n          DEEPSEEK_API_KEY: placeholder\n")
        )
        self.assertTrue(any("model key variables" in error for error in errors))

    def test_full_workflow_without_gate_is_rejected(self) -> None:
        errors = check_workflow(
            self.write(VALID.replace("  gate:\n", "  verification:\n"), "full-verification.yml")
        )
        self.assertTrue(any("final gate job is missing" in error for error in errors))

    def test_pinned_dockerfile_passes(self) -> None:
        path = self.write(
            "FROM python:3.12-slim@sha256:" + "a" * 64 + " AS base\n",
            "Dockerfile",
        )
        self.assertEqual(check_dockerfile(path), [])

    def test_digestless_dockerfile_base_is_rejected(self) -> None:
        path = self.write("FROM python:3.12-slim\n", "Dockerfile")
        errors = check_dockerfile(path)
        self.assertTrue(any("not pinned by sha256 digest" in error for error in errors))

    def test_pinned_arg_dockerfile_base_passes(self) -> None:
        path = self.write(
            "ARG BASE=python:3.12-slim@sha256:" + "b" * 64 + "\nFROM ${BASE}\n",
            "Dockerfile",
        )
        self.assertEqual(check_dockerfile(path), [])

    def test_build_action_requires_load_push_and_cache_scope(self) -> None:
        build = """      - uses: docker/build-push-action@263435318d21b8e681c14492fe198d362a7d2c83 # v6.18.0
        with:
          context: .
          load: false
          tags: example:test
"""
        errors = check_workflow(self.write(VALID + build))
        self.assertTrue(any("load: true" in error for error in errors))
        self.assertTrue(any("push: false" in error for error in errors))
        self.assertTrue(any("matching cache scope" in error for error in errors))

    def test_direct_docker_build_is_rejected(self) -> None:
        errors = check_workflow(
            self.write(VALID + "      - run: docker build -t example:test .\n")
        )
        self.assertTrue(any("docker/build-push-action" in error for error in errors))

    def test_reused_build_cache_scope_is_rejected(self) -> None:
        build = """      - uses: docker/build-push-action@263435318d21b8e681c14492fe198d362a7d2c83 # v6.18.0
        with:
          load: true
          push: false
          cache-from: type=gha,scope=reused
          cache-to: type=gha,mode=max,scope=reused
"""
        errors = check_workflow(self.write(VALID + build + build))
        self.assertTrue(any("cache scope reused is reused" in error for error in errors))

    def test_repository_checks_additional_workflow(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        workflow_root = root / ".github" / "workflows"
        workflow_root.mkdir(parents=True)
        (workflow_root / "ci.yml").write_text(VALID + CLEANUP_GATES, encoding="utf-8")
        (workflow_root / "full-verification.yml").write_text(
            VALID.replace("pull_request:\n  push:\n", "workflow_dispatch:\n")
            + CLEANUP_GATES,
            encoding="utf-8",
        )
        (workflow_root / "extra.yaml").write_text(
            VALID.replace("    timeout-minutes: 5\n", ""), encoding="utf-8"
        )
        (root / "Dockerfile").write_text(
            "FROM alpine@sha256:" + "c" * 64 + "\n", encoding="utf-8"
        )
        errors = check_repository(root)
        self.assertTrue(any("extra.yaml: job gate has no timeout-minutes" in error for error in errors))

    def test_repository_rejects_additional_workflow_job_write_permission(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        workflow_root = root / ".github" / "workflows"
        workflow_root.mkdir(parents=True)
        (workflow_root / "ci.yml").write_text(VALID + CLEANUP_GATES, encoding="utf-8")
        (workflow_root / "full-verification.yml").write_text(
            VALID.replace("pull_request:\n  push:\n", "workflow_dispatch:\n")
            + CLEANUP_GATES,
            encoding="utf-8",
        )
        extra = VALID.replace(
            "  gate:\n    if:",
            "  gate:\n    permissions:\n      contents: write\n    if:",
        )
        (workflow_root / "extra.yaml").write_text(extra, encoding="utf-8")
        (root / "Dockerfile").write_text(
            "FROM alpine@sha256:" + "c" * 64 + "\n", encoding="utf-8"
        )
        errors = check_repository(root)
        self.assertTrue(any("extra.yaml: job gate must not override" in error for error in errors))

    def test_repository_requires_compose_cleanup_gates(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        workflow_root = root / ".github" / "workflows"
        workflow_root.mkdir(parents=True)
        (workflow_root / "ci.yml").write_text(VALID + CLEANUP_GATES, encoding="utf-8")
        (workflow_root / "full-verification.yml").write_text(
            VALID.replace("pull_request:\n  push:\n", "workflow_dispatch:\n")
            + CLEANUP_GATES.replace(
                "          & ./script/ci/tests/test_native_cleanup.ps1\n", ""
            ),
            encoding="utf-8",
        )
        (root / "Dockerfile").write_text(
            "FROM alpine@sha256:" + "c" * 64 + "\n", encoding="utf-8"
        )
        errors = check_repository(root)
        self.assertTrue(
            any("required cleanup gate is missing" in error for error in errors)
        )

    def test_agent_pytest_plugin_isolation_passes(self) -> None:
        command = """          docker run --rm --network none \\
            -e PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \\
            --entrypoint python example:test \\
            -m pytest -p pytest_asyncio.plugin -p pytest_cov.plugin --cov=example
"""
        self.assertEqual(check_agent_test_isolation(self.write(VALID), command), [])

    def test_agent_pytest_without_autoload_guard_is_rejected(self) -> None:
        command = """          docker run --rm --network none \\
            --entrypoint python example:test \\
            -m pytest -p pytest_asyncio.plugin
"""
        errors = check_agent_test_isolation(self.write(VALID), command)
        self.assertTrue(any("disable pytest plugin autoload" in error for error in errors))

    def test_agent_pytest_without_explicit_plugins_is_rejected(self) -> None:
        command = """          docker run --rm --network none \\
            -e PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 \\
            --entrypoint python example:test -m pytest --cov=example
"""
        errors = check_agent_test_isolation(self.write(VALID), command)
        self.assertTrue(any("pytest_asyncio.plugin" in error for error in errors))
        self.assertTrue(any("pytest_cov.plugin" in error for error in errors))

    def test_agent_eval_without_autoload_guard_is_rejected(self) -> None:
        command = """          docker run --rm --network none \\
            --entrypoint python example:test \\
            -m hotshop_agent.eval_runner --suite quick
"""
        errors = check_agent_test_isolation(self.write(VALID), command)
        self.assertTrue(any("disable pytest plugin autoload" in error for error in errors))

    def cleanup_root(
        self, verification: str, native_test: str, ownership_test: str
    ) -> Path:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        paths = {
            root / "script" / "verify-task16-compose.ps1": verification,
            root / "script" / "ci" / "tests" / "test_native_cleanup.ps1": native_test,
            root
            / "script"
            / "ci"
            / "tests"
            / "test_compose_cleanup_ownership.ps1": ownership_test,
        }
        for path, text in paths.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
        return root

    def test_cleanup_powershell_policy_passes_safe_patterns(self) -> None:
        root = self.cleanup_root(
            "finally {\n    $cleanupFailures = @()\n"
            "Invoke-HotShopCleanupNativeStep\nConvertFrom-Json\n}\n",
            "$p = [IO.Path]::GetTempPath()\ntry { -File } finally { Remove-Item }\n",
            "if (-not [string]::IsNullOrWhiteSpace($Mode)) {\n"
            '  $arguments += @("-OwnershipTestMode", $Mode)\n}\n',
        )
        self.assertEqual(check_cleanup_powershell(root), [])

    def test_cleanup_powershell_policy_rejects_direct_native_and_format(self) -> None:
        root = self.cleanup_root(
            "finally {\n    $cleanupFailures = @()\n"
            'Invoke-HotShopNativeCommand docker inspect --format "{{.Id}}"\n}\n',
            "$p = [IO.Path]::GetTempPath()\ntry { -File } finally { Remove-Item }\n",
            "if (-not [string]::IsNullOrWhiteSpace($Mode)) {}\n",
        )
        errors = check_cleanup_powershell(root)
        self.assertTrue(any("non-throwing wrapper" in error for error in errors))
        self.assertTrue(any("inspect JSON" in error for error in errors))

    def test_cleanup_powershell_policy_rejects_command_and_empty_mode_risk(self) -> None:
        root = self.cleanup_root(
            "finally {\n    $cleanupFailures = @()\n}\n",
            '$args = @("-Command", "exit 0")\n',
            '$arguments = @("-OwnershipTestMode", $Mode)\n',
        )
        errors = check_cleanup_powershell(root)
        self.assertTrue(any("temporary -File" in error for error in errors))
        self.assertTrue(any("only be passed when nonempty" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
