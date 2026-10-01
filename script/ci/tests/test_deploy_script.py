"""Verify deployment ownership without contacting a Docker daemon."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]


class DeployScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        directory = Path(self.temp.name)
        self.log = directory / "calls.jsonl"
        fake = directory / "docker"
        fake.write_text("#!/usr/bin/env python3\nimport json, os, sys\n"
                        "with open(os.environ['DOCKER_CALL_LOG'], 'a') as log:\n"
                        "    log.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                        "sys.exit(int(os.environ.get('FAKE_DOCKER_EXIT', '0')))\n")
        fake.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{directory}{os.pathsep}{os.environ['PATH']}",
                        DOCKER_CALL_LOG=str(self.log))

    def run_script(self, *args):
        return subprocess.run(["sh", str(ROOT / "script/deploy.sh"), *args],
                              env=self.env, capture_output=True, text=True, timeout=10)

    def calls(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_clean_only_removes_named_project_containers_and_network(self):
        result = self.run_script("-a", "clean", "-p", "hotshop-test-owned")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.calls()
        self.assertEqual(calls[0], ["compose", "version"])
        self.assertEqual(calls[1], ["compose", "--project-name", "hotshop-test-owned",
                         "-f", "docker-compose.yml", "--profile", "app", "--profile", "agent",
                         "down", "--remove-orphans"])

    def test_build_uses_compose_and_current_service_name(self):
        result = self.run_script("-a", "build", "-p", "hotshop-test-owned", "-s", "portal-service")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.calls()[-1][-2:], ["build", "portal-service"])

    def test_docker_failure_is_not_reported_as_success(self):
        self.env["FAKE_DOCKER_EXIT"] = "17"
        self.assertEqual(self.run_script("-a", "start").returncode, 17)
        self.assertEqual(len(self.calls()), 1)

    def test_invalid_project_is_rejected_before_docker(self):
        self.assertEqual(self.run_script("-p", "--all").returncode, 2)
        self.assertFalse(self.log.exists())

    def test_explicit_env_file_and_arguments_remain_separate(self):
        result = self.run_script("-a", "config", "-f", "path with spaces.env")
        self.assertEqual(result.returncode, 0, result.stderr)
        call = self.calls()[-1]
        self.assertEqual(call[call.index("--env-file") + 1], "path with spaces.env")
        self.assertEqual(call[-2:], ["config", "--quiet"])


if __name__ == "__main__":
    unittest.main()
