#!/usr/bin/env python3
from __future__ import annotations

import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CI_WORKFLOW = REPO_ROOT / ".github" / "workflows" / "ci.yml"
SUPPRESSIONS = REPO_ROOT / ".cppcheck-suppressions"


class CppcheckCiContractTest(unittest.TestCase):
    def test_non_blocking_instrumentation_does_not_gate_release(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        instrumentation_start = workflow.index("  instrumentation-tests:")
        release_start = workflow.index("  release:", instrumentation_start)
        instrumentation_job = workflow[instrumentation_start:release_start]
        release_job = workflow[release_start:]

        self.assertIn("continue-on-error: true", instrumentation_job)
        needs_start = release_job.index("    needs:")
        needs_end = release_job.index("    if:", needs_start)
        release_needs = release_job[needs_start:needs_end]
        self.assertNotIn("- instrumentation-tests", release_needs)

    def test_cppcheck_is_blocking(self) -> None:
        workflow = CI_WORKFLOW.read_text(encoding="utf-8")
        start = workflow.index("- name: Run cppcheck on native sources")
        end = workflow.index("- name: Set up JDK for Android checks", start)
        step = workflow[start:end]

        self.assertIn("--error-exitcode=1", step)
        self.assertIn("--suppressions-list=.cppcheck-suppressions", step)
        self.assertNotIn("|| true", step)

    def test_suppressions_are_exact_reviewed_style_advisories(self) -> None:
        # Comment lines (leading '#') document why a fixture pointer is left plain;
        # they are not suppression entries, so they are excluded from the contract.
        entries = [
            line.strip()
            for line in SUPPRESSIONS.read_text(encoding="utf-8").splitlines()
            if line.strip() and not line.lstrip().startswith("#")
        ]

        self.assertEqual(17, len(entries))
        for entry in entries:
            with self.subTest(entry=entry):
                diagnostic, path, line = entry.rsplit(":", 2)
                self.assertIn(
                    diagnostic,
                    {"useStlAlgorithm", "constParameterPointer", "constVariablePointer"},
                )
                self.assertTrue(path.startswith("native/"))
                self.assertNotIn("*", path)
                self.assertGreater(int(line), 0)


if __name__ == "__main__":
    unittest.main()
