#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = REPO_ROOT / "tools" / "check_mermaid.py"

spec = importlib.util.spec_from_file_location("check_mermaid", TOOL_PATH)
assert spec is not None
checker = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["check_mermaid"] = checker
spec.loader.exec_module(checker)


class MermaidCheckerTest(unittest.TestCase):
    def test_render_passes_explicit_puppeteer_configuration(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            workdir = Path(tmp)
            config = workdir / "puppeteer.json"
            config.write_text(json.dumps({"args": ["--no-sandbox"]}), encoding="utf-8")
            block = checker.MermaidBlock(
                source=REPO_ROOT / "docs" / "architecture.md",
                index=1,
                fence_line=18,
                text="flowchart LR\n  A --> B",
            )
            completed = subprocess.CompletedProcess([], 0, "", "")

            with mock.patch.object(checker.subprocess, "run", return_value=completed) as run:
                checker.render_block(
                    block,
                    workdir,
                    ["npx", "mmdc"],
                    puppeteer_config=config,
                )

        command = run.call_args.args[0]
        self.assertIn("--puppeteerConfigFile", command)
        config_index = command.index("--puppeteerConfigFile") + 1
        self.assertEqual(str(config.resolve()), command[config_index])

    def test_docs_workflow_uses_ci_puppeteer_configuration(self) -> None:
        workflow = (REPO_ROOT / ".github" / "workflows" / "docs.yml").read_text(
            encoding="utf-8"
        )
        config_path = REPO_ROOT / ".github" / "puppeteer-ci.json"

        self.assertIn(
            "python tools/check_mermaid.py --puppeteer-config .github/puppeteer-ci.json",
            workflow,
        )
        self.assertEqual(
            {"args": ["--no-sandbox"]},
            json.loads(config_path.read_text(encoding="utf-8")),
        )


if __name__ == "__main__":
    unittest.main()
