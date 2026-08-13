from __future__ import annotations

import subprocess
import tempfile
import unittest
from pathlib import Path

from script.ci.detect_changes import change_metadata, changed_paths, classify_paths


class DetectChangesTest(unittest.TestCase):
    def assert_deleted_file_selects(
        self, deleted_path: str, companion_path: str, expected: dict[str, bool]
    ) -> None:
        with tempfile.TemporaryDirectory() as directory:
            repository = Path(directory)
            subprocess.run(["git", "init", "--quiet"], cwd=repository, check=True)
            subprocess.run(
                ["git", "config", "user.email", "ci-test@example.invalid"],
                cwd=repository,
                check=True,
            )
            subprocess.run(
                ["git", "config", "user.name", "CI Test"], cwd=repository, check=True
            )
            deleted = repository / deleted_path
            deleted.parent.mkdir(parents=True)
            deleted.write_text("tracked\n", encoding="utf-8")
            companion = repository / companion_path
            companion.parent.mkdir(parents=True, exist_ok=True)
            companion.write_text("before\n", encoding="utf-8")
            subprocess.run(["git", "add", "."], cwd=repository, check=True)
            subprocess.run(
                ["git", "commit", "--quiet", "-m", "initial"], cwd=repository, check=True
            )
            base = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=repository, check=True,
                capture_output=True, text=True,
            ).stdout.strip()
            deleted.unlink()
            companion.write_text("after\n", encoding="utf-8")
            subprocess.run(
                ["git", "commit", "--quiet", "-am", "delete component file"],
                cwd=repository,
                check=True,
            )
            head = subprocess.run(
                ["git", "rev-parse", "HEAD"], cwd=repository, check=True,
                capture_output=True, text=True,
            ).stdout.strip()

            paths = changed_paths(base, head, cwd=repository)
            self.assertEqual(set(paths), {deleted_path, companion_path})
            self.assertEqual(classify_paths(paths), expected)

    def test_document_only_change_selects_no_expensive_component(self) -> None:
        self.assertEqual(
            classify_paths(["docs/quality/ci.md"]),
            {"java": False, "agent": False, "web": False, "openapi": False, "docker": False},
        )
        self.assertEqual(
            change_metadata(["README.md", "docs/quality/ci.md"]),
            {"ci_security": False, "docs_only": True},
        )

    def test_workflow_change_selects_every_component(self) -> None:
        self.assertTrue(all(classify_paths([".github/workflows/ci.yml"]).values()))
        self.assertEqual(
            change_metadata([".github/workflows/ci.yml"]),
            {"ci_security": True, "docs_only": False},
        )

    def test_root_pom_selects_every_component(self) -> None:
        self.assertTrue(all(classify_paths(["pom.xml"]).values()))

    def test_java_change_selects_java_contract_and_docker(self) -> None:
        selected = classify_paths(["portal/src/main/java/example.java"])
        self.assertEqual(
            selected,
            {"java": True, "agent": False, "web": False, "openapi": True, "docker": True},
        )

    def test_unknown_path_fails_safe(self) -> None:
        self.assertTrue(all(classify_paths(["new-root-tool.conf"]).values()))

    def test_empty_diff_fails_safe(self) -> None:
        self.assertTrue(all(classify_paths([]).values()))

    def test_deleted_portal_file_and_readme_select_java_openapi_docker(self) -> None:
        self.assert_deleted_file_selects(
            "portal/src/main/java/Deleted.java", "README.md",
            {"java": True, "agent": False, "web": False, "openapi": True, "docker": True},
        )

    def test_deleted_agent_file_and_docs_select_agent_docker(self) -> None:
        self.assert_deleted_file_selects(
            "agent/src/hotshop_agent/deleted.py", "docs/quality/note.md",
            {"java": False, "agent": True, "web": False, "openapi": False, "docker": True},
        )

    def test_deleted_web_file_and_readme_select_web_docker(self) -> None:
        self.assert_deleted_file_selects(
            "web/package.json", "README.md",
            {"java": False, "agent": False, "web": True, "openapi": False, "docker": True},
        )


if __name__ == "__main__":
    unittest.main()
