"""Check that the Unix entry point delegates to the single local lifecycle."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]

@unittest.skipUnless(shutil.which("sh"), "POSIX shell unavailable")
class DeployScriptTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        directory = Path(self.temp.name)
        self.log = directory / "calls.json"
        fake = directory / "pwsh"
        fake.write_text("#!/usr/bin/env python3\nimport json, os, sys\n"
                        "with open(os.environ['LIFECYCLE_LOG'], 'w') as log:\n"
                        "    json.dump(sys.argv[1:], log)\n"
                        "sys.exit(int(os.environ.get('FAKE_EXIT', '0')))\n")
        fake.chmod(0o755)
        self.env = dict(os.environ, PATH=f"{directory}{os.pathsep}{os.environ['PATH']}", LIFECYCLE_LOG=str(self.log))

    def run_script(self, *args):
        return subprocess.run(["sh", str(ROOT / "script/deploy.sh"), *args], env=self.env, capture_output=True, text=True, timeout=10)

    def test_start_delegates_without_build_or_project_overrides(self):
        result = self.run_script("-a", "start")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.log.read_text())[-2:], ["-Action", "Start"])

    def test_service_build_delegates(self):
        result = self.run_script("-a", "build", "-s", "portal-service")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.log.read_text())[-4:], ["-Action", "Build", "-Service", "portal-service"])

    def test_second_project_rejected(self):
        self.assertEqual(self.run_script("-p", "another-project").returncode, 2)
        self.assertFalse(self.log.exists())

    def test_service_argument_requires_build(self):
        self.assertEqual(self.run_script("-a", "start", "-s", "portal-service").returncode, 2)
        self.assertFalse(self.log.exists())

    def test_failure_propagates(self):
        self.env["FAKE_EXIT"] = "17"
        self.assertEqual(self.run_script("-a", "start").returncode, 17)

if __name__ == "__main__":
    unittest.main()
