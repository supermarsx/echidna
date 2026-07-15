#!/usr/bin/env python3
from __future__ import annotations

import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"
SUPPRESSIONS = REPO_ROOT / ".cppcheck-suppressions"


class CppcheckCiContractTest(unittest.TestCase):
    def test_cppcheck_is_blocking(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        start = workflow.index("- name: Run cppcheck on native sources")
        end = workflow.index("- name: Set up JDK for Android checks", start)
        step = workflow[start:end]

        self.assertIn("--error-exitcode=1", step)
        self.assertIn("--suppressions-list=.cppcheck-suppressions", step)
        self.assertNotIn("|| true", step)

    def test_suppressions_are_exact_reviewed_style_advisories(self) -> None:
        entries = [
            line.strip()
            for line in SUPPRESSIONS.read_text(encoding="utf-8").splitlines()
            if line.strip()
        ]

        self.assertEqual(12, len(entries))
        for entry in entries:
            with self.subTest(entry=entry):
                diagnostic, path, line = entry.rsplit(":", 2)
                self.assertIn(diagnostic, {"useStlAlgorithm", "constParameterPointer"})
                self.assertTrue(path.startswith("native/"))
                self.assertNotIn("*", path)
                self.assertGreater(int(line), 0)


if __name__ == "__main__":
    unittest.main()
