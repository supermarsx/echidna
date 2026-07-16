#!/usr/bin/env python3
from __future__ import annotations

import copy
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[2]
TOOL_PATH = REPO_ROOT / "tools/perf/compare_audio_pipeline_reports.py"
ARTIFACT_PATH = REPO_ROOT / "tools/perf/audio_pipeline_post_handoff_comparison.json"
spec = importlib.util.spec_from_file_location("compare_audio_pipeline_reports", TOOL_PATH)
assert spec is not None and spec.loader is not None
comparison = importlib.util.module_from_spec(spec)
spec.loader.exec_module(comparison)


class AudioPipelineComparisonTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.artifact = comparison.load_json(ARTIFACT_PATH)

    def test_committed_comparison_is_current_and_consistent(self) -> None:
        comparison.verify_comparison(copy.deepcopy(self.artifact))

    def test_missing_comparison_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing.json"
            with self.assertRaises(comparison.ComparisonError):
                comparison.load_json(missing)

    def test_stale_harness_hash_is_rejected(self) -> None:
        stale = copy.deepcopy(self.artifact)
        stale["harness"]["benchmark_source_sha256"] = "0" * 64
        with self.assertRaisesRegex(comparison.ComparisonError, "stale"):
            comparison.verify_comparison(stale)

    def test_stale_production_source_bundle_is_rejected(self) -> None:
        stale = copy.deepcopy(self.artifact)
        stale["harness"]["production_source_bundle_sha256"] = "0" * 64
        with self.assertRaisesRegex(comparison.ComparisonError, "production source bundle"):
            comparison.verify_comparison(stale)

    def test_stale_production_source_inventory_is_rejected(self) -> None:
        stale = copy.deepcopy(self.artifact)
        stale["harness"]["production_source_file_count"] += 1
        with self.assertRaisesRegex(comparison.ComparisonError, "inventory"):
            comparison.verify_comparison(stale)

    def test_source_hashes_are_line_ending_stable_and_content_sensitive(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "native/dsp/source.cpp"
            source.parent.mkdir(parents=True)
            with mock.patch.object(comparison, "REPO_ROOT", root):
                source.write_bytes(b"int value = 1;\r\nreturn value;\r\n")
                crlf_source_hash = comparison.source_sha256(source)
                crlf_bundle_hash = comparison.production_source_bundle_sha256([source])

                source.write_bytes(b"int value = 1;\nreturn value;\n")
                self.assertEqual(crlf_source_hash, comparison.source_sha256(source))
                self.assertEqual(
                    crlf_bundle_hash,
                    comparison.production_source_bundle_sha256([source]),
                )

                source.write_bytes(b"int value = 2;\nreturn value;\n")
                self.assertNotEqual(crlf_source_hash, comparison.source_sha256(source))
                self.assertNotEqual(
                    crlf_bundle_hash,
                    comparison.production_source_bundle_sha256([source]),
                )

    def test_source_hash_rejects_non_utf8_content(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "source.cpp"
            source.write_bytes(b"\xff\xfe")
            with self.assertRaisesRegex(comparison.ComparisonError, "not UTF-8"):
                comparison.source_sha256(source)

    def test_malformed_group_inventory_is_rejected(self) -> None:
        malformed = copy.deepcopy(self.artifact)
        malformed["groups"].pop()
        with self.assertRaisesRegex(comparison.ComparisonError, "summary count"):
            comparison.verify_comparison(malformed)

    def test_contract_change_requires_source_declared_value(self) -> None:
        malformed = copy.deepcopy(self.artifact)
        check = next(
            item for item in malformed["audio_checks"] if item["id"] == "public_api_version"
        )
        check["current_metrics"]["runtime_version"] -= 1
        with self.assertRaisesRegex(comparison.ComparisonError, "deltas|source declaration"):
            comparison.verify_comparison(malformed)

    def test_contract_change_requires_allowlisted_rationale(self) -> None:
        malformed = copy.deepcopy(self.artifact)
        check = next(
            item for item in malformed["audio_checks"] if item["id"] == "public_api_version"
        )
        check["contract_change"]["rationale"] = ""
        with self.assertRaisesRegex(comparison.ComparisonError, "rationale"):
            comparison.verify_comparison(malformed)

    def test_result_cannot_claim_a_false_pass(self) -> None:
        dishonest = copy.deepcopy(self.artifact)
        dishonest["result"]["material_regression_count"] = 99
        with self.assertRaisesRegex(comparison.ComparisonError, "overall"):
            comparison.verify_comparison(dishonest)

    def test_artifact_is_compact(self) -> None:
        encoded = json.dumps(self.artifact, separators=(",", ":")).encode("utf-8")
        self.assertLess(len(encoded), 100_000)


if __name__ == "__main__":
    unittest.main()
