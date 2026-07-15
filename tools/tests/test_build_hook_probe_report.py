#!/usr/bin/env python3
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = REPO_ROOT / "tools" / "build_hook_probe_report.py"

spec = importlib.util.spec_from_file_location("build_hook_probe_report", TOOL_PATH)
assert spec is not None
reporter = importlib.util.module_from_spec(spec)
assert spec.loader is not None
sys.modules["build_hook_probe_report"] = reporter
spec.loader.exec_module(reporter)


class HookProbeReportTest(unittest.TestCase):
    def test_report_hashes_device_and_keeps_review_gated_database_entry(self) -> None:
        report = reporter.build_report(sample_analysis(), sample_diagnostics())
        payload = json.dumps(report, sort_keys=True)

        self.assertEqual("echidna.hook-probe-report.v1", report["schema"])
        self.assertTrue(report["deviceKey"].startswith("sha256:"))
        self.assertEqual("live_hook_and_callback_seen", report["validationState"])
        self.assertFalse(report["deviceDatabaseEntry"]["policy"]["autoApplyOffsets"])
        self.assertEqual([], report["deviceDatabaseEntry"]["offsetProfiles"])
        self.assertIn("libaudioclient.so", payload)
        self.assertNotIn("SM-G973F", payload)
        self.assertNotIn("com.example.voice", payload)
        self.assertNotIn("preset-main", payload)

    def test_empty_whitelist_action_blocks_validation_state(self) -> None:
        diagnostics = sample_diagnostics()
        diagnostics["actions"] = [{"code": "configure_whitelist"}]
        diagnostics["whitelist"]["counts"]["enabledWhitelist"] = 0

        report = reporter.build_report(sample_analysis(), diagnostics)

        self.assertEqual("blocked_no_whitelist", report["validationState"])
        self.assertIn("configure_whitelist", report["runtimeEvidence"]["actionCodes"])

    def test_cli_writes_report(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            analysis = root / "analysis.json"
            diagnostics = root / "diagnostics.json"
            output = root / "report.json"
            analysis.write_text(json.dumps(sample_analysis()), encoding="utf-8")
            diagnostics.write_text(json.dumps(sample_diagnostics()), encoding="utf-8")

            completed = subprocess.run(
                [
                    sys.executable,
                    str(TOOL_PATH),
                    str(analysis),
                    "--diagnostics",
                    str(diagnostics),
                    "--output",
                    str(output),
                ],
                check=False,
                capture_output=True,
                text=True,
            )

            self.assertEqual("", completed.stderr)
            self.assertEqual(0, completed.returncode)
            self.assertEqual(
                "hook probe evidence" in output.read_text(encoding="utf-8").lower(),
                True,
            )


def sample_analysis() -> dict[str, object]:
    return {
        "schemaVersion": 1,
        "root": "/tmp/dump",
        "device": {
            "manufacturer": "Samsung",
            "brand": "samsung",
            "model": "SM-G973F",
            "boardPlatform": "exynos9820",
            "apiLevel": "33",
            "abis": ["arm64-v8a", "armeabi-v7a"],
        },
        "vendorProfile": {
            "id": "samsung_exynos",
            "name": "Samsung Exynos",
            "score": 9,
            "confidence": "high",
            "notes": ["Collect audioserver maps."],
        },
        "libraries": [
            {
                "path": "system/lib64/libaudioclient.so",
                "role": "audiorecord_or_client",
                "processScope": "app_process",
                "arch": "aarch64",
                "elfClass": "ELF64",
                "soname": "libaudioclient.so",
                "needed": ["libbinder.so"],
                "sizeBytes": 1234,
                "matchedSymbols": ["_ZN7android11AudioRecord4readEPvmb"],
                "matchedSurfaces": ["audiorecord_native"],
                "strippedOrUnreadable": False,
            }
        ],
        "hookCandidates": [
            {
                "surface": "audiorecord_native",
                "library": "system/lib64/libaudioclient.so",
                "role": "audiorecord_or_client",
                "processScope": "app_process",
                "confidence": "high",
                "score": 88,
                "risk": "low",
                "symbols": ["_ZN7android11AudioRecord4readEPvmb"],
            }
        ],
    }


def sample_diagnostics() -> dict[str, object]:
    return {
        "schema": "echidna.diagnostics.v1",
        "status": {
            "magiskModuleInstalled": True,
            "zygiskEnabled": True,
            "selinuxState": "ENFORCING_WITH_POLICY",
        },
        "whitelist": {
            "counts": {
                "enabledWhitelist": 1,
                "disabledWhitelist": 0,
                "appBindings": 1,
            }
        },
        "telemetry": {
            "totalCallbacks": 4,
            "xruns": 0,
            "hooks": [
                {
                    "name": "AudioRecord",
                    "library": "libaudioclient.so",
                    "symbol": "AudioRecord::read",
                    "reason": "installed for sha256:abc",
                    "attempts": 2,
                    "successes": 2,
                    "failures": 0,
                    "successRate": 1.0,
                }
            ],
        },
        "actions": [],
    }


if __name__ == "__main__":
    unittest.main()
