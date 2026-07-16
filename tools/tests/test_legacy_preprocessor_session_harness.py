#!/usr/bin/env python3
from __future__ import annotations

import unittest
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
HARNESS = (
    REPO_ROOT
    / "android"
    / "lsposed-shim"
    / "shim"
    / "src"
    / "androidTest"
    / "java"
    / "com"
    / "echidna"
    / "lsposed"
    / "hooks"
    / "LegacyPreprocessorSessionInstrumentedTest.java"
)


class LegacyPreprocessorSessionHarnessTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.source = HARNESS.read_text(encoding="utf-8")
        baseline_start = cls.source.index(
            "public void apiSevenBinderBoundaryAndInjectedBaselineAreModuleIndependent()"
        )
        enabled_start = cls.source.index(
            "public void readyModuleMutatesInjectedAudioAndRejectsInvalidProofs()"
        )
        helper_start = cls.source.index("private static AudioCapture captureBaseline(")
        cls.baseline = cls.source[baseline_start:enabled_start]
        cls.enabled = cls.source[enabled_start:helper_start]

    def test_module_independent_gate_has_no_lifecycle_assumption(self) -> None:
        self.assertNotIn("Assume.", self.baseline)
        for token in (
            "ProviderSession.open(context, evidenceDirectory, true)",
            '"oneWayPidZeroRejected", true',
            '"baselineRms"',
            '"baselineToneMagnitude"',
        ):
            self.assertIn(token, self.baseline)

    def test_enabled_gate_has_one_early_exact_readiness_assumption(self) -> None:
        self.assertEqual(1, self.source.count("Assume.assumeTrue("))
        assume_at = self.enabled.index("Assume.assumeTrue(")
        provider_at = self.enabled.index("ProviderSession.open(")
        baseline_at = self.enabled.index("captureBaseline(")
        self.assertLess(assume_at, provider_at)
        self.assertLess(assume_at, baseline_at)

        for value in (
            '"ready".equals(lifecycleStatus)',
            'EXPECTED_MODULE_ID = "echidna"',
            'EXPECTED_MODULE_VERSION = "0.0.0"',
            'EXPECTED_MODULE_VERSION_CODE = "000"',
            '"cea16af9a4a617e6277b5e55cfb5bf2619ebaf0f"',
            "54dd0e373fbc3bc050dd8147ffdfbc6ea613d050dcaf36f09ea3e59ebd95834f",
        ):
            self.assertIn(value, self.source)

    def test_ready_enabled_path_keeps_all_proof_failures_hard(self) -> None:
        self.assertNotIn("Assume.", self.enabled[self.enabled.index("ProviderSession.open(") :])
        for token in (
            "production manager did not authorize and enable the effect",
            "replacement capability must be genuinely signed",
            "effect must process frames",
            "effect must report real sample mutation",
            "telemetry sequence must advance",
            "tampered",
            "replacementNonce",
            "-9 dB DSP must attenuate RMS once",
            "1 kHz spectral magnitude must attenuate once",
        ):
            self.assertIn(token, self.enabled)


if __name__ == "__main__":
    unittest.main()
